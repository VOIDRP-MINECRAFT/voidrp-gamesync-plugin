package ru.voidrp.gamesync.command;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

public final class PlayerMarketShopCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerMarketShopCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        if (plugin.getGameSyncConfig().isWebGuiEnabled()) {
            plugin.getWebGuiBridgeService().openMarket(player);
        } else {
            plugin.getPlayerMarketGuiService().open(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
