package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Listing;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.util.ItemUtil;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个商品管理界面（27 格）：11=商品、12=修改价格、14=下架、15=返回。
 */
public class ManageItemGui extends BaseGui {

    public static final int ITEM_SLOT = 11;
    public static final int PRICE_SLOT = 12;
    public static final int DELIST_SLOT = 14;
    public static final int BACK_SLOT = 15;

    private final Listing listing;

    public ManageItemGui(EnGlobalMarket plugin, Player player, Listing listing) {
        super(plugin, player, 27, "manage-item-title");
        this.listing = listing;
        build();
    }

    private void build() {
        ItemStack filler = filler();
        for (int i = 0; i < 27; i++) {
            if (i != ITEM_SLOT && i != PRICE_SLOT && i != DELIST_SLOT && i != BACK_SLOT) {
                inventory.setItem(i, filler.clone());
            }
        }

        ItemStack item = ItemUtil.displayCopy(listing.item());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            if (meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(Component.empty());
            lore.add(Text.color("&6总价: &f" + plugin.money(listing.price())));
            lore.add(Text.color("&7单价: &f" + PriceUtil.format(listing.unitPrice()) + " / 个"));
            long remain = listing.remainingMillis(plugin.getExpireDays());
            lore.add(Text.color(remain > 0 ? "&7剩余: &f" + MarketGui.formatRemain(remain) : "&c即将过期"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(ITEM_SLOT, item);

        inventory.setItem(PRICE_SLOT, button(Material.EMERALD, "&a修改价格", List.of(
                "&7当前总价: &f" + plugin.money(listing.price()),
                "&7点击用铁砧输入新价格")));
        inventory.setItem(DELIST_SLOT, button(Material.RED_DYE, "&c下架", List.of(
                "&7下架后物品退回你的背包")));
        inventory.setItem(BACK_SLOT, button(Material.BARRIER, "&c返回", List.of("&7回到我的商品")));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getSlot();
        if (slot == PRICE_SLOT) {
            plugin.getGuiManager().beginPriceInput(player, this, listing.id(), listing.price());
        } else if (slot == DELIST_SLOT) {
            delist();
        } else if (slot == BACK_SLOT) {
            plugin.getGuiManager().open(player, new ManageGui(plugin, player));
        }
    }

    private void delist() {
        MarketService.DelistResult result = plugin.getMarketService().delist(player, listing.id());
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1, 1);
                player.sendMessage(Text.color(plugin.getMessage("delist-success")));
            }
            case GONE -> player.sendMessage(Text.color(plugin.getMessage("buy-sold")));
            case NOT_OWNER -> player.sendMessage(Text.color(plugin.getMessage("not-owner")));
        }
        plugin.getGuiManager().open(player, new ManageGui(plugin, player));
    }
}

