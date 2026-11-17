package com.englobalmarket.service;

import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.util.PriceUtil;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 交易核心逻辑：不含任何 Bukkit 玩家/界面代码，只依赖 {@link ListingStore} 与 {@link EconomyGateway}。
 *
 * <p>原子性约定：数据库与经济的写操作都可能失败，因此每一步都检查返回值，失败时按相反顺序补偿：
 * <ul>
 *   <li>购买：先删除记录（等于「认领」这件商品）→ 扣买家 → 给卖家入账；
 *       扣款失败就用原 id 恢复记录，入账失败就退款并恢复记录。</li>
 *   <li>下架：只有删除成功才把物品交还玩家，删除失败绝不发物品。</li>
 *   <li>领取：只有删除成功才把物品交给玩家，避免「发两次」。</li>
 * </ul>
 * 最坏情况下宁可少卖一次，也不重复发货或凭空吞掉物品；恢复失败时把物品兜底写入卖家收货箱并记严重日志。
 */
public final class TradingCore {

    /** 交易结果状态。 */
    public enum Status {
        SUCCESS,
        /** 记录已不存在（被买走 / 下架 / 过期）。 */
        GONE,
        /** 不能购买自己的商品。 */
        OWN,
        /** 不是商品所有者。 */
        NOT_OWNER,
        /** 余额不足。 */
        NOT_ENOUGH_MONEY,
        /** 界面展示的价格与当前价格不一致。 */
        PRICE_CHANGED,
        /** 经济插件不可用。 */
        UNAVAILABLE,
        /** 数据库或经济写入失败（已尽量回滚）。 */
        FAILED
    }

    public record PurchaseResult(Status status, BigDecimal price, BigDecimal proceeds,
                                 ItemStack item, UUID sellerUuid, String sellerName) {

        static PurchaseResult of(Status status) {
            return new PurchaseResult(status, null, null, null, null, null);
        }
    }

    public record DelistResult(Status status, ItemStack item) {
        static DelistResult of(Status status) {
            return new DelistResult(status, null);
        }
    }

    /** 领取结果：成功时带回物品与原记录的原因（部分领取需要沿用同一个原因重新入库）。 */
    public record ClaimResult(Status status, ItemStack item, String reason) {
        static ClaimResult of(Status status) {
            return new ClaimResult(status, null, null);
        }
    }

    private final ListingStore store;
    private final EconomyGateway economy;
    private final Consumer<String> audit;

    public TradingCore(ListingStore store, EconomyGateway economy, Consumer<String> audit) {
        this.store = store;
        this.economy = economy;
        this.audit = audit == null ? message -> {
        } : audit;
    }

    // ---------------- 购买 ----------------

    /**
     * 购买整组商品。
     *
     * @param expectedPrice 买家在确认界面上看到的价格；服务端价格变了就返回 {@link Status#PRICE_CHANGED}，
     *                      不会按新价格悄悄扣款
     */
    public PurchaseResult purchase(UUID buyer, int listingId, BigDecimal expectedPrice, BigDecimal taxRate) {
        if (economy == null || !economy.isAvailable()) {
            return PurchaseResult.of(Status.UNAVAILABLE);
        }
        Listing listing = store.getListing(listingId);
        if (listing == null) {
            return PurchaseResult.of(Status.GONE);
        }
        if (listing.sellerUuid().equals(buyer)) {
            return PurchaseResult.of(Status.OWN);
        }
        BigDecimal price = listing.price();
        if (expectedPrice != null && price.compareTo(expectedPrice) != 0) {
            return PurchaseResult.of(Status.PRICE_CHANGED);
        }
        if (!economy.has(buyer, price)) {
            return PurchaseResult.of(Status.NOT_ENOUGH_MONEY);
        }

        // 1. 删除记录 = 认领商品。删除失败说明记录已被别人处理或数据库异常，
        //    此时不扣款，直接放弃本次交易。
        if (!store.deleteListing(listingId)) {
            if (store.getListing(listingId) != null) {
                audit.accept("删除商品记录失败，购买已中止（listing id=" + listingId + "）");
                return PurchaseResult.of(Status.FAILED);
            }
            return PurchaseResult.of(Status.GONE);
        }

        // 2. 扣买家。失败则恢复记录，钱与货都不动。
        if (!economy.withdraw(buyer, price)) {
            boolean restored = restore(listing, "买家扣款失败");
            audit.accept("买家 " + buyer + " 扣款失败（listing id=" + listingId + "），商品已恢复=" + restored);
            return PurchaseResult.of(economy.has(buyer, price)
                    ? Status.FAILED
                    : Status.NOT_ENOUGH_MONEY);
        }

        // 3. 给卖家入账。失败则退款并恢复记录。
        BigDecimal proceeds = PriceUtil.proceeds(price, taxRate);
        if (!economy.deposit(listing.sellerUuid(), proceeds)) {
            boolean refunded = economy.deposit(buyer, price);
            boolean restored = restore(listing, "卖家入账失败");
            audit.accept("卖家 " + listing.sellerUuid() + " 入账失败（listing id=" + listingId
                    + "，金额 " + proceeds.toPlainString() + "）；已退款买家=" + refunded
                    + "，商品已恢复=" + restored);
            return PurchaseResult.of(Status.FAILED);
        }

        return new PurchaseResult(Status.SUCCESS, price, proceeds,
                listing.item(), listing.sellerUuid(), listing.sellerName());
    }

