package com.englobalmarket.service;

import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.model.PendingOp;
import com.englobalmarket.util.PriceUtil;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 交易核心逻辑：不含任何 Bukkit 玩家/界面代码，只依赖 {@link ListingStore}、
 * {@link EconomyGateway} 与 {@link RecoveryStore}。
 *
 * <p><b>为什么需要流水</b>：数据库、Vault 与 Bukkit 背包不共享事务，任何一步都可能在崩溃时停在中间。
 * 因此每次动钱或动物品之前，先把「打算做什么、做到哪一步」写进 {@code pending_ops} 表，
 * 插件启动时再按阶段补齐或回滚（见 {@link #recover(PendingOp)}）。
 *
 * <p><b>购买顺序</b>：写流水(CLAIMED) → 删记录(认领商品) → 流水 CHARGING → 扣买家 → CHARGED
 * → PAYING → 给卖家入账 → PAID → 交付物品后删除流水。
 *
 * <p><b>失败处理</b>：扣款失败 → 退款/恢复上架；卖家入账失败 → 退款并把商品放回市场；
 * <b>退款也失败时绝不重新上架</b>，而是把流水置为 {@link PendingOp.Stage#REFUND_PENDING} 冻结，
 * 由启动恢复重试退款，退款成功后商品转入卖家收货箱。宁可短期冻结，也不会出现
 * 「买家钱被扣了、商品却重新在市场上卖」的状态。
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

    /** 恢复一条流水的结果。 */
    public enum Recovery {
        /** 已处理完并删除流水。 */
        RESOLVED,
        /** 条件不满足（离线、经济不可用），下次再试。 */
        RETRY,
        /** 需要把物品交给玩家，由调用方处理。 */
        NEEDS_PLAYER,
        /** 钱到底动没动无法判定，必须人工核对。 */
        NEEDS_MANUAL
    }

    public record PurchaseResult(Status status, BigDecimal price, BigDecimal proceeds,
                                 ItemStack item, UUID sellerUuid, String sellerName, int pendingId) {

        static PurchaseResult of(Status status) {
            return new PurchaseResult(status, null, null, null, null, null, 0);
        }
    }

    public record DelistResult(Status status, ItemStack item) {
        static DelistResult of(Status status) {
            return new DelistResult(status, null);
        }
    }

    /**
     * 领取结果：成功时带回物品、原记录的原因与投递流水 id。
     * 调用方在物品真正进了背包之后必须调用 {@link #completeDelivery(int)} 结清流水。
     */
    public record ClaimResult(Status status, ItemStack item, String reason, int pendingId) {
        static ClaimResult of(Status status) {
            return new ClaimResult(status, null, null, 0);
        }
    }

    private final ListingStore store;
    private final EconomyGateway economy;
    private final RecoveryStore recovery;
    private final Consumer<String> audit;

    public TradingCore(ListingStore store, EconomyGateway economy, RecoveryStore recovery,
                       Consumer<String> audit) {
        this.store = store;
        this.economy = economy;
        this.recovery = recovery;
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
        BigDecimal proceeds = PriceUtil.proceeds(price, taxRate);

        // 0. 先落盘交易流水。写不进去就不交易 —— 没有流水意味着崩溃后无法对账。
        int opId = recovery.addPendingOp(new PendingOp(0, PendingOp.Kind.PURCHASE, PendingOp.Stage.CLAIMED,
                buyer, null, price, proceeds, listing.sellerUuid(), listing.sellerName(),
                listing.item(), listingId, null, System.currentTimeMillis()));
        if (opId <= 0) {
            audit.accept("无法写入交易流水，购买已中止（listing id=" + listingId + "）");
            return PurchaseResult.of(Status.FAILED);
        }

        // 1. 删除记录 = 认领商品。删除失败说明记录已被别人处理或数据库异常，此时不扣款。
        if (!store.deleteListing(listingId)) {
            recovery.deletePendingOp(opId);
            if (store.getListing(listingId) != null) {
                audit.accept("删除商品记录失败，购买已中止（listing id=" + listingId + "）");
                return PurchaseResult.of(Status.FAILED);
            }
            return PurchaseResult.of(Status.GONE);
        }

        // 2. 扣买家。阶段先落盘成 CHARGING：若此时崩溃，恢复流程会判定「不确定」并留给人工，
        //    而不是猜测钱有没有扣。
        if (!markStage(opId, PendingOp.Stage.CHARGING, "扣款中")) {
            restoreAndClear(listing, opId, "写入交易阶段失败");
            return PurchaseResult.of(Status.FAILED);
        }
        if (!economy.withdraw(buyer, price)) {
            boolean restored = restore(listing, "买家扣款失败");
            recovery.deletePendingOp(opId);
            audit.accept("买家 " + buyer + " 扣款失败（listing id=" + listingId + "），商品已恢复=" + restored);
            return PurchaseResult.of(economy.has(buyer, price) ? Status.FAILED : Status.NOT_ENOUGH_MONEY);
        }
        if (!markStage(opId, PendingOp.Stage.CHARGED, null)) {
            // 钱已经扣了但流水没写上：立刻尝试退款与回滚，失败则流水停留在 CHARGING 等人工
            audit.accept("严重：流水 #" + opId + " 阶段写入失败（已扣款 listing id=" + listingId + "）");
            refundAndRestore(listing, opId, buyer, price, "流水阶段写入失败");
            return PurchaseResult.of(Status.FAILED);
        }

        // 3. 给卖家入账。
        if (!markStage(opId, PendingOp.Stage.PAYING, "入账中")) {
            refundAndRestore(listing, opId, buyer, price, "写入交易阶段失败");
            return PurchaseResult.of(Status.FAILED);
        }
        if (!economy.deposit(listing.sellerUuid(), proceeds)) {
            refundAndRestore(listing, opId, buyer, price, "卖家入账失败");
            return PurchaseResult.of(Status.FAILED);
        }
        if (!markStage(opId, PendingOp.Stage.PAID, null)) {
            // 钱货都已结清，物品交付由调用方完成；流水留在 PAID，恢复时补发（可能重复，已记严重日志）
            audit.accept("严重：交易已完成但流水 #" + opId + " 无法更新为 PAID（listing id=" + listingId + "）");
        }

        return new PurchaseResult(Status.SUCCESS, price, proceeds,
                listing.item(), listing.sellerUuid(), listing.sellerName(), opId);
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

    /**
     * 领取一条收货箱记录。
     *
     * <p>删除记录与「欠玩家物品」的流水在同一个事务里完成，因此不存在
     * 「记录删了、物品还没进背包」就崩溃导致的永久丢失。调用方交付物品后
     * 必须调用 {@link #completeDelivery(int)} 结清流水。
     */
    public ClaimResult claim(UUID owner, int claimId) {
        Claim claim = store.getClaim(claimId);
        if (claim == null || !claim.ownerUuid().equals(owner)) {
            return ClaimResult.of(Status.GONE);
        }
        int opId = recovery.moveClaimToDelivery(claimId, owner, claim.item(), claim.reason());
        if (opId <= 0) {
            if (store.getClaim(claimId) != null) {
                audit.accept("领取事务失败，记录未变动（claim id=" + claimId + "）");
                return ClaimResult.of(Status.FAILED);
            }
            return ClaimResult.of(Status.GONE);
        }
        return new ClaimResult(Status.SUCCESS, claim.item(), claim.reason(), opId);
    }

    /** 物品已交付玩家，结清投递流水。失败时恢复流程可能重复投递，因此记严重日志。 */
    public boolean completeDelivery(int pendingId) {
        if (pendingId <= 0) {
            return true;
        }
        if (recovery.deletePendingOp(pendingId)) {
            return true;
        }
        audit.accept("严重：投递流水 #" + pendingId + " 无法结清，重启后可能重复投递该物品");
        return false;
    }

    // ---------------- 崩溃恢复 ----------------

    /**
     * 按流水阶段补齐或回滚。
     *
     * <p>原则：能确定钱没动的，放回商品；能确定钱动了但货没到手的，补发或退款；
     * 无法确定的（{@code CHARGING} / {@code PAYING}）一律不自动处理，留给管理员核对。
     */
    public Recovery recover(PendingOp op) {
        switch (op.stage()) {
            case CLAIMED -> {
                // 商品已被认领但还没动钱：放回市场（失败则进卖家收货箱）
                restoreIfAbsent(op, "启动恢复：交易未扣款");
                return recovery.deletePendingOp(op.id()) ? Recovery.RESOLVED : Recovery.RETRY;
            }
            case CHARGED -> {
                // 买家被扣款但卖家没入账：退款并把商品放回市场
                if (!economyAvailable()) {
                    return Recovery.RETRY;
                }
                if (!economy.deposit(op.playerUuid(), op.amount())) {
                    recovery.updatePendingOp(op.id(), PendingOp.Stage.REFUND_PENDING, "启动恢复：退款失败，稍后重试");
                    return Recovery.RETRY;
                }
                restoreIfAbsent(op, "启动恢复：退款后归还商品");
                return recovery.deletePendingOp(op.id()) ? Recovery.RESOLVED : Recovery.RETRY;
            }
            case REFUND_PENDING -> {
                // 上次退款失败：重试；成功后转到 REFUNDED，由下一轮把商品还回卖家
                if (!economyAvailable()) {
                    return Recovery.RETRY;
                }
                if (!economy.deposit(op.playerUuid(), op.amount())) {
                    return Recovery.RETRY;
                }
                recovery.updatePendingOp(op.id(), PendingOp.Stage.REFUNDED, "启动恢复：已退款，待归还商品");
                return Recovery.RETRY;
            }
            case REFUNDED -> {
                // 已退款，把商品写入卖家收货箱
                if (op.targetUuid() == null) {
                    audit.accept("严重：流水 #" + op.id() + " 缺少卖家，无法归还商品");
                    return Recovery.NEEDS_MANUAL;
                }
                if (store.addClaim(op.targetUuid(), op.item(), Claim.REASON_ROLLBACK) <= 0) {
                    return Recovery.RETRY;
                }
                return recovery.deletePendingOp(op.id()) ? Recovery.RESOLVED : Recovery.RETRY;
            }
            case PAID, DELIVERY_PENDING -> {
                // 物品交付需要 Bukkit 玩家对象，交给调用方处理（缺物品时由调用方记日志）
                return Recovery.NEEDS_PLAYER;
            }
            case CHARGING, PAYING -> {
                audit.accept("严重：流水 #" + op.id() + " 停在「" + op.stage() + "」，"
                        + "无法判断钱是否已被扣/已入账，请管理员核对：" + describe(op));
                return Recovery.NEEDS_MANUAL;
            }
        }
        return Recovery.NEEDS_MANUAL;
    }

    // ---------------- 内部 ----------------

    private boolean economyAvailable() {
        return economy != null && economy.isAvailable();
    }

    private boolean markStage(int opId, PendingOp.Stage stage, String note) {
        return recovery.updatePendingOp(opId, stage, note);
    }

    /** 把流水还原成一条上架记录（恢复时用原 id 与原始上架时间）。 */
    private Listing asListing(PendingOp op) {
        return new Listing(op.listingId(), op.targetUuid(), op.targetName(), op.item(),
                op.amount(), op.createdAt());
    }

    /**
     * 恢复上架，但先确认商品不在市场里。
     *
     * <p>用于启动恢复：如果上一轮「恢复上架」已经成功、只是流水没删掉，
     * 这里再恢复一次会因为主键冲突而转去写收货箱，等于凭空复制一件商品。
     */
    private void restoreIfAbsent(PendingOp op, String reason) {
        if (store.getListing(op.listingId()) != null) {
            return;
        }
        restore(asListing(op), reason);
    }

    /** 流水阶段都写不进去：直接按回滚处理，避免留下无记录的中间状态。 */
    private void restoreAndClear(Listing listing, int opId, String reason) {
        boolean restored = restore(listing, reason);
        recovery.deletePendingOp(opId);
        audit.accept("流水 #" + opId + " 写入失败（listing id=" + listing.id() + "），商品已恢复=" + restored);
    }

    /**
     * 扣款后失败：退款并回滚。退款失败时<b>不恢复上架</b>，改为冻结流水等待重试，
     * 保证「买家钱被扣、商品又在市场上」这种状态不会出现。
     */
    private void refundAndRestore(Listing listing, int opId, UUID buyer, BigDecimal price, String reason) {
        boolean refunded = economy != null && economy.deposit(buyer, price);
        if (!refunded) {
            // 钱已经离开买家、又没能退回去，商品绝不能重新上架，
            // 否则就是「买家钱被扣、商品还在卖」。冻结流水等启动恢复重试。
            recovery.updatePendingOp(opId, PendingOp.Stage.REFUND_PENDING, reason + "；退款失败待重试");
            audit.accept("严重：买家 " + buyer + " 退款失败（listing id=" + listing.id() + "，" + reason
                    + "），流水 #" + opId + " 已冻结为 REFUND_PENDING，商品不再上架");
            return;
        }
        restore(listing, reason);
        recovery.deletePendingOp(opId);
    }

    private String describe(PendingOp op) {
        return "kind=" + op.kind() + " 玩家=" + op.playerUuid() + " 金额="
                + (op.amount() == null ? "-" : op.amount().toPlainString())
                + " listing=" + op.listingId() + " 时间=" + op.createdAt();
    }

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
