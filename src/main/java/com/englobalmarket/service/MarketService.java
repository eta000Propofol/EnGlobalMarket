package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
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
 * 真正的交易步骤与补偿回滚在 {@link TradingCore} 里。
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

    private final EnGlobalMarket plugin;
    private final ListingStore storage;
    private final TradingCore core;

    public MarketService(EnGlobalMarket plugin, ListingStore storage, EconomyGateway economy) {
        this.plugin = plugin;
        this.storage = storage;
        this.core = new TradingCore(storage, economy, message -> plugin.getLogger().warning(message));
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
        switch (result.status()) {
            case SUCCESS -> {
                giveItem(buyer, result.item().clone());
                notifySeller(result);
                return BuyResult.SUCCESS;
            }
            case UNAVAILABLE -> {
                return BuyResult.NO_ECONOMY;
            }
            case GONE -> {
                return BuyResult.GONE;
            }
            case OWN -> {
                return BuyResult.OWN;
            }
            case NOT_ENOUGH_MONEY -> {
                return BuyResult.NOT_ENOUGH_MONEY;
            }
            case PRICE_CHANGED -> {
                return BuyResult.PRICE_CHANGED;
            }
            default -> {
                return BuyResult.FAILED;
            }
        }
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
     * 领取收货箱中的一件物品。记录先删除再交货，背包放不下的部分重新入库；
     * 重新入库失败时掉落在地上，避免物品凭空消失。
     */
    public ClaimResult claimItem(Player player, int claimId) {
        TradingCore.ClaimResult result = core.claim(player.getUniqueId(), claimId);
        switch (result.status()) {
            case GONE -> {
                return new ClaimResult(ClaimOutcome.GONE, 0);
            }
            case FAILED -> {
                return new ClaimResult(ClaimOutcome.FAILED, 0);
            }
            default -> {
                // 继续发放
            }
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(result.item().clone());
        if (leftover.isEmpty()) {
            return new ClaimResult(ClaimOutcome.CLAIMED, 0);
        }
        ItemStack rest = leftover.values().iterator().next();
        int remaining = rest.getAmount();
        if (storage.addClaim(player.getUniqueId(), rest, result.reason()) > 0) {
            return new ClaimResult(ClaimOutcome.PARTIAL, remaining);
        }
        drop(player, rest, "领取剩余部分写回收货箱失败");
        return new ClaimResult(ClaimOutcome.CLAIMED_DROPPED, 0);
    }

    /** 玩家上线时自动投递收货箱中的物品；返回本次投递成功的件数。 */
    public int deliverClaims(Player player) {
        int delivered = 0;
        List<Claim> claims = storage.getClaims(player.getUniqueId(), 0, 100);
        for (Claim claim : claims) {
            TradingCore.ClaimResult result = core.claim(player.getUniqueId(), claim.id());
            if (result.status() == TradingCore.Status.FAILED) {
                plugin.getLogger().warning("自动投递中止：删除收货箱记录失败 (claim id=" + claim.id() + ")");
                break;
            }
            if (result.status() != TradingCore.Status.SUCCESS) {
                continue;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(result.item().clone());
            if (leftover.isEmpty()) {
                delivered++;
                continue;
            }
            ItemStack rest = leftover.values().iterator().next();
            if (storage.addClaim(player.getUniqueId(), rest, result.reason()) <= 0) {
                drop(player, rest, "自动投递剩余部分写回收货箱失败");
            }
            break;
        }
        return delivered;
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
