package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.model.PendingOp;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 市场业务层（Bukkit 适配层）：负责玩家背包、消息与通知，
 * 真正的交易步骤、补偿回滚与崩溃恢复在 {@link TradingCore} 里。
 */
public class MarketService {

    public enum CreateResult { SUCCESS, NO_ECONOMY, NO_ITEM, TOO_MANY, LIMIT_STACK, INVALID_PRICE, FAILED }

    public enum BuyResult { SUCCESS, NO_ECONOMY, GONE, OWN, NOT_ENOUGH_MONEY, PRICE_CHANGED, FAILED }

    public enum DelistResult { SUCCESS, GONE, NOT_OWNER, FAILED }

    public enum ChangeResult { SUCCESS, GONE, NOT_OWNER, FAILED }

    /** 领取结果。 */
    public enum ClaimOutcome {
        /** 全部进入背包。 */
        CLAIMED,
        /** 背包放不下，剩余部分已掉落（兜底，收货箱写入失败时）。 */
        CLAIMED_DROPPED,
        /** 部分领取，剩余仍在收货箱。 */
        PARTIAL,
        /** 记录不存在或不属于该玩家。 */
        GONE,
        /** 数据库写入失败。 */
        FAILED
    }

    public record ClaimResult(ClaimOutcome outcome, int remaining) {
    }

    /** 一轮恢复的统计结果。 */
    public record RecoveryReport(int resolved, int delivered, int retry, int needsManual) {
    }

    private final EnGlobalMarket plugin;
    private final MarketStorage storage;
    private final TradingCore core;

    public MarketService(EnGlobalMarket plugin, MarketStorage storage, EconomyGateway economy) {
        this.plugin = plugin;
        this.storage = storage;
        this.core = new TradingCore(storage, economy, storage, message -> plugin.getLogger().warning(message));
    }

    /** 经济是否可用（Vault 缺失时 economy 为 null）。 */
    private boolean economyAvailable() {
        return plugin.getEconomyService() != null && plugin.getEconomyService().isAvailable();
    }

    // ---------------- 查询 ----------------

    public List<Listing> getListings(int offset, int limit) {
        return storage.getListings(offset, limit);
    }

    public int countListings() {
        return storage.countListings();
    }

    public List<Listing> getListingsBySeller(UUID seller, int offset, int limit) {
        return storage.getListingsBySeller(seller, offset, limit);
    }

    public int countBySeller(UUID seller) {
        return storage.countBySeller(seller);
    }

    public Listing getListing(int id) {
        return storage.getListing(id);
    }

    // ---------------- 上架 ----------------

    public CreateResult createListing(Player player, ItemStack item, BigDecimal price) {
        if (!economyAvailable()) {
            return CreateResult.NO_ECONOMY;
        }
        if (item == null || item.getType().isAir()) {
            return CreateResult.NO_ITEM;
        }
        int maxStack = item.getMaxStackSize();
        if (item.getAmount() > maxStack) {
            return CreateResult.LIMIT_STACK;
        }
        if (price == null || price.signum() <= 0 || price.compareTo(PriceUtil.MAX_PRICE) > 0) {
            return CreateResult.INVALID_PRICE;
        }
        if (storage.countBySeller(player.getUniqueId()) >= plugin.getMaxListings()) {
            return CreateResult.TOO_MANY;
        }
        int id = storage.createListing(player.getUniqueId(), player.getName(), item.clone(), price);
        return id > 0 ? CreateResult.SUCCESS : CreateResult.FAILED;
    }

    /** 物品可上架的最大堆叠数（原版规则 64/16/1）。 */
    public int maxStackSize(ItemStack item) {
        if (item == null) {
            return 0;
        }
        return item.getMaxStackSize();
    }

    // ---------------- 购买 ----------------

    /**
     * 购买整组商品。
     *
     * @param expectedPrice 确认界面展示给玩家的价格；价格变了会返回 {@link BuyResult#PRICE_CHANGED}
     */
    public BuyResult purchase(Player buyer, int listingId, BigDecimal expectedPrice) {
        TradingCore.PurchaseResult result =
                core.purchase(buyer.getUniqueId(), listingId, expectedPrice, plugin.getTaxRateDecimal());
        if (result.status() != TradingCore.Status.SUCCESS) {
            return switch (result.status()) {
                case UNAVAILABLE -> BuyResult.NO_ECONOMY;
                case GONE -> BuyResult.GONE;
                case OWN -> BuyResult.OWN;
                case NOT_ENOUGH_MONEY -> BuyResult.NOT_ENOUGH_MONEY;
                case PRICE_CHANGED -> BuyResult.PRICE_CHANGED;
                default -> BuyResult.FAILED;
            };
        }
        giveItem(buyer, result.item().clone());
        notifySeller(result);
        // 物品已交付，结清流水；失败时恢复流程会补发（可能重复，已记严重日志）
        core.completeDelivery(result.pendingId());
        return BuyResult.SUCCESS;
    }

