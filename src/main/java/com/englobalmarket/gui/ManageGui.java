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
 * 我的商品界面（45 格）：分页展示自己的上架商品，底部提供收货箱/返回/退出。
 */
public class ManageGui extends BaseGui {

    public static final int PAGE_SIZE = 36;
    public static final int LIST_START = 0;
    public static final int LIST_END = 35;
    public static final int PREV_SLOT = 36;
    public static final int CLAIM_SLOT = 38;
    public static final int BACK_SLOT = 39;
    public static final int EXIT_SLOT = 40;
    public static final int INFO_SLOT = 42;
    public static final int NEXT_SLOT = 44;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final Page pageInfo;
    private final List<Listing> listings;

    public ManageGui(EnGlobalMarket plugin, Player player) {
        this(plugin, player, 1);
    }

    public ManageGui(EnGlobalMarket plugin, Player player, int page) {
        super(plugin, player, 45, "manage-title");
        this.pageInfo = Page.of(page, plugin.getMarketService().countBySeller(player.getUniqueId()), PAGE_SIZE);
        this.listings = plugin.getMarketService().getListingsBySeller(player.getUniqueId(), pageInfo.offset(), PAGE_SIZE);
        build();
    }

    private void build() {
        ItemStack filler = filler();
        fill(LIST_END + 1, 44, filler);

        for (int i = 0; i < listings.size() && i < PAGE_SIZE; i++) {
            inventory.setItem(LIST_START + i, listingItem(listings.get(i)));
        }

        inventory.setItem(INFO_SLOT, button(Material.BOOK, "&e&l我的商品", List.of(
                "&7第 &f" + pageInfo.page() + "&7/&f" + pageInfo.totalPages() + "&7 页",
                "&7共 &f" + plugin.getMarketService().countBySeller(player.getUniqueId()) + "&7 件",
                "&7点击商品下架或修改价格")));

        inventory.setItem(PREV_SLOT, pageInfo.hasPrevious()
                ? button(Material.ARROW, "&a上一页", List.of("&7翻到上一页"))
                : button(Material.GRAY_DYE, "&7上一页", List.of("&7已是第一页")));
        inventory.setItem(NEXT_SLOT, pageInfo.hasNext()
                ? button(Material.ARROW, "&a下一页", List.of("&7翻到下一页"))
                : button(Material.GRAY_DYE, "&7下一页", List.of("&7已是最后一页")));

        int claims = plugin.getMarketService().countClaims(player.getUniqueId());
        inventory.setItem(CLAIM_SLOT, claims > 0
                ? button(Material.CHEST, "&b领取收货箱 &e(&f" + claims + "&e)", List.of("&7点击领取过期/下架的物品"))
                : button(Material.GRAY_DYE, "&7收货箱", List.of("&7暂无待领取物品")));
        inventory.setItem(BACK_SLOT, button(Material.ENDER_PEARL, "&b返回市场", List.of("&7回到全球市场")));
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
        lore.add(Text.color("&6总价: &f" + plugin.money(listing.price())));
        lore.add(Text.color("&7单价: &f" + PriceUtil.format(listing.unitPrice()) + " / 个"));
        lore.add(Text.color("&7上架: &f" + TIME.format(new Date(listing.listedAt()))));
        long remain = listing.remainingMillis(plugin.getExpireDays());
        lore.add(Text.color(remain > 0 ? "&7剩余: &f" + MarketGui.formatRemain(remain) : "&c即将过期"));
        lore.add(Text.color("&7点击管理此商品"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getSlot();
        if (slot == PREV_SLOT && pageInfo.hasPrevious()) {
            plugin.getGuiManager().open(player, new ManageGui(plugin, player, pageInfo.page() - 1));
        } else if (slot == NEXT_SLOT && pageInfo.hasNext()) {
            plugin.getGuiManager().open(player, new ManageGui(plugin, player, pageInfo.page() + 1));
        } else if (slot == CLAIM_SLOT) {
            plugin.getGuiManager().open(player, new ClaimGui(plugin, player));
        } else if (slot == BACK_SLOT) {
            plugin.getGuiManager().open(player, new MarketGui(plugin, player));
        } else if (slot == EXIT_SLOT) {
            player.closeInventory();
        } else if (slot >= LIST_START && slot <= LIST_END) {
            int index = slot - LIST_START;
            if (index < listings.size()) {
                plugin.getGuiManager().open(player, new ManageItemGui(plugin, player, listings.get(index)));
            }
        }
    }
}



