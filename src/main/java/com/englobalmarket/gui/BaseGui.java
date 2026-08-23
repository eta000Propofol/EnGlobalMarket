package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 市场 GUI 基类：持有者、通用按钮构建工具、切换/关闭标记。
 */
public abstract class BaseGui implements InventoryHolder {

    protected final EnGlobalMarket plugin;
    protected final Player player;
    protected final Inventory inventory;
    private boolean switching = false;

    protected BaseGui(EnGlobalMarket plugin, Player player, int size, String titleKey) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, size, Text.color(plugin.getGuiTitle(titleKey)));
    }

    /** 点击事件分发（子类实现）。 */
    public abstract void handleClick(InventoryClickEvent event);

    /** 界面关闭时的清理（子类可覆写，如返还物品）。 */
    public void onClose() {
    }

    /** 玩家退出时的强制清理（子类可覆写，如掉落物品）。 */
    public void onQuit() {
    }

    public void setSwitching(boolean switching) {
        this.switching = switching;
    }

    public boolean isSwitching() {
        return switching;
    }

    public Player getPlayer() {
        return player;
    }

    /** 灰色填充玻璃。 */
    protected ItemStack filler() {
        return filler(Material.GRAY_STAINED_GLASS_PANE);
    }

    protected ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 功能按钮。 */
    protected ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(Text::color).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 用同一物品填充一段格子（含两端）。 */
    protected void fill(int from, int to, ItemStack item) {
        for (int i = from; i <= to; i++) {
            inventory.setItem(i, item.clone());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
