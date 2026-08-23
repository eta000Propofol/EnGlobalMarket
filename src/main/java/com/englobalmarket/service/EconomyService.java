package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.util.PriceUtil;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * 经济服务：通过 Vault 接口接入服务器的经济插件。
 */
public class EconomyService {

    private final EnGlobalMarket plugin;
    private Economy economy;

    public EconomyService(EnGlobalMarket plugin) {
        this.plugin = plugin;
        // 只有 Vault 确实安装了，才去触碰 Vault 的 Economy 类，避免 NoClassDefFoundError。
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("未安装 Vault，市场交易功能将不可用。");
            return;
        }
        try {
            RegisteredServiceProvider<Economy> provider = plugin.getServer()
                    .getServicesManager().getRegistration(Economy.class);
            if (provider != null && provider.getProvider() != null) {
                economy = provider.getProvider();
                plugin.getLogger().info("已接入 Vault 经济插件: " + economy.getName());
            } else {
                plugin.getLogger().warning("Vault 已安装，但没有可用的经济插件，市场交易功能将不可用。");
            }
        } catch (Throwable t) {
            economy = null;
            plugin.getLogger().warning("接入 Vault 失败，市场交易功能已禁用: " + t.getMessage());
        }
    }

    /** 经济是否可用。 */
    public boolean isAvailable() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public double getBalance(OfflinePlayer player) {
        return economy == null ? 0 : economy.getBalance(player);
    }

    /** 从玩家余额中扣除；成功返回 true。 */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return economy != null;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    /** 给玩家入账；成功返回 true。 */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return economy != null;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    /** 格式化金额（带货币单位）。 */
    public String format(double amount) {
        if (economy != null) {
            return economy.format(amount);
        }
        return PriceUtil.format(amount);
    }
}

