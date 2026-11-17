package com.englobalmarket.gui;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.model.Listing;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.util.PriceUtil;
import com.englobalmarket.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI 注册表与导航管理：追踪每个玩家打开的界面，处理切换与聊天栏价格输入。
 *
 * <p>线程约定：界面相关的 Map 只在主线程读写；价格输入的两个 Map 会被
 * {@code AsyncChatEvent} 线程查询（{@link #hasPriceInput}），因此使用并发容器保证可见性。
 */
public class GuiManager {

    /** 价格输入上下文。currentPrice 为 null 表示尚未设置过价格。 */
    public record PriceInput(BaseGui target, int listingId, BigDecimal currentPrice) {
    }

    /** 价格输入超时时间（tick）。 */
    private static final long PRICE_TIMEOUT_TICKS = 20L * 60;

    private final EnGlobalMarket plugin;
    private final Map<UUID, BaseGui> open = new LinkedHashMap<>();
    private final Map<UUID, PriceInput> priceInputs = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> priceTimeouts = new ConcurrentHashMap<>();

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
    public void beginPriceInput(Player player, BaseGui target, int listingId, BigDecimal currentPrice) {
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
        scheduleTimeout(player);
    }

    /** 是否正在等待该玩家输入价格（可能在异步线程调用）。 */
    public boolean hasPriceInput(Player player) {
        return priceInputs.containsKey(player.getUniqueId());
    }

    /** 处理玩家在聊天栏输入的价格（主线程调用）。 */
    public void completePriceInput(Player player, String rawText) {
        UUID uuid = player.getUniqueId();
        PriceInput input = priceInputs.remove(uuid);
        cancelTimeout(uuid);
        if (input == null) {
            return;
        }

        String text = rawText == null ? "" : rawText.trim();
        if (text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Text.color(plugin.getMessage("price-cancelled")));
            reopenAfterPriceInput(player, input);
            return;
        }

        BigDecimal price = PriceUtil.parse(text);
        if (price == null) {
            player.sendMessage(Text.color(plugin.getMessage("invalid-price")));
            player.sendMessage(Text.color(plugin.getMessage("price-prompt-hint")));
            // 输入无效：继续等待下一次输入
            priceInputs.put(uuid, input);
            scheduleTimeout(player);
            return;
        }

        if (input.target() instanceof CreateGui createGui) {
            createGui.setPrice(price);
            player.sendMessage(Text.color(plugin.getMessage("price-set")
                    .replace("%price%", plugin.money(price))));
        } else if (input.target() instanceof ManageItemGui) {
            switch (plugin.getMarketService().changePrice(player, input.listingId(), price)) {
                case SUCCESS -> player.sendMessage(Text.color(plugin.getMessage("price-change-success")
                        .replace("%price%", plugin.money(price))));
                case GONE, NOT_OWNER -> player.sendMessage(Text.color(plugin.getMessage("buy-sold")));
                case FAILED -> player.sendMessage(Text.color(plugin.getMessage("op-failed")));
            }
        }
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1, 1);
        reopenAfterPriceInput(player, input);
    }

    private void scheduleTimeout(Player player) {
        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> cancelPriceInput(player), PRICE_TIMEOUT_TICKS);
        BukkitTask previous = priceTimeouts.put(player.getUniqueId(), task);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelTimeout(UUID uuid) {
        BukkitTask task = priceTimeouts.remove(uuid);
        if (task != null) {
            task.cancel();
        }
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
