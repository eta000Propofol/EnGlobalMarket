package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Listing;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GUI 注册表与导航管理：追踪每个玩家打开的界面，处理切换与聊天栏价格输入。
 */
public class GuiManager {

    /** 价格输入上下文。 */
    public record PriceInput(BaseGui target, int listingId, double currentPrice) {
    }

    /** 价格输入超时时间（tick）。 */
    private static final long PRICE_TIMEOUT_TICKS = 20L * 60;

    private final EnGlobalMarket plugin;
    private final Map<UUID, BaseGui> open = new LinkedHashMap<>();
    private final Map<UUID, PriceInput> priceInputs = new LinkedHashMap<>();
    private final Map<UUID, BukkitTask> priceTimeouts = new LinkedHashMap<>();

    public GuiManager(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    /** 打开（或切换）到指定界面。旧界面标记为切换中，避免误清理。 */
    public void open(Player player, BaseGui gui) {
        BaseGui current = open.get(player.getUniqueId());
        if (current != null && current != gui) {
            current.setSwitching(true);
        }
        gui.setSwitching(false);
        open.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
    }

    /** 下一 tick 再打开（用于在关闭事件后重新打开界面）。 */
    public void openNextTick(Player player, BaseGui gui) {
        plugin.getServer().getScheduler().runTask(plugin, () -> open(player, gui));
    }

    /** 界面关闭事件：仅当关闭的界面就是注册中的界面时清理。 */
    public void onClose(Player player, BaseGui closing) {
        BaseGui registered = open.get(player.getUniqueId());
        if (registered == closing) {
            open.remove(player.getUniqueId());
            if (!closing.isSwitching()) {
                closing.onClose();
            }
        }
    }

    /** 玩家退出：强制清理并丢弃未处理的物品。 */
    public void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        BaseGui gui = open.remove(uuid);
        if (gui != null) {
            gui.onQuit();
        }
        PriceInput input = priceInputs.remove(uuid);
        BukkitTask task = priceTimeouts.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        if (input != null && input.target() instanceof CreateGui createGui) {
            createGui.onQuit();
        }
    }

    /** 插件卸载：清理所有打开的界面与待输入状态。 */
    public void closeAll() {
        for (BaseGui gui : new ArrayList<>(open.values())) {
            gui.onQuit();
        }
        for (PriceInput input : new ArrayList<>(priceInputs.values())) {
            if (input.target() instanceof CreateGui createGui) {
                createGui.onQuit();
            }
        }
        for (BukkitTask task : priceTimeouts.values()) {
            task.cancel();
        }
        open.clear();
        priceInputs.clear();
        priceTimeouts.clear();
    }

    // ---------------- 聊天栏价格输入 ----------------

    /**
     * 开始价格输入：关闭当前界面，让玩家在聊天栏输入价格。
     * 目标界面（含已放入的物品）会保留，输入结束/取消/超时后重新打开。
     */
    public void beginPriceInput(Player player, BaseGui target, int listingId, double currentPrice) {
        UUID uuid = player.getUniqueId();
        BaseGui current = open.get(uuid);
        if (current != null) {
            current.setSwitching(true);
            open.remove(uuid);
        }
        priceInputs.put(uuid, new PriceInput(target, listingId, currentPrice));
        player.closeInventory();
        player.sendMessage(Text.color(plugin.getMessage("price-prompt")));
        player.sendMessage(Text.color(plugin.getMessage("price-prompt-hint")));
        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> cancelPriceInput(player), PRICE_TIMEOUT_TICKS);
        priceTimeouts.put(uuid, task);
    }

    public boolean hasPriceInput(Player player) {
        return priceInputs.containsKey(player.getUniqueId());
    }

    /** 处理玩家在聊天栏输入的价格（主线程调用）。 */
    public void completePriceInput(Player player, String rawText) {
        UUID uuid = player.getUniqueId();
        PriceInput input = priceInputs.remove(uuid);
        BukkitTask task = priceTimeouts.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        if (input == null) {
            return;
        }

        String text = rawText == null ? "" : rawText.trim();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Text.color(plugin.getMessage("price-cancelled")));
            reopenAfterPriceInput(player, input);
            return;
        }

        Double price = PriceUtil.parse(text);
        if (price == null) {
            player.sendMessage(Text.color(plugin.getMessage("invalid-price")));
            player.sendMessage(Text.color(plugin.getMessage("price-prompt-hint")));
            // 输入无效：继续等待下一次输入
            priceInputs.put(uuid, input);
            priceTimeouts.put(uuid, plugin.getServer().getScheduler()
                    .runTaskLater(plugin, () -> cancelPriceInput(player), PRICE_TIMEOUT_TICKS));
            return;
        }

        if (input.target() instanceof CreateGui createGui) {
            createGui.setPrice(price);
            player.sendMessage(Text.color(plugin.getMessage("price-set")
                    .replace("%price%", plugin.money(price))));
        } else if (input.target() instanceof ManageItemGui) {
            if (plugin.getMarketService().changePrice(player, input.listingId(), price)) {
                player.sendMessage(Text.color(plugin.getMessage("price-change-success")
                        .replace("%price%", plugin.money(price))));
            } else {
                player.sendMessage(Text.color(plugin.getMessage("buy-sold")));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1, 1);
        reopenAfterPriceInput(player, input);
    }

    /** 超时取消。 */
    private void cancelPriceInput(Player player) {
        UUID uuid = player.getUniqueId();
        PriceInput input = priceInputs.remove(uuid);
        priceTimeouts.remove(uuid);
        if (input == null) {
            return;
        }
        player.sendMessage(Text.color(plugin.getMessage("price-cancelled")));
        reopenAfterPriceInput(player, input);
    }

    /** 输入结束后重新打开目标界面。 */
    private void reopenAfterPriceInput(Player player, PriceInput input) {
        if (input.target() instanceof ManageItemGui) {
            Listing listing = plugin.getMarketService().getListing(input.listingId());
            if (listing != null) {
                openNextTick(player, new ManageItemGui(plugin, player, listing));
            } else {
                openNextTick(player, new ManageGui(plugin, player));
            }
        } else {
            openNextTick(player, input.target());
        }
    }
}

