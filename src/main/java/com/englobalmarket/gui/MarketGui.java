package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Listing;
import com.englobalmarket.util.ItemUtil;
import com.englobalmarket.util.Page;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 全球市场主界面（45 格）：0-35 商品列表、36-44 底部功能按钮与页码。
 */
public class MarketGui extends BaseGui {

    public static final int PAGE_SIZE = 36;
    public static final int LIST_START = 0;
    public static final int LIST_END = 35;
    public static final int PREV_SLOT = 36;
    public static final int MANAGE_SLOT = 38;
    public static final int CREATE_SLOT = 39;
    public static final int EXIT_SLOT = 40;
    public static final int INFO_SLOT = 42;
    public static final int NEXT_SLOT = 44;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final Page pageInfo;
    private final List<Listing> listings;

    public MarketGui(EnGlobalMarket plugin, Player player) {
        this(plugin, player, 1);
    }

    public MarketGui(EnGlobalMarket plugin, Player player, int page) {
        super(plugin, player, 45, "market-title");
        this.pageInfo = Page.of(page, plugin.getMarketService().countListings(), PAGE_SIZE);
        this.listings = plugin.getMarketService().getListings(pageInfo.offset(), PAGE_SIZE);
        build();
    }

    private void build() {
        ItemStack filler = filler();
        fill(LIST_END + 1, 44, filler);

        for (int i = 0; i < listings.size() && i < PAGE_SIZE; i++) {
            inventory.setItem(LIST_START + i, listingItem(listings.get(i)));
        }

        inventory.setItem(INFO_SLOT, button(Material.BOOK, "&e&l全球市场", List.of(
                "&7第 &f" + pageInfo.page() + "&7/&f" + pageInfo.totalPages() + "&7 页",
                "&7共 &f" + plugin.getMarketService().countListings() + "&7 件商品",
                "&7点击商品购买整组",
                "&7底部按钮管理你的商品")));

        inventory.setItem(PREV_SLOT, pageInfo.hasPrevious()
                ? button(Material.ARROW, "&a上一页", List.of("&7翻到上一页"))
                : button(Material.GRAY_DYE, "&7上一页", List.of("&7已是第一页")));
        inventory.setItem(NEXT_SLOT, pageInfo.hasNext()
                ? button(Material.ARROW, "&a下一页", List.of("&7翻到下一页"))
                : button(Material.GRAY_DYE, "&7下一页", List.of("&7已是最后一页")));
        inventory.setItem(MANAGE_SLOT, button(Material.CHEST, "&b管理我的商品", List.of("&7查看、下架或修改价格")));
        inventory.setItem(CREATE_SLOT, button(Material.EMERALD, "&a创建商店", List.of("&7把物品上架到全球市场")));
        inventory.setItem(EXIT_SLOT, button(Material.BARRIER, "&c退出", List.of("&7关闭界面")));
    }

    private ItemStack listingItem(Listing listing) {
        ItemStack item = ItemUtil.displayCopy(listing.item());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.empty());
        lore.add(Text.color("&7卖家: &f" + listing.sellerName()));
        lore.add(Text.color("&6总价: &f" + plugin.money(listing.price())));
        lore.add(Text.color("&7单价: &f" + PriceUtil.format(listing.unitPrice()) + " / 个"));
        lore.add(Text.color("&7上架: &f" + TIME.format(new Date(listing.listedAt()))));
        long remain = listing.remainingMillis(plugin.getExpireDays());
        lore.add(Text.color(remain > 0 ? "&7剩余: &f" + formatRemain(remain) : "&c即将过期"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    static String formatRemain(long millis) {
        long totalMinutes = Math.max(0, millis / 60_000);
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分";
        }
        return minutes + " 分";
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getSlot();
        if (slot == PREV_SLOT && pageInfo.hasPrevious()) {
            plugin.getGuiManager().open(player, new MarketGui(plugin, player, pageInfo.page() - 1));
        } else if (slot == NEXT_SLOT && pageInfo.hasNext()) {
            plugin.getGuiManager().open(player, new MarketGui(plugin, player, pageInfo.page() + 1));
        } else if (slot == MANAGE_SLOT) {
            plugin.getGuiManager().open(player, new ManageGui(plugin, player));
        } else if (slot == CREATE_SLOT) {
            plugin.getGuiManager().open(player, new CreateGui(plugin, player));
        } else if (slot == EXIT_SLOT) {
            player.closeInventory();
        } else if (slot >= LIST_START && slot <= LIST_END) {
            int index = slot - LIST_START;
            if (index < listings.size()) {
                Listing listing = listings.get(index);
                if (listing.sellerUuid().equals(player.getUniqueId())) {
                    // 点击自己的商品：转到商品管理界面
                    plugin.getGuiManager().open(player, new ManageItemGui(plugin, player, listing));
                } else {
                    plugin.getGuiManager().open(player, new ConfirmBuyGui(plugin, player, listing));
                }
            }
        }
    }
}




