package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.model.PendingOp;
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
import java.util.logging.Logger;

/**
 * SQLite 数据层：上架记录、收货箱与交易流水。
 *
 * <p>主线程同步读写。写操作一律返回是否真正落库，读操作的异常记日志后返回空结果
 * （界面层能继续打开，不会因为一次查询失败把玩家卡住）。
 *
 * <p>构造参数里注入 {@link Logger} 与 {@link ItemCodec} 而不是直接依赖插件实例，
 * 这样可以在测试里用临时数据库 + 假编解码器验证真实 SQL 与事务行为。
 */
public class MarketStorage implements ListingStore, RecoveryStore {

    private final Logger logger;
    private final File dbFile;
    private final ItemCodec codec;
    private Connection connection;

    public MarketStorage(Logger logger, File dbFile, ItemCodec codec) {
        this.logger = logger;
        this.dbFile = dbFile;
        this.codec = codec;
    }

    /** 生产环境使用：Bukkit 物品序列化。 */
    public MarketStorage(EnGlobalMarket plugin, File dbFile) {
        this(plugin.getLogger(), dbFile, new BukkitItemCodec());
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
            // 交易流水：崩溃后据此判断该补发、该退款还是该人工核对
            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_ops (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT,
                    amount TEXT,
                    proceeds TEXT,
                    target_uuid TEXT,
                    target_name TEXT,
                    item_data BLOB,
                    listing_id INTEGER NOT NULL DEFAULT 0,
                    note TEXT,
                    created_at INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_listings_time ON listings(listed_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner_uuid)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_pending_stage ON pending_ops(stage)");
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

    /** 供集成测试读取连接状态（事务后 auto-commit 是否恢复）。 */
    Connection connection() {
        return connection;
    }

    // ---------------- listings ----------------

    @Override
    public int createListing(UUID sellerUuid, String sellerName, ItemStack item, BigDecimal price) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO listings(seller_uuid, seller_name, item_data, price, listed_at) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, sellerName);
            ps.setBytes(3, encode(item));
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
            logger.severe("创建上架记录失败: " + e.getMessage());
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
            ps.setBytes(4, encode(listing.item()));
            ps.setDouble(5, listing.price().doubleValue());
            ps.setLong(6, listing.listedAt());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logger.severe("恢复上架记录失败 (id=" + listing.id() + "): " + e.getMessage());
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
            logger.severe("读取上架记录失败: " + e.getMessage());
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
            logger.severe("修改价格失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteListing(int id) {
        try {
            return deleteListing(connection, id) == 1;
        } catch (SQLException e) {
            logger.severe("删除上架记录失败: " + e.getMessage());
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
            logger.severe("转移商品到收货箱失败 (id=" + listing.id() + "): " + e.getMessage());
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
            logger.severe(errorMessage + ": " + e.getMessage());
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
            logger.severe("统计数据失败: " + e.getMessage());
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
            item = codec.decode(rs.getBytes("item_data"));
        } catch (Exception e) {
            logger.warning("商品数据反序列化失败，已跳过 id=" + id);
            return null;
        }
        if (!isUsable(item)) {
            return null;
        }
        long listedAt = rs.getLong("listed_at");
        return new Listing(id, uuid, name, item, readPrice(rs), listedAt);
    }

    /** 价格以 REAL 存储并按两位小数归一，保证显示金额与实际扣款一致。 */
    private BigDecimal readPrice(ResultSet rs) throws SQLException {
        BigDecimal price = BigDecimal.valueOf(rs.getDouble("price"));
        if (price.stripTrailingZeros().scale() > PriceUtil.SCALE) {
            price = price.setScale(PriceUtil.SCALE, RoundingMode.HALF_UP);
        }
        return price;
    }

    /** 物品是否可用（空载荷、空气、反序列化失败都视为不可用）。 */
    private boolean isUsable(ItemStack item) {
        if (item == null || item.getType() == null) {
            return false;
        }
        return !item.getType().isAir();
    }

    // ---------------- claims ----------------

    @Override
    public int addClaim(UUID owner, ItemStack item, String reason) {
        try {
            return insertClaim(connection, owner, item, reason);
        } catch (SQLException e) {
            logger.severe("写入收货箱失败: " + e.getMessage());
            return -1;
        }
    }

    private int insertClaim(Connection c, UUID owner, ItemStack item, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO claims(owner_uuid, item_data, reason, created_at) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setBytes(2, encode(item));
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
            logger.severe("读取收货箱记录失败: " + e.getMessage());
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
            logger.severe("读取收货箱失败: " + e.getMessage());
        }
        return out;
    }

    @Override
    public int countClaims(UUID owner) {
        return count("SELECT COUNT(*) FROM claims WHERE owner_uuid=?", owner);
    }

    @Override
    public boolean deleteClaim(int id) {
        try {
            return deleteClaim(connection, id) == 1;
        } catch (SQLException e) {
            logger.severe("删除收货箱记录失败: " + e.getMessage());
            return false;
        }
    }

