package com.englobalmarket;

import com.englobalmarket.command.MarketCommand;
import com.englobalmarket.gui.GuiManager;
import com.englobalmarket.listener.GuiListener;
import com.englobalmarket.listener.PlayerListener;
import com.englobalmarket.service.EconomyService;
import com.englobalmarket.service.ExpiryScheduler;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.service.MarketStorage;
import com.englobalmarket.util.PriceUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

/**
 * EnGlobalMarket 主类：全球市场插件。
 */
public final class EnGlobalMarket extends JavaPlugin {

    private static EnGlobalMarket instance;

    /**
     * 新增消息键的兜底文案：旧服务器不会重新生成 config.yml，
     * 取不到这些键时用默认值，避免提示消息变成空行。
     */
    private static final Map<String, String> MESSAGE_DEFAULTS = Map.of(
            "inventory-full-drop", "&c背包已满，物品已掉落在脚下。",
            "claim-partial", "&c背包已满，剩余 %amount% 个仍留在收货箱。");

    private EconomyService economyService;
    private MarketStorage storage;
    private MarketService marketService;
    private GuiManager guiManager;
    private ExpiryScheduler expiryScheduler;

    public static EnGlobalMarket instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();
        clampTax();

        try {
            storage = new MarketStorage(this, new File(getDataFolder(), "market.db"));
            storage.init();
        } catch (Exception e) {
            getLogger().severe("无法初始化数据库: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economyService = new EconomyService(this);
        marketService = new MarketService(this, storage, economyService);
        guiManager = new GuiManager(this);

        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        MarketCommand command = new MarketCommand(this);
        var market = getCommand("market");
        if (market != null) {
            market.setExecutor(command);
            market.setTabCompleter(command);
        }

        marketService.expireAll();

        expiryScheduler = new ExpiryScheduler(this);
        expiryScheduler.start();

        getLogger().info("EnGlobalMarket 已启用。当前税率: " + (int) (getTaxRate() * 100) + "%");
    }

    @Override
    public void onDisable() {
        if (expiryScheduler != null) {
            expiryScheduler.stop();
        }
        if (guiManager != null) {
            guiManager.closeAll();
        }
        if (storage != null) {
            storage.close();
        }
        instance = null;
        getLogger().info("EnGlobalMarket 已卸载。");
    }

    /** 重载配置并重新调度过期检查。 */
    public void reloadAll() {
        reloadConfig();
        clampTax();
        if (expiryScheduler != null) {
            expiryScheduler.start();
        }
        getLogger().info("EnGlobalMarket 配置已重载。");
    }

    /** 校验并把税率钳制在 [0, 1) 内。 */
    private void clampTax() {
        double raw = getConfig().getDouble("economy.tax-rate", 0.05);
        double clamped = PriceUtil.clampTaxRate(raw);
        if (raw >= 1) {
            getLogger().warning("税率必须小于 1，已修正为 " + PriceUtil.format(clamped) + "。");
        }
        getConfig().set("economy.tax-rate", clamped);
        saveConfig();
    }

    // ---------------- 配置访问 ----------------

    public double getTaxRate() {
        return getConfig().getDouble("economy.tax-rate", 0.05);
    }

    public int getExpireDays() {
        return Math.max(0, getConfig().getInt("market.expire-days", 7));
    }

    public int getMaxListings() {
        return Math.max(1, getConfig().getInt("market.max-listings-per-player", 20));
    }

    public String getCurrencyName() {
        return getConfig().getString("economy.currency-name", "货币");
    }

    public String getGuiTitle(String key) {
        return getConfig().getString("gui." + key, "&8全球市场");
    }

    public String getMessage(String key) {
        return getConfig().getString("messages." + key, MESSAGE_DEFAULTS.getOrDefault(key, ""));
    }

    /** 格式化金额（纯数字 + 货币名）。 */
    public String money(double amount) {
        return PriceUtil.format(amount) + " " + getCurrencyName();
    }

    // ---------------- 服务 ----------------

    public EconomyService getEconomyService() {
        return economyService;
    }

    public MarketService getMarketService() {
        return marketService;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }
}