    private void notifySeller(TradingCore.PurchaseResult result) {
        Player sellerOnline = Bukkit.getPlayer(result.sellerUuid());
        if (sellerOnline == null || !sellerOnline.isOnline()) {
            return;
        }
        sellerOnline.sendMessage(Text.color(plugin.getMessage("sold-notify")
                .replace("%item%", displayName(result.item()))
                .replace("%proceeds%", plugin.money(result.proceeds()))));
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        if (!leftover.isEmpty()) {
            // 购买时溢出是掉落在脚下，不走收货箱，因此用单独的文案
            player.sendMessage(Text.color(plugin.getMessage("inventory-full-drop")));
        }
    }

    // ---------------- 下架 / 改价 ----------------

    public DelistResult delist(Player player, int listingId) {
        TradingCore.DelistResult result = core.delist(player.getUniqueId(), listingId);
        return switch (result.status()) {
            case SUCCESS -> {
                returnItem(player, result.item().clone());
                yield DelistResult.SUCCESS;
            }
            case GONE -> DelistResult.GONE;
            case NOT_OWNER -> DelistResult.NOT_OWNER;
            default -> DelistResult.FAILED;
        };
    }

    public ChangeResult changePrice(Player player, int listingId, BigDecimal price) {
        return switch (core.changePrice(player.getUniqueId(), listingId, price)) {
            case SUCCESS -> ChangeResult.SUCCESS;
            case GONE -> ChangeResult.GONE;
            case NOT_OWNER -> ChangeResult.NOT_OWNER;
            default -> ChangeResult.FAILED;
        };
    }

