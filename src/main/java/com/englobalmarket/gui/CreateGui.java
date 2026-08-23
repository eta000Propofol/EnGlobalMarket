package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 创建商品界面（9 格）：0=物品槽、1=说明、4=设置价格、7=取消、8=确认上架。
 */
public class CreateGui extends BaseGui {

    public static final int ITEM_SLOT = 0;
    public static final int INFO_SLOT = 1;
    public static final int PRICE_SLOT = 4;
    public static final int CANCEL_SLOT = 7;
    public static final int CONFIRM_SLOT = 8;

    private double price = 0;

    public CreateGui(EnGlobalMarket plugin, Player player) {
        super(plugin, player, 9, "create-title");
        build();
    }

    private void build() {
        ItemStack filler = filler();
        for (int i = 0; i < 9; i++) {
            if (i != ITEM_SLOT && i != INFO_SLOT && i != PRICE_SLOT && i != CANCEL_SLOT && i != CONFIRM_SLOT) {
                inventory.setItem(i, filler.clone());
            }
        }
        inventory.setItem(INFO_SLOT, button(Material.PAPER, "&e&l上架说明", List.of(
                "&7把要出售的物品放入左侧第一格",
                "&7一次最多上架一个原版堆叠",
                "&7（普通物品 64 / 雪球鸡蛋 16 / 药水装备 1）",
                "&7点击绿宝石设置整组总价",
                "&7最后点击确认上架")));
        updatePriceButton();
        inventory.setItem(CANCEL_SLOT, button(Material.BARRIER, "&c取消", List.of("&7返回市场，物品会退回背包")));
        inventory.setItem(CONFIRM_SLOT, button(Material.LIME_DYE, "&a&l确认上架", List.of(
                "&7成交后收取税率: &f" + (int) (plugin.getTaxRate() * 100) + "%")));
    }

    public void setPrice(double price) {
        this.price = price;
        updatePriceButton();
    }

    private void updatePriceButton() {
        String name = price > 0 ? "&a&l设置价格: &f" + PriceUtil.format(price) : "&e&l设置价格";
        inventory.setItem(PRICE_SLOT, button(Material.EMERALD, name, List.of(
                price > 0 ? "&7当前整组总价: &f" + plugin.money(price) : "&7尚未设置价格",
                "&7点击用铁砧输入价格")));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        boolean top = event.getClickedInventory() == inventory;
        boolean bottom = event.getClickedInventory() == player.getInventory();
        boolean normal = !event.isShiftClick()
                && event.getAction() != InventoryAction.HOTBAR_SWAP
                && event.getAction() != InventoryAction.COLLECT_TO_CURSOR;

        if (top && event.getSlot() == PRICE_SLOT) {
            event.setCancelled(true);
            plugin.getGuiManager().beginPriceInput(player, this, -1, price);
            return;
        }
        if (top && event.getSlot() == CANCEL_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (top && event.getSlot() == CONFIRM_SLOT) {
            event.setCancelled(true);
            confirm();
            return;
        }
        if (top && event.getSlot() == ITEM_SLOT && normal) {
            event.setCancelled(false);
            return;
        }
        if (bottom && normal) {
            event.setCancelled(false);
            return;
        }
        event.setCancelled(true);
    }

    private void confirm() {
        ItemStack item = inventory.getItem(ITEM_SLOT);
        if (item == null || item.getType().isAir()) {
            player.sendMessage(Text.color(plugin.getMessage("create-no-item")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            return;
        }
        if (price <= 0) {
            player.sendMessage(Text.color(plugin.getMessage("create-no-price")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            return;
        }
        MarketService.CreateResult result = plugin.getMarketService().createListing(player, item, price);
        switch (result) {
            case SUCCESS -> {
                inventory.setItem(ITEM_SLOT, null);
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1, 1);
                player.sendMessage(Text.color(plugin.getMessage("create-success")));
                plugin.getGuiManager().open(player, new MarketGui(plugin, player));
            }
            case NO_ECONOMY -> player.sendMessage(Text.color(plugin.getMessage("no-economy")));
            case TOO_MANY -> player.sendMessage(Text.color(plugin.getMessage("create-too-many")
                    .replace("%max%", String.valueOf(plugin.getMaxListings()))));
            case LIMIT_STACK -> player.sendMessage(Text.color(plugin.getMessage("create-limit-stack")
                    .replace("%max%", String.valueOf(item.getMaxStackSize()))));
            case INVALID_PRICE -> player.sendMessage(Text.color(plugin.getMessage("invalid-price")));
            default -> player.sendMessage(Text.color(plugin.getMessage("create-fail")));
        }
    }

    @Override
    public void onClose() {
        ItemStack item = inventory.getItem(ITEM_SLOT);
        if (item == null || item.getType().isAir()) {
            return;
        }
        inventory.setItem(ITEM_SLOT, null);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    @Override
    public void onQuit() {
        ItemStack item = inventory.getItem(ITEM_SLOT);
        if (item == null || item.getType().isAir()) {
            return;
        }
        inventory.setItem(ITEM_SLOT, null);
        player.getWorld().dropItemNaturally(player.getLocation(), item);
    }
}
