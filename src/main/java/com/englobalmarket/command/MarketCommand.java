package com.englobalmarket.command;

import com.englobalmarket.EnGlobalMarket;
import com.englobalmarket.gui.MarketGui;
import com.englobalmarket.model.PendingOp;
import com.englobalmarket.service.MarketService;
import com.englobalmarket.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /market：打开全球市场。
 * 管理员：/market reload 重载配置，/market pending 查看未结清流水，/market pending retry 立即重试恢复。
 */
public class MarketCommand implements CommandExecutor, TabCompleter {

    private final EnGlobalMarket plugin;

    public MarketCommand(EnGlobalMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!requireAdmin(sender)) {
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(Text.color(plugin.getMessage("reload-success")));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("pending")) {
            if (!requireAdmin(sender)) {
                return true;
            }
            boolean retry = args.length > 1 && args[1].equalsIgnoreCase("retry");
            showPending(sender, retry);
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

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("englobalmarket.admin")) {
            return true;
        }
        sender.sendMessage(Text.color(plugin.getMessage("no-permission")));
        return false;
    }

    private void showPending(CommandSender sender, boolean retryFirst) {
        MarketService service = plugin.getMarketService();
        if (retryFirst) {
            MarketService.RecoveryReport report = service.recoverPending();
            sender.sendMessage(Text.color("&7恢复结果：完成 " + report.resolved() + " 条，" + "补发 "
                    + report.delivered() + " 条，待重试 " + report.retry() + " 条，需人工核对 "
                    + report.needsManual() + " 条。"));
        }
        List<PendingOp> ops = service.listPending();
        if (ops.isEmpty()) {
            sender.sendMessage(Text.color(plugin.getMessage("pending-none")));
            return;
        }
        long manual = ops.stream().filter(op -> op.stage().needsManualReview()).count();
        sender.sendMessage(Text.color(plugin.getMessage("pending-header")
                .replace("%count%", String.valueOf(ops.size()))
                .replace("%manual%", String.valueOf(manual))));
        for (PendingOp op : ops) {
            sender.sendMessage(Text.color(plugin.getMessage("pending-entry")
                    .replace("%id%", String.valueOf(op.id()))
                    .replace("%kind%", op.kind().name())
                    .replace("%stage%", op.stage().name())
                    .replace("%player%", String.valueOf(op.playerUuid()))
                    .replace("%amount%", op.amount() == null ? "-" : op.amount().toPlainString())
                    .replace("%note%", op.note() == null ? "-" : op.note())));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("englobalmarket.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("reload", "pending");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pending")) {
            return List.of("retry");
        }
        return List.of();
    }
}
