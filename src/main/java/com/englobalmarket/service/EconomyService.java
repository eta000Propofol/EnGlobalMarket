package com.englobalmarket.service;

import com.englobalmarket.EnGlobalMarket;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 经济服务：通过 Vault 接口接入服务器的经济插件。
 *
 * <p>对外使用 {@link BigDecimal} 金额（两位小数），只在调用 Vault 时转成 double。
 */
public class EconomyService implements EconomyGateway {

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
    @Override
    public boolean isAvailable() {
        return economy != null;
    }

    @Override
    public boolean has(UUID player, BigDecimal amount) {
        return economy != null && amount != null && economy.has(player(player), toVault(amount));
    }

    @Override
    public BigDecimal balance(UUID player) {
        if (economy == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(economy.getBalance(player(player))).setScale(2, RoundingMode.HALF_UP);
    }

    /** 从玩家余额中扣除；成功返回 true。 */
    @Override
    public boolean withdraw(UUID player, BigDecimal amount) {
        if (economy == null) {
            return false;
        }
        if (amount == null || amount.signum() <= 0) {
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(player(player), toVault(amount));
        return response != null && response.transactionSuccess();
    }

    /** 给玩家入账；成功返回 true。 */
    @Override
    public boolean deposit(UUID player, BigDecimal amount) {
        if (economy == null) {
            return false;
        }
        if (amount == null || amount.signum() <= 0) {
            return true;
        }
        EconomyResponse response = economy.depositPlayer(player(player), toVault(amount));
        return response != null && response.transactionSuccess();
    }

    /** Vault 只接受 double：金额已是两位小数，转换是精确的。 */
    private static double toVault(BigDecimal amount) {
        return amount.doubleValue();
    }

    /** 在线玩家直接返回 Player 实例，离线玩家走 Bukkit 缓存。 */
    private static OfflinePlayer player(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    /** 金额显示（带货币单位，由经济插件格式化）。 */
    public String format(BigDecimal amount) {
        if (economy != null && amount != null) {
            return economy.format(toVault(amount));
        }
        return com.englobalmarket.util.PriceUtil.format(amount);
    }
}