    private int deleteClaim(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate();
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
            item = codec.decode(rs.getBytes("item_data"));
        } catch (Exception e) {
            logger.warning("收货箱物品反序列化失败，已跳过 id=" + id);
            return null;
        }
        if (!isUsable(item)) {
            return null;
        }
        String reason = rs.getString("reason");
        long createdAt = rs.getLong("created_at");
        return new Claim(id, uuid, item, reason, createdAt);
    }

    // ---------------- 交易流水 ----------------

    @Override
    public int addPendingOp(PendingOp op) {
        try {
            return insertPendingOp(connection, op);
        } catch (SQLException e) {
            logger.severe("写入交易流水失败: " + e.getMessage());
            return -1;
        }
    }

    private int insertPendingOp(Connection c, PendingOp op) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO pending_ops(kind, stage, player_uuid, player_name, amount, proceeds,
                                        target_uuid, target_name, item_data, listing_id, note, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, op.kind().name());
            ps.setString(2, op.stage().name());
            ps.setString(3, op.playerUuid().toString());
            ps.setString(4, op.playerName());
            ps.setString(5, op.amount() == null ? null : op.amount().toPlainString());
            ps.setString(6, op.proceeds() == null ? null : op.proceeds().toPlainString());
            ps.setString(7, op.targetUuid() == null ? null : op.targetUuid().toString());
            ps.setString(8, op.targetName());
            ps.setBytes(9, encode(op.item()));
            ps.setInt(10, op.listingId());
            ps.setString(11, op.note());
            ps.setLong(12, op.createdAt());
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
    public boolean updatePendingOp(int id, PendingOp.Stage stage, String note) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE pending_ops SET stage=?, note=? WHERE id=?")) {
            ps.setString(1, stage.name());
            ps.setString(2, note);
            ps.setInt(3, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logger.severe("更新交易流水失败 (id=" + id + "): " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deletePendingOp(int id) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM pending_ops WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            logger.severe("删除交易流水失败 (id=" + id + "): " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<PendingOp> getPendingOps(int limit) {
        List<PendingOp> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM pending_ops ORDER BY id ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PendingOp op = mapPendingOp(rs);
                    if (op != null) {
                        out.add(op);
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("读取交易流水失败: " + e.getMessage());
        }
        return out;
    }

    @Override
    public int countPendingOps() {
        return count("SELECT COUNT(*) FROM pending_ops", null);
    }

    /**
     * 领取的事务化提交：同一事务内删掉收货箱记录并写入投递流水。
     * 要么两者都生效（玩家被欠一件物品），要么都不生效（记录仍可再次领取）。
     */
    @Override
    public int moveClaimToDelivery(int claimId, UUID owner, ItemStack item, String reason) {
        boolean autoCommit = true;
        try {
            autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            if (deleteClaim(connection, claimId) != 1) {
                connection.rollback();
                return -1;
            }
            int opId = insertPendingOp(connection, new PendingOp(0, PendingOp.Kind.DELIVERY,
                    PendingOp.Stage.DELIVERY_PENDING, owner, null, null, null, null, null,
                    item, 0, reason, System.currentTimeMillis()));
            if (opId <= 0) {
                connection.rollback();
                return -1;
            }
            connection.commit();
            return opId;
        } catch (SQLException e) {
            quietlyRollback();
            logger.severe("领取提交失败 (claim id=" + claimId + "): " + e.getMessage());
            return -1;
        } finally {
            restoreAutoCommit(autoCommit);
        }
    }

    private PendingOp mapPendingOp(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        PendingOp.Kind kind;
        PendingOp.Stage stage;
        try {
            kind = PendingOp.Kind.valueOf(rs.getString("kind"));
            stage = PendingOp.Stage.valueOf(rs.getString("stage"));
        } catch (IllegalArgumentException | NullPointerException e) {
            logger.warning("交易流水类型或阶段无法识别，已跳过 id=" + id);
            return null;
        }
        UUID player;
        try {
            player = UUID.fromString(rs.getString("player_uuid"));
        } catch (IllegalArgumentException e) {
            logger.warning("交易流水玩家 UUID 非法，已跳过 id=" + id);
            return null;
        }
        String targetRaw = rs.getString("target_uuid");
        UUID target = null;
        if (targetRaw != null) {
            try {
                target = UUID.fromString(targetRaw);
            } catch (IllegalArgumentException ignored) {
                // 关联方损坏不影响主流程，恢复时会按「缺少卖家」处理
            }
        }
        ItemStack item;
        try {
            item = codec.decode(rs.getBytes("item_data"));
        } catch (Exception e) {
            logger.warning("交易流水物品反序列化失败，已跳过 id=" + id);
            return null;
        }
        return new PendingOp(id, kind, stage, player, rs.getString("player_name"),
                readDecimal(rs.getString("amount")), readDecimal(rs.getString("proceeds")),
                target, rs.getString("target_name"), item, rs.getInt("listing_id"),
                rs.getString("note"), rs.getLong("created_at"));
    }

    private BigDecimal readDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            logger.warning("交易流水金额无法解析: " + raw);
            return null;
        }
    }

    // ---------------- 事务辅助 ----------------

    /** 物品编码失败按 SQL 异常处理，保证事务能整体回滚而不是抛运行时异常卡住半途。 */
    private byte[] encode(ItemStack item) throws SQLException {
        try {
            return codec.encode(item);
        } catch (RuntimeException e) {
            throw new SQLException("物品序列化失败", e);
        }
    }

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
            logger.severe("恢复自动提交失败: " + e.getMessage());
        }
    }
}
