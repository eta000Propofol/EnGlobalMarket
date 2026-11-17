package com.englobalmarket.service;

import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TradingCore 交易原子性与补偿回滚")
class TradingCoreTest {

    private static final BigDecimal TAX5 = new BigDecimal("0.05");

    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    private FakeStore store;
    private FakeEconomy economy;
    private List<String> audit;
    private List<String> events;
    private TradingCore core;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        economy = new FakeEconomy();
        audit = new ArrayList<>();
        events = new ArrayList<>();
        store.events = events;
        economy.events = events;
        core = new TradingCore(store, economy, audit::add);
        economy.setBalance(buyer, "200.00");
    }

    private int indexOf(String event) {
        return events.indexOf(event);
    }

    private void assertEveryEventExists(String... expected) {
        for (String event : expected) {
            assertTrue(events.contains(event), "缺少事件 " + event + "，实际为 " + events);
        }
    }

    // ---------------- 购买 ----------------

    @Nested
    @DisplayName("购买")
    class Purchase {

        @Test
        @DisplayName("成功：删记录 → 扣买家 → 给卖家税后入账")
        void successMovesMoneyAndRemovesListing() {
            Listing listing = store.putListing(seller, "100.00");

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), new BigDecimal("100.00"), TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.SUCCESS, result.status()),
                    () -> assertEquals(0, new BigDecimal("100.00").compareTo(result.price())),
                    () -> assertEquals(0, new BigDecimal("95.00").compareTo(result.proceeds())),
                    () -> assertEquals(seller, result.sellerUuid()),
                    () -> assertEquals("卖家1", result.sellerName()),
                    () -> assertFalse(store.listings.containsKey(listing.id()), "商品记录应已删除"),
                    () -> assertEquals(0, new BigDecimal("100.00").compareTo(economy.balanceOf(buyer)), "买家余额"),
                    () -> assertEquals(0, new BigDecimal("95.00").compareTo(economy.balanceOf(seller)), "卖家余额"),
                    // 税额（5.00）不进入任何账户
                    () -> assertEquals(0, new BigDecimal("195.00")
                            .compareTo(economy.balanceOf(buyer).add(economy.balanceOf(seller)))));
        }

        @Test
        @DisplayName("先删记录再动钱：扣款发生在认领商品之后")
        void deletesBeforeCharging() {
            Listing listing = store.putListing(seller, "100.00");

            core.purchase(buyer, listing.id(), null, TAX5);

            int delete = indexOf("deleteListing:" + listing.id());
            int withdraw = indexOf("withdraw:" + buyer);
            int deposit = indexOf("deposit:" + seller);
            assertAll(
                    () -> assertTrue(delete >= 0, "未删除记录"),
                    () -> assertTrue(withdraw > delete, "扣款应在删除记录之后"),
                    () -> assertTrue(deposit > withdraw, "入账应在扣款之后"));
        }

        @Test
        @DisplayName("界面价与当前价不一致：返回 PRICE_CHANGED，不扣款不删记录")
        void priceChangedAbortsWithoutCharging() {
            Listing listing = store.putListing(seller, "200.00");
            economy.setBalance(buyer, "500.00");

            TradingCore.PurchaseResult result =
                    core.purchase(buyer, listing.id(), new BigDecimal("100.00"), TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.PRICE_CHANGED, result.status()),
                    () -> assertTrue(store.listings.containsKey(listing.id())),
                    () -> assertEquals(0, new BigDecimal("500.00").compareTo(economy.balanceOf(buyer))),
                    () -> assertTrue(events.isEmpty(), "不应发生任何写入，实际: " + events));
        }

        @Test
        @DisplayName("余额不足：不删记录不扣款")
        void notEnoughMoneyTouchesNothing() {
            Listing listing = store.putListing(seller, "300.00");

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.NOT_ENOUGH_MONEY, result.status()),
                    () -> assertTrue(store.listings.containsKey(listing.id())),
                    () -> assertEquals(0, new BigDecimal("200.00").compareTo(economy.balanceOf(buyer))),
                    () -> assertFalse(events.contains("deleteListing:" + listing.id())));
        }

        @Test
        @DisplayName("不能购买自己的商品")
        void cannotBuyOwn() {
            Listing listing = store.putListing(buyer, "100.00");

            assertEquals(TradingCore.Status.OWN, core.purchase(buyer, listing.id(), null, TAX5).status());
            assertTrue(store.listings.containsKey(listing.id()));
        }

        @Test
        @DisplayName("记录不存在")
        void gone() {
            assertEquals(TradingCore.Status.GONE, core.purchase(buyer, 999, null, TAX5).status());
        }

        @Test
        @DisplayName("经济不可用")
        void unavailable() {
            economy.available = false;
            Listing listing = store.putListing(seller, "100.00");

            assertEquals(TradingCore.Status.UNAVAILABLE, core.purchase(buyer, listing.id(), null, TAX5).status());
            assertTrue(store.listings.containsKey(listing.id()));
        }

        @Test
        @DisplayName("删除失败且记录仍在：判为故障，不扣款")
        void deleteFailureAbortsWithoutCharging() {
            Listing listing = store.putListing(seller, "100.00");
            store.failDeleteListing = true;

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertEquals(0, new BigDecimal("200.00").compareTo(economy.balanceOf(buyer))),
                    () -> assertFalse(events.contains("withdraw:" + buyer)),
                    () -> assertFalse(audit.isEmpty(), "应记录告警"));
        }

        @Test
        @DisplayName("扣款失败：恢复记录，钱货两清")
        void withdrawFailureRestoresListing() {
            Listing listing = store.putListing(seller, "100.00");
            economy.denyWithdraw(buyer);

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertTrue(store.listings.containsKey(listing.id()), "商品应被恢复"),
                    () -> assertEquals(listing, store.listings.get(listing.id()), "恢复的应是原记录"),
                    () -> assertEquals(0, new BigDecimal("200.00").compareTo(economy.balanceOf(buyer))),
                    () -> assertEquals(0, BigDecimal.ZERO.compareTo(economy.balanceOf(seller))),
                    () -> assertEveryEventExists("deleteListing:" + listing.id(), "restoreListing:" + listing.id()));
        }

        @Test
        @DisplayName("扣款失败且余额确实变少：报余额不足而不是故障")
        void withdrawFailureWithLowBalanceReportsNoMoney() {
            Listing listing = store.putListing(seller, "100.00");
            // 模拟竞态：检查时 has() 说有钱，真正扣款时余额已被别处花掉
            economy.setBalance(buyer, "50.00");
            economy.hasScript.add(true);
            economy.denyWithdraw(buyer);

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertEquals(TradingCore.Status.NOT_ENOUGH_MONEY, result.status());
            assertTrue(store.listings.containsKey(listing.id()));
        }

        @Test
        @DisplayName("卖家入账失败：退款买家并恢复商品")
        void depositFailureRefundsBuyer() {
            Listing listing = store.putListing(seller, "100.00");
            economy.denyDeposit(seller);

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertTrue(store.listings.containsKey(listing.id()), "商品应被恢复"),
                    () -> assertEquals(0, new BigDecimal("200.00").compareTo(economy.balanceOf(buyer)), "买家应已退款"),
                    () -> assertEquals(0, BigDecimal.ZERO.compareTo(economy.balanceOf(seller))),
                    () -> assertEveryEventExists("restoreListing:" + listing.id(),
                            "deposit:" + buyer, "deposit:" + seller));
        }

        @Test
        @DisplayName("退款也失败时：记录仍会恢复，并记录严重告警")
        void refundFailureStillRestoresListing() {
            Listing listing = store.putListing(seller, "100.00");
            economy.denyAllDeposits = true;

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertTrue(store.listings.containsKey(listing.id())),
                    () -> assertEquals(1, audit.size(), "应有一条告警: " + audit),
                    () -> assertTrue(audit.get(0).contains("入账失败")));
        }

        @Test
        @DisplayName("恢复失败时：物品兜底转入卖家收货箱，绝不丢失")
        void restoreFailureFallsBackToClaim() {
            Listing listing = store.putListing(seller, "100.00");
            economy.denyDeposit(seller);
            store.failRestoreListing = true;

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertFalse(store.listings.containsKey(listing.id())),
                    () -> assertEquals(1, store.claims.size(), "应写入一条兜底收货箱记录"),
                    () -> {
                        Claim claim = store.claims.values().iterator().next();
                        assertEquals(seller, claim.ownerUuid());
                        assertEquals(Claim.REASON_ROLLBACK, claim.reason());
                    },
                    () -> assertEquals(0, new BigDecimal("200.00").compareTo(economy.balanceOf(buyer)),
                            "买家仍应拿到退款"),
                    () -> assertTrue(audit.stream().anyMatch(m -> m.contains("收货箱"))));
        }

        @Test
        @DisplayName("恢复与兜底都失败时：记录严重日志")
        void restoreAndFallbackBothFailLogsSevere() {
            Listing listing = store.putListing(seller, "100.00");
            economy.denyDeposit(seller);
            store.failRestoreListing = true;
            store.failAddClaim = true;

            TradingCore.PurchaseResult result = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertTrue(audit.stream().anyMatch(m -> m.startsWith("严重")), "应有严重告警: " + audit));
        }

        @Test
        @DisplayName("同一件商品只能成交一次")
        void secondPurchaseSeesGone() {
            Listing listing = store.putListing(seller, "100.00");

            TradingCore.PurchaseResult first = core.purchase(buyer, listing.id(), null, TAX5);
            TradingCore.PurchaseResult second = core.purchase(buyer, listing.id(), null, TAX5);

            assertAll(
                    () -> assertEquals(TradingCore.Status.SUCCESS, first.status()),
                    () -> assertEquals(TradingCore.Status.GONE, second.status()),
                    () -> assertEquals(0, new BigDecimal("100.00").compareTo(economy.balanceOf(buyer)),
                            "不能被扣两次款"),
                    () -> assertEquals(0, new BigDecimal("95.00").compareTo(economy.balanceOf(seller)),
                            "不能重复入账"));
        }
    }

    // ---------------- 下架 ----------------

    @Nested
    @DisplayName("下架")
    class Delist {

        @Test
        @DisplayName("成功：删除记录并交还物品")
        void success() {
            Listing listing = store.putListing(seller, "100.00");

            TradingCore.DelistResult result = core.delist(seller, listing.id());

            assertAll(
                    () -> assertEquals(TradingCore.Status.SUCCESS, result.status()),
                    () -> assertFalse(store.listings.containsKey(listing.id())),
                    () -> assertEquals(0, BigDecimal.ZERO.compareTo(economy.balanceOf(seller)), "下架不应有金钱变动"));
        }

        @Test
        @DisplayName("非本人不能下架")
        void notOwner() {
            Listing listing = store.putListing(seller, "100.00");

            assertEquals(TradingCore.Status.NOT_OWNER, core.delist(buyer, listing.id()).status());
            assertTrue(store.listings.containsKey(listing.id()));
        }

        @Test
        @DisplayName("记录不存在")
        void gone() {
            assertEquals(TradingCore.Status.GONE, core.delist(seller, 999).status());
        }

        @Test
        @DisplayName("删除失败：不交还物品，避免一件商品变两份")
        void deleteFailureDoesNotHandOutItem() {
            Listing listing = store.putListing(seller, "100.00");
            store.failDeleteListing = true;

            TradingCore.DelistResult result = core.delist(seller, listing.id());

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertNull(result.item(), "失败时不应返回物品"),
                    () -> assertTrue(store.listings.containsKey(listing.id())),
                    () -> assertFalse(audit.isEmpty()));
        }
    }

    // ---------------- 改价 ----------------

    @Nested
    @DisplayName("改价")
    class ChangePrice {

        @Test
        @DisplayName("成功修改")
        void success() {
            Listing listing = store.putListing(seller, "100.00");

            assertEquals(TradingCore.Status.SUCCESS,
                    core.changePrice(seller, listing.id(), new BigDecimal("123.45")));
            assertEquals(0, new BigDecimal("123.45").compareTo(store.listings.get(listing.id()).price()));
        }

        @Test
        @DisplayName("记录不存在 / 非本人")
        void goneAndNotOwner() {
            Listing listing = store.putListing(seller, "100.00");
            assertAll(
                    () -> assertEquals(TradingCore.Status.GONE,
                            core.changePrice(seller, 999, new BigDecimal("1.00"))),
                    () -> assertEquals(TradingCore.Status.NOT_OWNER,
                            core.changePrice(buyer, listing.id(), new BigDecimal("1.00"))));
        }

        @Test
        @DisplayName("非法价格被拒绝")
        void invalidPrices() {
            Listing listing = store.putListing(seller, "100.00");
            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED,
                            core.changePrice(seller, listing.id(), null)),
                    () -> assertEquals(TradingCore.Status.FAILED,
                            core.changePrice(seller, listing.id(), BigDecimal.ZERO)),
                    () -> assertEquals(TradingCore.Status.FAILED,
                            core.changePrice(seller, listing.id(), new BigDecimal("-1"))),
                    () -> assertEquals(TradingCore.Status.FAILED,
                            core.changePrice(seller, listing.id(), new BigDecimal("1000000000.01"))),
                    () -> assertEquals(0, new BigDecimal("100.00").compareTo(store.listings.get(listing.id()).price())));
        }

        @Test
        @DisplayName("写库失败：报故障，价格不变")
        void storeFailure() {
            Listing listing = store.putListing(seller, "100.00");
            store.failUpdatePrice = true;

            assertEquals(TradingCore.Status.FAILED,
                    core.changePrice(seller, listing.id(), new BigDecimal("50.00")));
            assertEquals(0, new BigDecimal("100.00").compareTo(store.listings.get(listing.id()).price()));
        }
    }

    // ---------------- 领取 ----------------

    @Nested
    @DisplayName("领取")
    class ClaimItems {

        @Test
        @DisplayName("成功：记录先删除，物品与原因一并返回")
        void success() {
            Claim claim = store.putClaim(buyer, Claim.REASON_EXPIRED);

            TradingCore.ClaimResult result = core.claim(buyer, claim.id());

            assertAll(
                    () -> assertEquals(TradingCore.Status.SUCCESS, result.status()),
                    () -> assertEquals(Claim.REASON_EXPIRED, result.reason(), "原因要保留，部分领取时按原原因回库"),
                    () -> assertFalse(store.claims.containsKey(claim.id())),
                    () -> assertNotNull(events.stream().filter(e -> e.startsWith("deleteClaim:")).findFirst().orElse(null)));
        }

        @Test
        @DisplayName("重复领取第二次拿不到东西")
        void secondClaimIsGone() {
            Claim claim = store.putClaim(buyer, Claim.REASON_OVERFLOW);

            assertEquals(TradingCore.Status.SUCCESS, core.claim(buyer, claim.id()).status());
            TradingCore.ClaimResult second = core.claim(buyer, claim.id());
            assertAll(
                    () -> assertEquals(TradingCore.Status.GONE, second.status()),
                    () -> assertNull(second.item()));
        }

        @Test
        @DisplayName("别人的收货箱不能领")
        void otherOwner() {
            Claim claim = store.putClaim(seller, Claim.REASON_EXPIRED);

            assertEquals(TradingCore.Status.GONE, core.claim(buyer, claim.id()).status());
            assertTrue(store.claims.containsKey(claim.id()));
        }

        @Test
        @DisplayName("删除失败：不发物品，记录仍在")
        void deleteFailureDoesNotDeliver() {
            Claim claim = store.putClaim(buyer, Claim.REASON_EXPIRED);
            store.failDeleteClaim = true;

            TradingCore.ClaimResult result = core.claim(buyer, claim.id());

            assertAll(
                    () -> assertEquals(TradingCore.Status.FAILED, result.status()),
                    () -> assertNull(result.item(), "失败时不应交出物品"),
                    () -> assertTrue(store.claims.containsKey(claim.id())),
                    () -> assertFalse(audit.isEmpty()));
        }
    }
}
