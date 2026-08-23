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
 * 确认购买界面（9 格）：0=商品、1=交易信息、4=确认购买、8=取消。
 */
public class ConfirmBuyGui extends BaseGui {

    public static final int ITEM_SLOT = 0;
    public static final int INFO_SLOT = 1;
    public static final int CONFIRM_SLOT = 4;
    public static final int CANCEL_SLOT = 8;

    private final Listing listing;

    public ConfirmBuyGui(EnGlobalMarket plugin, Player player, Listing listing) {
        super(plugin, player, 9, "confirm-title");
        this.listing = listing;
        build();
    }

    private void build() {
        ItemStack filler = filler();
        for (int i = 0; i < 9; i++) {
            if (i != ITEM_SLOT && i != INFO_SLOT && i != CONFIRM_SLOT && i != CANCEL_SLOT) {
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
            lore.add(Text.color("&7卖家: &f" + listing.sellerName()));
            lore.add(Text.color("&6总价: &f" + plugin.money(listing.price())));
            lore.add(Text.color("&7单价: &f" + PriceUtil.format(listing.unitPrice()) + " / 个"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        inventory.setItem(ITEM_SLOT, item);

        double proceeds = PriceUtil.sellerProceeds(listing.price(), plugin.getTaxRate());
        inventory.setItem(INFO_SLOT, button(Material.BOOK, "&e&l购买确认", List.of(
                "&7购买整组商品",
                "&7需支付: &f" + plugin.money(listing.price()),
                "&7卖家实收（税后）: &f" + plugin.money(proceeds),
                "&7你的余额: &f" + plugin.money(plugin.getEconomyService().getBalance(player)),
                "&7确认后将立即从余额扣除")));
        inventory.setItem(CONFIRM_SLOT, button(Material.LIME_DYE, "&a&l确认购买", List.of("&7点击完成交易")));
        inventory.setItem(CANCEL_SLOT, button(Material.BARRIER, "&c取消", List.of("&7返回市场")));
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        if (event.getSlot() == CONFIRM_SLOT) {
            confirm();
        } else if (event.getSlot() == CANCEL_SLOT) {
            plugin.getGuiManager().open(player, new MarketGui(plugin, player));
        }
    }

    private void confirm() {
        MarketService.BuyResult result = plugin.getMarketService().purchase(player, listing.id());
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1, 1);
                player.sendMessage(Text.color(plugin.getMessage("buy-success")
                        .replace("%price%", plugin.money(listing.price()))));
            }
            case GONE -> player.sendMessage(Text.color(plugin.getMessage("buy-sold")));
            case OWN -> player.sendMessage(Text.color(plugin.getMessage("buy-own")));
            case NOT_ENOUGH_MONEY -> player.sendMessage(Text.color(plugin.getMessage("buy-no-money")
                    .replace("%price%", plugin.money(listing.price()))));
            case NO_ECONOMY -> player.sendMessage(Text.color(plugin.getMessage("no-economy")));
        }
        plugin.getGuiManager().open(player, new MarketGui(plugin, player));
    }
}



