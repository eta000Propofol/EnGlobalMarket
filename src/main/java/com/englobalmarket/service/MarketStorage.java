package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import org.bukkit.inventory.ItemStack;

import java.io.File;
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
 * 主线程同步读写，异常兜底并记日志。
 */
public class MarketStorage {

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

    public int createListing(UUID sellerUuid, String sellerName, ItemStack item, double price) {
        String sql = "INSERT INTO listings(seller_uuid, seller_name, item_data, price, listed_at) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, sellerName);
            ps.setBytes(3, item.serializeAsBytes());
            ps.setDouble(4, price);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
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

    public Listing getListing(int id) {
        String sql = "SELECT * FROM listings WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public List<Listing> getListings(int offset, int limit) {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM listings ORDER BY listed_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapListing(rs);
                    if (listing != null) {
                        out.add(listing);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取上架列表失败: " + e.getMessage());
        }
        return out;
    }

    public List<Listing> getListingsBySeller(UUID seller, int offset, int limit) {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM listings WHERE seller_uuid=? ORDER BY listed_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapListing(rs);
                    if (listing != null) {
                        out.add(listing);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取个人上架列表失败: " + e.getMessage());
        }
        return out;
    }

    public List<Listing> getExpiredListings(long cutoffMillis) {
        List<Listing> out = new ArrayList<>();
        String sql = "SELECT * FROM listings WHERE listed_at <= ? ORDER BY listed_at ASC";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, cutoffMillis);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing listing = mapListing(rs);
                    if (listing != null) {
                        out.add(listing);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("读取过期商品失败: " + e.getMessage());
        }
        return out;
    }

    public int countListings() {
        return count("SELECT COUNT(*) FROM listings", null);
    }

    public int countBySeller(UUID seller) {
        return count("SELECT COUNT(*) FROM listings WHERE seller_uuid=?", seller);
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

    public boolean updatePrice(int id, double price) {
        String sql = "UPDATE listings SET price=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, price);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("修改价格失败: " + e.getMessage());
        }
        return false;
    }

    public boolean deleteListing(int id) {
        String sql = "DELETE FROM listings WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("删除上架记录失败: " + e.getMessage());
        }
        return false;
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
        double price = rs.getDouble("price");
        long listedAt = rs.getLong("listed_at");
        return new Listing(id, uuid, name, item, price, listedAt);
    }

    // ---------------- claims ----------------

    public int addClaim(UUID owner, ItemStack item, String reason) {
        String sql = "INSERT INTO claims(owner_uuid, item_data, reason, created_at) VALUES(?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setBytes(2, item.serializeAsBytes());
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("写入收货箱失败: " + e.getMessage());
        }
        return -1;
    }

    public Claim getClaim(int id) {
        String sql = "SELECT * FROM claims WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public List<Claim> getClaims(UUID owner, int offset, int limit) {
        List<Claim> out = new ArrayList<>();
        String sql = "SELECT * FROM claims WHERE owner_uuid=? ORDER BY created_at ASC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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

    public int countClaims(UUID owner) {
        return count("SELECT COUNT(*) FROM claims WHERE owner_uuid=?", owner);
    }

    public boolean deleteClaim(int id) {
        String sql = "DELETE FROM claims WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("删除收货箱记录失败: " + e.getMessage());
        }
        return false;
    }

    public boolean updateClaimItem(int id, ItemStack item) {
        String sql = "UPDATE claims SET item_data=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setBytes(1, item.serializeAsBytes());
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().severe("更新收货箱物品失败: " + e.getMessage());
        }
        return false;
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
}

