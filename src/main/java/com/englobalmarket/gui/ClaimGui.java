package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Claim;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.util.ItemUtil;
import com.englobalmarket.util.Page;
import com.englobalmarket.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 收货箱界面（45 格）：分页展示待领取物品，点击领取进背包。
 */
public class ClaimGui extends BaseGui {

    public static final int PAGE_SIZE = 36;
    public static final int LIST_START = 0;
    public static final int LIST_END = 35;
    public static final int PREV_SLOT = 36;
    public static final int BACK_SLOT = 39;
    public static final int EXIT_SLOT = 40;
    public static final int INFO_SLOT = 42;
    public static final int NEXT_SLOT = 44;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final Page pageInfo;
    private final List<Claim> claims;

    public ClaimGui(EnGlobalMarket plugin, Player player) {
        this(plugin, player, 1);
    }

    public ClaimGui(EnGlobalMarket plugin, Player player, int page) {
        super(plugin, player, 45, "claim-title");
        this.pageInfo = Page.of(page, plugin.getMarketService().countClaims(player.getUniqueId()), PAGE_SIZE);
        this.claims = plugin.getMarketService().getClaims(player.getUniqueId(), pageInfo.offset(), PAGE_SIZE);
        build();
    }

    private void build() {
        ItemStack filler = filler();
        fill(LIST_END + 1, 44, filler);

        for (int i = 0; i < claims.size() && i < PAGE_SIZE; i++) {
            inventory.setItem(LIST_START + i, claimItem(claims.get(i)));
        }

        inventory.setItem(INFO_SLOT, button(Material.BOOK, "&e&l收货箱", List.of(
                "&7第 &f" + pageInfo.page() + "&7/&f" + pageInfo.totalPages() + "&7 页",
                "&7共 &f" + plugin.getMarketService().countClaims(player.getUniqueId()) + "&7 件",
                "&7点击物品领取到背包")));

        inventory.setItem(PREV_SLOT, pageInfo.hasPrevious()
                ? button(Material.ARROW, "&a上一页", List.of("&7翻到上一页"))
                : button(Material.GRAY_DYE, "&7上一页", List.of("&7已是第一页")));
        inventory.setItem(NEXT_SLOT, pageInfo.hasNext()
                ? button(Material.ARROW, "&a下一页", List.of("&7翻到下一页"))
                : button(Material.GRAY_DYE, "&7下一页", List.of("&7已是最后一页")));
        inventory.setItem(BACK_SLOT, button(Material.ENDER_PEARL, "&b返回我的商品", List.of("&7回到管理界面")));
        inventory.setItem(EXIT_SLOT, button(Material.BARRIER, "&c退出", List.of("&7关闭界面")));
    }

    private ItemStack claimItem(Claim claim) {
        ItemStack item = ItemUtil.displayCopy(claim.item());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.empty());
        lore.add(Text.color("&7原因: &f" + reasonText(claim.reason())));
        lore.add(Text.color("&7时间: &f" + TIME.format(new Date(claim.createdAt()))));
        lore.add(Text.color("&7点击领取"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String reasonText(String reason) {
        return switch (reason) {
            case Claim.REASON_EXPIRED -> "&c超时自动下架";
            case Claim.REASON_DELISTED -> "&7主动下架";
            case Claim.REASON_OVERFLOW -> "&7背包放不下";
            default -> "&7其他";
        };
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getSlot();
        if (slot == PREV_SLOT && pageInfo.hasPrevious()) {
            plugin.getGuiManager().open(player, new ClaimGui(plugin, player, pageInfo.page() - 1));
        } else if (slot == NEXT_SLOT && pageInfo.hasNext()) {
            plugin.getGuiManager().open(player, new ClaimGui(plugin, player, pageInfo.page() + 1));
        } else if (slot == BACK_SLOT) {
            plugin.getGuiManager().open(player, new ManageGui(plugin, player));
        } else if (slot == EXIT_SLOT) {
            player.closeInventory();
        } else if (slot >= LIST_START && slot <= LIST_END) {
            int index = slot - LIST_START;
            if (index < claims.size()) {
                collect(claims.get(index));
            }
        }
    }

    private void collect(Claim claim) {
        int remaining = plugin.getMarketService().claimItem(player, claim.id());
        if (remaining == -1) {
            player.sendMessage(Text.color(plugin.getMessage("buy-sold")));
        } else if (remaining == 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1, 1);
            player.sendMessage(Text.color(plugin.getMessage("claim-success")
                    .replace("%item%", MarketService.displayName(claim.item()))
                    .replace("%amount%", String.valueOf(claim.item().getAmount()))));
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);
            player.sendMessage(Text.color(plugin.getMessage("inventory-full")));
        }
        plugin.getGuiManager().open(player, new ClaimGui(plugin, player, pageInfo.page()));
    }
}