    /** 把物品退回玩家背包；放不下的部分放入收货箱，收货箱写入失败则掉落，绝不吞物品。 */
    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            return;
        }
        boolean allStored = true;
        for (ItemStack rest : leftover.values()) {
            if (storage.addClaim(player.getUniqueId(), rest, Claim.REASON_OVERFLOW) <= 0) {
                allStored = false;
                drop(player, rest, "下架溢出写入收货箱失败");
            }
        }
        player.sendMessage(Text.color(plugin.getMessage(allStored ? "inventory-full" : "op-failed")));
    }

    // ---------------- 过期 ----------------

    /** 把超过有效期仍未售出的商品下架并转入卖家收货箱。 */
    public void expireAll() {
        long cutoff = System.currentTimeMillis() - (long) plugin.getExpireDays() * 86_400_000L;
        List<Listing> expired = storage.getExpiredListings(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        int moved = 0;
        for (Listing listing : expired) {
            // 删除与写入收货箱在同一个事务里，失败则两者都不生效，下一轮重试
            if (!storage.moveListingToClaim(listing, Claim.REASON_EXPIRED)) {
                plugin.getLogger().warning("处理过期商品失败，将在下一轮重试 (id=" + listing.id() + ")");
                continue;
            }
            moved++;
            Player seller = Bukkit.getPlayer(listing.sellerUuid());
            if (seller != null && seller.isOnline()) {
                seller.sendMessage(Text.color(plugin.getMessage("expired-notify")
                        .replace("%item%", displayName(listing.item()))
                        .replace("%days%", String.valueOf(plugin.getExpireDays()))));
            }
        }
        plugin.getLogger().info("已自动下架 " + moved + " 件过期商品。");
    }

    // ---------------- 收货箱 ----------------

    public List<Claim> getClaims(UUID owner, int offset, int limit) {
        return storage.getClaims(owner, offset, limit);
    }

    public int countClaims(UUID owner) {
        return storage.countClaims(owner);
    }

    /**
     * 领取收货箱中的一件物品。
     *
     * <p>「删除记录」与「记下欠玩家一件物品」在同一个事务里完成，所以哪怕领到一半崩溃，
     * 重启后也能从流水里补发，不会再出现记录没了、物品也没到手的情况。
     */
    public ClaimResult claimItem(Player player, int claimId) {
        TradingCore.ClaimResult result = core.claim(player.getUniqueId(), claimId);
        if (result.status() != TradingCore.Status.SUCCESS) {
            return new ClaimResult(result.status() == TradingCore.Status.GONE
                    ? ClaimOutcome.GONE : ClaimOutcome.FAILED, 0);
        }
        return deliverClaim(player, result);
    }

    /** 玩家上线时自动投递收货箱中的物品；返回本次投递成功的件数。 */
    public int deliverClaims(Player player) {
        int delivered = 0;
        List<Claim> claims = storage.getClaims(player.getUniqueId(), 0, 100);
        for (Claim claim : claims) {
            TradingCore.ClaimResult result = core.claim(player.getUniqueId(), claim.id());
            if (result.status() == TradingCore.Status.FAILED) {
                plugin.getLogger().warning("自动投递中止：领取提交失败 (claim id=" + claim.id() + ")");
                break;
            }
            if (result.status() != TradingCore.Status.SUCCESS) {
                continue;
            }
            ClaimResult outcome = deliverClaim(player, result);
            if (outcome.outcome() == ClaimOutcome.CLAIMED) {
                delivered++;
                continue;
            }
            // 背包放不下（部分或全部留在收货箱）就停手，剩下的等玩家清出空间再领
            break;
        }
        return delivered;
    }

    /**
     * 把一条已提交的领取流水交付到背包：全部装下就结清流水；
     * 放不下的部分写回收货箱；写回失败才掉落到脚下（最后兜底）。
     */
    private ClaimResult deliverClaim(Player player, TradingCore.ClaimResult result) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(result.item().clone());
        if (leftover.isEmpty()) {
            core.completeDelivery(result.pendingId());
            return new ClaimResult(ClaimOutcome.CLAIMED, 0);
        }
        ItemStack rest = leftover.values().iterator().next();
        int remaining = rest.getAmount();
        if (storage.addClaim(player.getUniqueId(), rest, result.reason()) > 0) {
            core.completeDelivery(result.pendingId());
            return new ClaimResult(ClaimOutcome.PARTIAL, remaining);
        }
        drop(player, rest, "领取剩余部分写回收货箱失败");
        core.completeDelivery(result.pendingId());
        return new ClaimResult(ClaimOutcome.CLAIMED_DROPPED, 0);
    }

    // ---------------- 异常流水恢复 ----------------

    /**
     * 处理所有未结清的交易流水：能自动补齐的补齐，需要玩家在线的等上线，
     * 钱到底动没动无法判定的只记日志等管理员核对。
     *
     * @return 本轮统计
     */
    public RecoveryReport recoverPending() {
        List<PendingOp> ops = storage.getPendingOps(500);
        if (ops.isEmpty()) {
            return new RecoveryReport(0, 0, 0, 0);
        }
        int resolved = 0;
        int delivered = 0;
        int retry = 0;
        int manual = 0;
        for (PendingOp op : ops) {
            TradingCore.Recovery outcome = core.recover(op);
            switch (outcome) {
                case RESOLVED -> resolved++;
                case NEEDS_MANUAL -> manual++;
                case NEEDS_PLAYER -> {
                    Player player = Bukkit.getPlayer(op.playerUuid());
                    if (player != null && player.isOnline() && deliverPending(player, op)) {
                        delivered++;
                    } else {
                        retry++;
                    }
                }
                case RETRY -> retry++;
            }
        }
        if (resolved + delivered + manual > 0) {
            plugin.getLogger().info("交易流水恢复：完成 " + resolved + " 条，补发 " + delivered
                    + " 条，待重试 " + retry + " 条，需人工核对 " + manual + " 条。");
        }
        return new RecoveryReport(resolved, delivered, retry, manual);
    }

    /** 玩家上线时补发欠他的物品（上次崩溃/离线时没送达的）。 */
    public int deliverPending(Player player) {
        int delivered = 0;
        for (PendingOp op : storage.getPendingOps(500)) {
            if (!op.playerUuid().equals(player.getUniqueId()) || !op.stage().owesItem()) {
                continue;
            }
            if (deliverPending(player, op)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 投递一条流水里的物品；装不下就把剩余写回收货箱，仍然失败才掉落到脚下。 */
    private boolean deliverPending(Player player, PendingOp op) {
        if (op.item() == null) {
            plugin.getLogger().severe("流水 #" + op.id() + " 缺少物品，无法投递，请管理员核对。");
            return false;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(op.item().clone());
        if (!leftover.isEmpty()) {
            ItemStack rest = leftover.values().iterator().next();
            if (storage.addClaim(op.playerUuid(), rest, Claim.REASON_ROLLBACK) <= 0) {
                drop(player, rest, "补发失败且写回收货箱失败 (流水 #" + op.id() + ")");
            }
        }
        return core.completeDelivery(op.id());
    }

    /** 管理员查看：当前未结清的流水。 */
    public List<PendingOp> listPending() {
        return storage.getPendingOps(200);
    }

    public int countPending() {
        return storage.countPendingOps();
    }

    /** 掉落物品并记日志（最后的兜底，宁可掉在地上也不吞掉）。 */
    private void drop(Player player, ItemStack item, String why) {
        plugin.getLogger().severe(why + "，物品已掉落在 " + player.getName() + " 脚下: "
                + item.getType() + " x" + item.getAmount());
        player.getWorld().dropItemNaturally(player.getLocation(), item);
    }

    // ---------------- 工具 ----------------

    /** 物品显示名（无自定义名则用材质名）。 */
    public static String displayName(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "空气";
        }
        var meta = item.getItemMeta();
        if (meta != null && meta.displayName() != null) {
            return Text.toLegacy(meta.displayName());
        }
        return item.getType().name().toLowerCase().replace('_', ' ');
    }
}