    // ---------------- 下架 ----------------

    public DelistResult delist(UUID owner, int listingId) {
        Listing listing = store.getListing(listingId);
        if (listing == null) {
            return DelistResult.of(Status.GONE);
        }
        if (!listing.sellerUuid().equals(owner)) {
            return DelistResult.of(Status.NOT_OWNER);
        }
        if (!store.deleteListing(listingId)) {
            if (store.getListing(listingId) != null) {
                audit.accept("删除商品记录失败，下架已中止（listing id=" + listingId + "）");
                return DelistResult.of(Status.FAILED);
            }
            return DelistResult.of(Status.GONE);
        }
        return new DelistResult(Status.SUCCESS, listing.item());
    }

    // ---------------- 改价 ----------------

    public Status changePrice(UUID owner, int listingId, BigDecimal price) {
        Listing listing = store.getListing(listingId);
        if (listing == null) {
            return Status.GONE;
        }
        if (!listing.sellerUuid().equals(owner)) {
            return Status.NOT_OWNER;
        }
        if (price == null || price.signum() <= 0 || price.compareTo(PriceUtil.MAX_PRICE) > 0) {
            return Status.FAILED;
        }
        return store.updatePrice(listingId, price) ? Status.SUCCESS : Status.FAILED;
    }

    // ---------------- 领取 ----------------

    /** 领取一条收货箱记录：先删记录再交出物品，保证不会重复发放。 */
    public ClaimResult claim(UUID owner, int claimId) {
        Claim claim = store.getClaim(claimId);
        if (claim == null || !claim.ownerUuid().equals(owner)) {
            return ClaimResult.of(Status.GONE);
        }
        if (!store.deleteClaim(claimId)) {
            if (store.getClaim(claimId) != null) {
                audit.accept("删除收货箱记录失败，领取已中止（claim id=" + claimId + "）");
                return ClaimResult.of(Status.FAILED);
            }
            return ClaimResult.of(Status.GONE);
        }
        return new ClaimResult(Status.SUCCESS, claim.item(), claim.reason());
    }

    // ---------------- 内部 ----------------

    /**
     * 恢复一条被删除的上架记录。
     *
     * @return 记录是否成功恢复；false 时物品已转入卖家收货箱（或两者都失败，已记严重日志）
     */
    private boolean restore(Listing listing, String reason) {
        if (store.restoreListing(listing)) {
            return true;
        }
        int claimId = store.addClaim(listing.sellerUuid(), listing.item(), Claim.REASON_ROLLBACK);
        if (claimId <= 0) {
            audit.accept("严重：商品 id=" + listing.id() + " 回滚失败且无法写入收货箱，物品可能丢失（"
                    + reason + "，卖家 " + listing.sellerUuid() + "）");
        } else {
            audit.accept("商品 id=" + listing.id() + " 无法恢复，已转入卖家收货箱（claim id=" + claimId
                    + "，" + reason + "）");
        }
        return false;
    }
}
