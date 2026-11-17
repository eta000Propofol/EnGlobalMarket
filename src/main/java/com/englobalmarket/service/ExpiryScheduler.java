package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;

/**
 * 过期检查调度器：每隔配置的分钟数扫描一次过期商品，并顺带重试未结清的交易流水
 * （退款失败等待重试的流水不依赖玩家在线）。
 */
public class ExpiryScheduler {

    private final EnGlobalMarket plugin;
    private org.bukkit.scheduler.BukkitTask task;

    public ExpiryScheduler(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long minutes = Math.max(1, plugin.getConfig().getInt("market.check-interval-minutes", 5));
        long ticks = minutes * 60L * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            plugin.getMarketService().expireAll();
            plugin.getMarketService().recoverPending();
        }, ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
