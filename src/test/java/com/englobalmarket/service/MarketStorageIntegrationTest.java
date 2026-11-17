package com.englobalmarket.service;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 SQLite 集成测试：临时数据库 + 真实事务，验证「删除 + 插入」不会停在半完成状态。
 *
 * <p>物品载荷用一个不做真实序列化的编解码器（Bukkit 的 ItemStack 需要服务端注册表才能实例化），
 * 因此断言直接查库，而不是走会解码物品的读取方法。
 */
@DisplayName("MarketStorage 真实 SQLite 事务")
class MarketStorageIntegrationTest {

    @TempDir
    Path tempDir;

    private StubCodec codec;
    private MarketStorage storage;

    @BeforeEach
    void setUp() throws SQLException {
        codec = new StubCodec();
        storage = new MarketStorage(Logger.getLogger("MarketStorageIT"), tempDir.resolve("market.db").toFile(), codec);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private int rows(String table) {
        try (Statement st = storage.connection().createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new AssertionError("查询 " + table + " 失败", e);
        }
    }

    private boolean autoCommit() {
        try {
            return storage.connection().getAutoCommit();
        } catch (SQLException e) {
            throw new AssertionError("读取 auto-commit 失败", e);
        }
    }

    @Nested
    @DisplayName("过期转移（listings → claims）")
    class MoveListingToClaim {

        @Test
        @DisplayName("成功：上架记录消失、收货箱多一条，auto-commit 恢复")
        void commitsBothSides() {
            UUID seller = UUID.randomUUID();
            int id = storage.createListing(seller, "卖家", null, new BigDecimal("12.34"));
            var listing = new com.englobalmarket.model.Listing(id, seller, "卖家", null,
                    new BigDecimal("12.34"), System.currentTimeMillis());

            assertTrue(storage.moveListingToClaim(listing, "expired"));
            assertAll(
                    () -> assertEquals(0, rows("listings"), "上架记录应已删除"),
                    () -> assertEquals(1, rows("claims"), "收货箱应已写入"),
                    () -> assertTrue(autoCommit(), "事务结束后必须恢复 auto-commit"));
        }

        @Test
        @DisplayName("上架记录不存在：不插入收货箱")
        void noClaimWhenListingMissing() {
            UUID seller = UUID.randomUUID();
            var missing = new com.englobalmarket.model.Listing(999, seller, "卖家", null,
                    new BigDecimal("1.00"), System.currentTimeMillis());

            assertAll(
                    () -> assertTrue(!storage.moveListingToClaim(missing, "expired")),
                    () -> assertEquals(0, rows("claims"), "事务应回滚，不能凭空多一条收货箱"),
                    () -> assertTrue(autoCommit()));
        }

        @Test
        @DisplayName("插入收货箱失败：删除上架记录必须回滚")
        void rollsBackDeleteWhenInsertFails() {
            UUID seller = UUID.randomUUID();
            int id = storage.createListing(seller, "卖家", null, new BigDecimal("5.00"));
            var listing = new com.englobalmarket.model.Listing(id, seller, "卖家", null,
                    new BigDecimal("5.00"), System.currentTimeMillis());
            codec.failEncode = true;

            assertAll(
                    () -> assertTrue(!storage.moveListingToClaim(listing, "expired")),
                    () -> assertEquals(1, rows("listings"), "商品必须还在，不能既没了商品又没收货箱"),
                    () -> assertEquals(0, rows("claims")),
                    () -> assertTrue(autoCommit(), "失败路径也要恢复 auto-commit"));
        }
    }

    @Nested
    @DisplayName("领取提交（claims → pending_ops）")
    class MoveClaimToDelivery {

        @Test
        @DisplayName("成功：收货箱记录消失、投递流水落盘")
        void commitsBothSides() {
            UUID owner = UUID.randomUUID();
            int claimId = storage.addClaim(owner, null, "expired");

            int opId = storage.moveClaimToDelivery(claimId, owner, null, "expired");

            List<com.englobalmarket.model.PendingOp> ops = storage.getPendingOps(10);
            assertAll(
                    () -> assertTrue(opId > 0),
                    () -> assertEquals(0, rows("claims"), "收货箱记录应已删除"),
                    () -> assertEquals(1, ops.size(), "应留下一条投递流水"),
                    () -> assertEquals(com.englobalmarket.model.PendingOp.Kind.DELIVERY, ops.get(0).kind()),
                    () -> assertEquals(com.englobalmarket.model.PendingOp.Stage.DELIVERY_PENDING, ops.get(0).stage()),
                    () -> assertEquals(owner, ops.get(0).playerUuid()),
                    () -> assertEquals("expired", ops.get(0).note(), "原因要带进流水，部分领取时按原原因回库"),
                    () -> assertTrue(autoCommit()));
        }

        @Test
        @DisplayName("记录不存在：不产生流水")
        void noOpWhenClaimMissing() {
            assertAll(
                    () -> assertTrue(storage.moveClaimToDelivery(999, UUID.randomUUID(), null, "x") < 0),
                    () -> assertEquals(0, rows("pending_ops")),
                    () -> assertTrue(autoCommit()));
        }

        @Test
        @DisplayName("流水写入失败：收货箱记录必须保留")
        void keepsClaimWhenInsertFails() {
            UUID owner = UUID.randomUUID();
            int claimId = storage.addClaim(owner, null, "overflow");
            codec.failEncode = true;

            assertAll(
                    () -> assertTrue(storage.moveClaimToDelivery(claimId, owner, null, "overflow") < 0),
                    () -> assertEquals(1, rows("claims"), "记录必须还在，否则物品凭空消失"),
                    () -> assertEquals(0, rows("pending_ops")),
                    () -> assertTrue(autoCommit()));
        }
    }

    @Nested
    @DisplayName("流水读写")
    class PendingOps {

        private com.englobalmarket.model.PendingOp op(BigDecimal amount, BigDecimal proceeds) {
            return new com.englobalmarket.model.PendingOp(0,
                    com.englobalmarket.model.PendingOp.Kind.PURCHASE,
                    com.englobalmarket.model.PendingOp.Stage.CLAIMED,
                    UUID.randomUUID(), "买家", amount, proceeds,
                    UUID.randomUUID(), "卖家", null, 42, "初始", 1_700_000_000_000L);
        }

        @Test
        @DisplayName("金额按十进制文本存储，0.29 不会变成 0.289999…")
        void amountRoundTripIsExact() {
            int id = storage.addPendingOp(op(new BigDecimal("0.29"), new BigDecimal("0.2755")));

            com.englobalmarket.model.PendingOp read = storage.getPendingOps(1).get(0);
            assertAll(
                    () -> assertTrue(id > 0),
                    () -> assertEquals("0.29", read.amount().toPlainString()),
                    () -> assertEquals("0.2755", read.proceeds().toPlainString()),
                    () -> assertEquals(0, new BigDecimal("0.29").compareTo(read.amount())),
                    () -> assertEquals(42, read.listingId()));
        }

        @Test
        @DisplayName("阶段推进可落盘，删除后计数归零")
        void stageTransitionsAndDelete() {
            int id = storage.addPendingOp(op(new BigDecimal("1.00"), new BigDecimal("0.95")));

            assertAll(
                    () -> assertTrue(storage.updatePendingOp(id,
                            com.englobalmarket.model.PendingOp.Stage.CHARGED, "已扣款")),
                    () -> assertEquals(com.englobalmarket.model.PendingOp.Stage.CHARGED,
                            storage.getPendingOps(1).get(0).stage()),
                    () -> assertEquals("已扣款", storage.getPendingOps(1).get(0).note()),
                    () -> assertEquals(1, storage.countPendingOps()),
                    () -> assertTrue(storage.deletePendingOp(id)),
                    () -> assertEquals(0, storage.countPendingOps()));
        }

        @Test
        @DisplayName("更新不存在的流水返回 false")
        void updateMissingReturnsFalse() {
            assertTrue(!storage.updatePendingOp(12345,
                    com.englobalmarket.model.PendingOp.Stage.PAID, null));
        }

        @Test
        @DisplayName("列表、统计与上架/收货箱计数互不干扰")
        void countsAreIndependent() {
            UUID seller = UUID.randomUUID();
            storage.createListing(seller, "卖家", null, new BigDecimal("3.00"));
            storage.addClaim(seller, null, "overflow");
            storage.addPendingOp(op(new BigDecimal("3.00"), new BigDecimal("2.85")));

            assertAll(
                    () -> assertEquals(1, storage.countListings()),
                    () -> assertEquals(1, storage.countClaims(seller)),
                    () -> assertEquals(1, storage.countPendingOps()),
                    () -> assertEquals(1, rows("listings")));
        }
    }

    /** 不做真实序列化的编解码器：Bukkit 的 ItemStack 需要服务端注册表才能实例化。 */
    private static final class StubCodec implements ItemCodec {

        boolean failEncode;

        @Override
        public byte[] encode(ItemStack item) {
            if (failEncode) {
                throw new IllegalStateException("模拟序列化失败");
            }
            return item == null ? new byte[]{0} : new byte[]{1};
        }

        @Override
        public ItemStack decode(byte[] data) {
            return null;
        }
    }
}
