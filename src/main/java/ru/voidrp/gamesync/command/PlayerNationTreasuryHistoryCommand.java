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
import ru.voidrp.gamesync.service.BackendClient.NationTreasuryTransactionItem;
import ru.voidrp.gamesync.service.BackendClient.NationTreasuryTransactionListResponse;

public final class PlayerNationTreasuryHistoryCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerNationTreasuryHistoryCommand(VoidRpGameSyncPlugin plugin) {
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

        player.sendMessage("§7Запрашиваем историю казны...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationTreasuryTransactionListResponse history = plugin.getBackendClient().getNationTreasuryTransactions(nation.slug());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§6=== История казны: §f" + nation.title() + " §6===");
                    if (history == null || history.items == null || history.items.isEmpty()) {
                        player.sendMessage("§eОпераций пока нет.");
                        return;
                    }
                    int limit = Math.min(5, history.items.size());
                    for (int i = 0; i < limit; i++) {
                        NationTreasuryTransactionItem item = history.items.get(i);
                        player.sendMessage("§8- §f" + label(item.transaction_type) + " §8| §a" + round2(item.net_amount));
                        if (item.comment != null && !item.comment.isBlank()) {
                            player.sendMessage("  §7" + item.comment);
                        }
                    }
                });
            } catch (IOException | InterruptedException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось получить историю treasury.");
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            }
        });

        return true;
    }

    private String label(String type) {
        if (type == null) return "Операция";
        return switch (type.toLowerCase()) {
            case "player_donation" -> "Донат игрока";
            case "deposit" -> "Пополнение";
            case "withdraw" -> "Списание";
            case "alliance_transfer_out" -> "Перевод союзнику";
            case "alliance_transfer_in" -> "Перевод от союзника";
            case "alliance_fee_income" -> "Комиссия альянса";
            default -> type;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}