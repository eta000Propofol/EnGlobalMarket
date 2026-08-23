package com.englobalmarket.command;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.gui.MarketGui;
import com.englobalmarket.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /market 命令：打开全球市场；/market reload 重载配置。
 */
public class MarketCommand implements CommandExecutor, TabCompleter {

    private final EnGlobalMarket plugin;

    public MarketCommand(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("englobalmarket.admin")) {
                sender.sendMessage(Text.color(plugin.getMessage("no-permission")));
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(Text.color(plugin.getMessage("reload-success")));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color(plugin.getMessage("not-player")));
            return true;
        }
        if (!player.hasPermission("englobalmarket.use")) {
            player.sendMessage(Text.color(plugin.getMessage("no-permission")));
            return true;
        }
        plugin.getGuiManager().open(player, new MarketGui(plugin, player));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("englobalmarket.admin")) {
            return List.of("reload");
        }
        return List.of();
    }
}
