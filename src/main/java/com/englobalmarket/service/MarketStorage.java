package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.util.PriceUtil;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite 数据层：上架记录与收货箱。
 *
 * <p>主线程同步读写。写操作一律返回是否真正落库，读操作的异常记日志后返回空结果
 * （界面层能继续打开，不会因为一次查询失败把玩家卡住）。
 */
public class MarketStorage implements ListingStore {

    private final EnGlobalMarket plugin;
    private final File dbFile;
    private Connection connection;

    public MarketStorage(EnGlobalMarket plugin, File dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    public void init() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("未找到 SQLite 驱动", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("""
                CREATE TABLE IF NOT EXISTS listings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    seller_uuid TEXT NOT NULL,
                    seller_name TEXT NOT NULL,
                    item_data BLOB NOT NULL,
                    price REAL NOT NULL,
                    listed_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS claims (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    owner_uuid TEXT NOT NULL,
                    item_data BLOB NOT NULL,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_listings_time ON listings(listed_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner_uuid)");
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    // ---------------- listings ----------------

    @Override
    public int createListing(UUID sellerUuid, String sellerName, ItemStack item, BigDecimal price) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO listings(seller_uuid, seller_name, item_data, price, listed_at) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, sellerName);
            ps.setBytes(3, item.serializeAsBytes());
            ps.setDouble(4, price.doubleValue());
            ps.setLong(5, System.currentTimeMillis());
            if (ps.executeUpdate() != 1) {
                return -1;
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("创建上架记录失败: " + e.getMessage());
        }
        return -1;
    }

    @Override
    public boolean restoreListing(Listing listing) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO listings(id, seller_uuid, seller_name, item_data, price, listed_at) VALUES(?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, listing.id());
            ps.setString(2, listing.sellerUuid().toString());
            ps.setString(3, listing.sellerName());
            ps.setBytes(4, listing.item().serializeAsBytes());
            ps.setDouble(5, listing.price().doubleValue());
            ps.setLong(6, listing.listedAt());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("恢复上架记录失败 (id=" + listing.id() + "): " + e.getMessage());
            return false;
        }
    }

    @Override
    public Listing getListing(int id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM listings WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapListing(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取上架记录失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Listing> getListings(int offset, int limit) {
        return queryListings("SELECT * FROM listings ORDER BY listed_at DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setInt(1, limit);
                    ps.setInt(2, offset);
                }, "读取上架列表失败");
    }

    @Override
    public List<Listing> getListingsBySeller(UUID seller, int offset, int limit) {
        return queryListings(
                "SELECT * FROM listings WHERE seller_uuid=? ORDER BY listed_at DESC LIMIT ? OFFSET ?",
                ps -> {
                    ps.setString(1, seller.toString());
                    ps.setInt(2, limit);
                    ps.setInt(3, offset);
                }, "读取个人上架列表失败");
    }

    @Override
    public List<Listing> getExpiredListings(long cutoffMillis) {
        return queryListings("SELECT * FROM listings WHERE listed_at <= ? ORDER BY listed_at ASC",
                ps -> ps.setLong(1, cutoffMillis), "读取过期商品失败");
    }

    @Override
    public int countListings() {
        return count("SELECT COUNT(*) FROM listings", null);
    }

    @Override
    public int countBySeller(UUID seller) {
        return count("SELECT COUNT(*) FROM listings WHERE seller_uuid=?", seller);
    }

    @Override
    public boolean updatePrice(int id, BigDecimal price) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE listings SET price=? WHERE id=?")) {
            ps.setDouble(1, price.doubleValue());
            ps.setInt(2, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("修改价格失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteListing(int id) {
        try {
            return deleteListing(connection, id) == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("删除上架记录失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 把一条上架记录转成收货箱记录：删除 + 插入在同一个事务里完成，
     * 任一步失败就整体回滚，不会出现「商品没了但收货箱也没有」的情况。
     */
    @Override
    public boolean moveListingToClaim(Listing listing, String reason) {
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            if (deleteListing(connection, listing.id()) != 1) {
                connection.rollback();
                return false;
            }
            if (insertClaim(connection, listing.sellerUuid(), listing.item(), reason) <= 0) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException e) {
            quietlyRollback();
            plugin.getLogger().severe("转移商品到收货箱失败 (id=" + listing.id() + "): " + e.getMessage());
            return false;
        } finally {
            restoreAutoCommit(autoCommit);
        }
    }

    private int deleteListing(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM listings WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Listing> queryListings(String sql, StatementBinder binder, String errorMessage) {
        List<Listing> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapListing(rs);
                    if (listing != null) {
                        out.add(listing);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(errorMessage + ": " + e.getMessage());
        }
        return out;
    }

    private int count(String sql, UUID param) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (param != null) {
                ps.setString(1, param.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("统计数据失败: " + e.getMessage());
        }
        return 0;
    }

    private Listing mapListing(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        UUID uuid;
        try {
            uuid = UUID.fromString(rs.getString("seller_uuid"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = rs.getString("seller_name");
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(rs.getBytes("item_data"));
        } catch (Exception e) {
            plugin.getLogger().warning("商品数据反序列化失败，已跳过 id=" + id);
            return null;
        }
        if (item == null || item.getType().isAir()) {
            return null;
        }
        // 旧版本允许超过两位小数的价格，读取时归一到分，保证显示金额与实际扣款一致
        BigDecimal price = BigDecimal.valueOf(rs.getDouble("price"));
        if (price.stripTrailingZeros().scale() > PriceUtil.SCALE) {
            price = price.setScale(PriceUtil.SCALE, RoundingMode.HALF_UP);
        }
        long listedAt = rs.getLong("listed_at");
        return new Listing(id, uuid, name, item, price, listedAt);
    }

    // ---------------- claims ----------------

    @Override
    public int addClaim(UUID owner, ItemStack item, String reason) {
        try {
            return insertClaim(connection, owner, item, reason);
        } catch (SQLException e) {
            plugin.getLogger().severe("写入收货箱失败: " + e.getMessage());
            return -1;
        }
    }

    private int insertClaim(Connection c, UUID owner, ItemStack item, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO claims(owner_uuid, item_data, reason, created_at) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setBytes(2, item.serializeAsBytes());
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            if (ps.executeUpdate() != 1) {
                return -1;
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    @Override
    public Claim getClaim(int id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM claims WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapClaim(rs);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取收货箱记录失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Claim> getClaims(UUID owner, int offset, int limit) {
        List<Claim> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM claims WHERE owner_uuid=? ORDER BY created_at ASC LIMIT ? OFFSET ?")) {
            ps.setString(1, owner.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Claim claim = mapClaim(rs);
                    if (claim != null) {
                        out.add(claim);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取收货箱失败: " + e.getMessage());
        }
        return out;
    }

    @Override
    public int countClaims(UUID owner) {
        return count("SELECT COUNT(*) FROM claims WHERE owner_uuid=?", owner);
    }

    @Override
    public boolean deleteClaim(int id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().severe("删除收货箱记录失败: " + e.getMessage());
            return false;
        }
    }

    private Claim mapClaim(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        UUID uuid;
        try {
            uuid = UUID.fromString(rs.getString("owner_uuid"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        ItemStack item;
        try {
            item = ItemStack.deserializeBytes(rs.getBytes("item_data"));
        } catch (Exception e) {
            plugin.getLogger().warning("收货箱物品反序列化失败，已跳过 id=" + id);
            return null;
        }
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String reason = rs.getString("reason");
        long createdAt = rs.getLong("created_at");
        return new Claim(id, uuid, item, reason, createdAt);
    }

    // ---------------- 事务辅助 ----------------

    private void quietlyRollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            plugin.getLogger().severe("恢复自动提交失败: " + e.getMessage());
        }
    }
}
