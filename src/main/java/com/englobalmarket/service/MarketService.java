package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.model.Listing;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 市场核心逻辑：上架、购买、下架、改价、过期下架、收货箱。
 */
public class MarketService {

    public enum CreateResult { SUCCESS, NO_ECONOMY, NO_ITEM, TOO_MANY, LIMIT_STACK, INVALID_PRICE, FAILED }

    public enum BuyResult { SUCCESS, NO_ECONOMY, GONE, OWN, NOT_ENOUGH_MONEY }

    public enum DelistResult { SUCCESS, GONE, NOT_OWNER }

    private final EnGlobalMarket plugin;
    private final MarketStorage storage;
    private final EconomyService economy;

    public MarketService(EnGlobalMarket plugin, MarketStorage storage, EconomyService economy) {
        this.plugin = plugin;
        this.storage = storage;
        this.economy = economy;
    }

    /** 经济是否可用（Vault 缺失时 economy 为 null）。 */
    private boolean economyAvailable() {
        return economy != null && economy.isAvailable();
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

    public CreateResult createListing(Player player, ItemStack item, double price) {
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
        if (!Double.isFinite(price) || price <= 0 || price > PriceUtil.MAX_PRICE) {
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

    public BuyResult purchase(Player buyer, int listingId) {
        if (!economyAvailable()) {
            return BuyResult.NO_ECONOMY;
        }
        Listing listing = storage.getListing(listingId);
        if (listing == null) {
            return BuyResult.GONE;
        }
        if (listing.sellerUuid().equals(buyer.getUniqueId())) {
            return BuyResult.OWN;
        }
        double price = listing.price();
        if (!economy.has(buyer, price)) {
            return BuyResult.NOT_ENOUGH_MONEY;
        }
        if (!economy.withdraw(buyer, price)) {
            return BuyResult.NOT_ENOUGH_MONEY;
        }

        double proceeds = PriceUtil.sellerProceeds(price, plugin.getTaxRate());
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerUuid());
        if (!economy.deposit(seller, proceeds)) {
            plugin.getLogger().warning("向卖家 " + listing.sellerName() + " 入账失败（listing id=" + listing.id() + "）");
        }

        storage.deleteListing(listingId);
        giveItem(buyer, listing.item().clone());

        Player sellerOnline = Bukkit.getPlayer(listing.sellerUuid());
        if (sellerOnline != null && sellerOnline.isOnline()) {
            sellerOnline.sendMessage(Text.color(plugin.getMessage("sold-notify")
                    .replace("%item%", displayName(listing.item()))
                    .replace("%proceeds%", plugin.money(proceeds))));
        }
        return BuyResult.SUCCESS;
    }

    private void giveItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        if (!leftover.isEmpty()) {
            player.sendMessage(Text.color(plugin.getMessage("inventory-full")));
        }
    }

    // ---------------- 下架 / 改价 ----------------

    public DelistResult delist(Player player, int listingId) {
        Listing listing = storage.getListing(listingId);
        if (listing == null) {
            return DelistResult.GONE;
        }
        if (!listing.sellerUuid().equals(player.getUniqueId())) {
            return DelistResult.NOT_OWNER;
        }
        storage.deleteListing(listingId);
        returnItem(player, listing.item().clone());
        return DelistResult.SUCCESS;
    }

    public boolean changePrice(Player player, int listingId, double price) {
        Listing listing = storage.getListing(listingId);
        if (listing == null || !listing.sellerUuid().equals(player.getUniqueId())) {
            return false;
        }
        if (!Double.isFinite(price) || price <= 0 || price > PriceUtil.MAX_PRICE) {
            return false;
        }
        return storage.updatePrice(listingId, price);
    }

    /** 把物品退回玩家背包；放不下的部分放入收货箱。 */
    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            return;
        }
        for (ItemStack rest : leftover.values()) {
            storage.addClaim(player.getUniqueId(), rest, Claim.REASON_OVERFLOW);
        }
        player.sendMessage(Text.color(plugin.getMessage("inventory-full")));
    }

    // ---------------- 过期 ----------------

    /** 把超过有效期仍未售出的商品下架并转入卖家收货箱。 */
    public void expireAll() {
        long cutoff = System.currentTimeMillis() - (long) plugin.getExpireDays() * 86_400_000L;
        List<Listing> expired = storage.getExpiredListings(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        for (Listing listing : expired) {
            storage.deleteListing(listing.id());
            storage.addClaim(listing.sellerUuid(), listing.item(), Claim.REASON_EXPIRED);
            Player seller = Bukkit.getPlayer(listing.sellerUuid());
            if (seller != null && seller.isOnline()) {
                seller.sendMessage(Text.color(plugin.getMessage("expired-notify")
                        .replace("%item%", displayName(listing.item()))
                        .replace("%days%", String.valueOf(plugin.getExpireDays()))));
            }
        }
        plugin.getLogger().info("已自动下架 " + expired.size() + " 件过期商品。");
    }

    // ---------------- 收货箱 ----------------

    public List<Claim> getClaims(UUID owner, int offset, int limit) {
        return storage.getClaims(owner, offset, limit);
    }

    public int countClaims(UUID owner) {
        return storage.countClaims(owner);
    }

    /**
     * 领取收货箱中的一件物品；背包放不下的部分保留在原记录中。
     * 返回剩余未领取数量：0 = 已全部领取，>0 = 部分领取，-1 = 不存在/非本人。
     */
    public int claimItem(Player player, int claimId) {
        Claim claim = storage.getClaim(claimId);
        if (claim == null || !claim.ownerUuid().equals(player.getUniqueId())) {
            return -1;
        }
        ItemStack item = claim.item().clone();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            storage.deleteClaim(claimId);
            return 0;
        }
        ItemStack rest = leftover.values().iterator().next();
        storage.updateClaimItem(claimId, rest);
        return rest.getAmount();
    }

    /** 玩家上线时自动投递收货箱中的物品；返回本次投递成功的件数。 */
    public int deliverClaims(Player player) {
        int delivered = 0;
        List<Claim> claims = storage.getClaims(player.getUniqueId(), 0, 100);
        for (Claim claim : claims) {
            ItemStack item = claim.item().clone();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (leftover.isEmpty()) {
                storage.deleteClaim(claim.id());
                delivered++;
            } else {
                ItemStack rest = leftover.values().iterator().next();
                storage.updateClaimItem(claim.id(), rest);
                break;
            }
        }
        return delivered;
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

