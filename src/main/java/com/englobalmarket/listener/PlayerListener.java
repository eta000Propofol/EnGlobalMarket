package com.englobalmarket.listener;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 玩家事件监听：上线时自动投递收货箱中的物品。
 */
public class PlayerListener implements Listener {

    private final EnGlobalMarket plugin;

    public PlayerListener(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int pending = plugin.getMarketService().countClaims(player.getUniqueId());
        if (pending <= 0) {
            return;
        }
        int delivered = plugin.getMarketService().deliverClaims(player);
        if (delivered > 0) {
            player.sendMessage(Text.color(plugin.getMessage("claim-delivered")
                    .replace("%count%", String.valueOf(delivered))));
        }
        int left = plugin.getMarketService().countClaims(player.getUniqueId());
        if (left > 0) {
            player.sendMessage(Text.color(plugin.getMessage("claim-pending")
                    .replace("%count%", String.valueOf(left))));
        }
    }
}
