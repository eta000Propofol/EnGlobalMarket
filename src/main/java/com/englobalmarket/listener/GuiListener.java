package com.englobalmarket.listener;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.gui.BaseGui;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;

/**
 * GUI 事件监听：点击分发、聊天栏价格输入、关闭清理、退出清理。
 */
public class GuiListener implements Listener {

    private final EnGlobalMarket plugin;

    public GuiListener(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BaseGui gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BaseGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BaseGui closing) {
            plugin.getGuiManager().onClose(player, closing);
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getGuiManager().hasPriceInput(player)) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.getGuiManager().completePriceInput(player, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getGuiManager().onQuit(event.getPlayer());
    }
}

