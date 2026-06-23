package ru.voidrp.gamesync.command;

import java.io.IOException;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.service.BackendClient.NationTreasurySummaryResponse;

public final class PlayerNationTreasuryCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerNationTreasuryCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }

        player.sendMessage("§7Запрашиваем treasury summary...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationTreasurySummaryResponse summary = plugin.getBackendClient().getNationTreasurySummary(nation.slug());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§6=== Казна государства: §f" + nation.title() + " §6===");
                    player.sendMessage("§7Баланс: §a" + round2(summary.treasury_balance));
                    player.sendMessage("§7Территория: §f" + summary.territory_points);
                    player.sendMessage("§7Престиж: §f" + summary.prestige_score);
                });
            } catch (IOException | InterruptedException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось получить данные treasury.");
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            }
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}