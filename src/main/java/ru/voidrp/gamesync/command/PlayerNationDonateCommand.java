package ru.voidrp.gamesync.command;

import java.io.IOException;

import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.GameNationDonationRequest;
import ru.voidrp.gamesync.model.NationDefinition;

public final class PlayerNationDonateCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerNationDonateCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }

        if (!player.hasPermission("voidrp.gamesync.donate")) {
            player.sendMessage("§cУ тебя нет прав на донат в казну.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§eИспользование: /" + label + " <amount> [comment]");
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[0].replace(",", "."));
        } catch (NumberFormatException exception) {
            player.sendMessage("§cСумма должна быть числом.");
            return true;
        }

        if (amount <= 0D) {
            player.sendMessage("§cСумма должна быть больше 0.");
            return true;
        }

        amount = round2(amount);

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }

        if (plugin.getEconomy() == null) {
            player.sendMessage("§cЭкономика Vault недоступна.");
            return true;
        }

        double balance = plugin.getEconomy().getBalance(player);
        if (balance < amount) {
            player.sendMessage("§cНедостаточно средств. Твой баланс: §f" + round2(balance));
            return true;
        }

        String comment = null;
        if (args.length >= 2) {
            comment = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            if (comment.isBlank()) {
                comment = null;
            }
            if (comment != null && comment.length() > 500) {
                comment = comment.substring(0, 500);
            }
        }

        EconomyResponse withdrawResponse = plugin.getEconomy().withdrawPlayer(player, amount);
        if (withdrawResponse == null || !withdrawResponse.transactionSuccess()) {
            player.sendMessage("§cНе удалось списать деньги с твоего баланса.");
            return true;
        }

        final double finalAmount = amount;
        final String finalComment = comment;

        player.sendMessage("§7Донат в казну отправляется...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().donateToNationTreasury(
                    new GameNationDonationRequest(
                        nation.slug(),
                        finalAmount,
                        player.getName(),
                        finalComment
                    )
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§aКазна государства §f" + nation.title() + " §aпополнена на §f" + finalAmount);
                    if (finalComment != null) {
                        player.sendMessage("§7Комментарий: §f" + finalComment);
                    }
                });
            } catch (IOException | InterruptedException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse rollback = plugin.getEconomy().depositPlayer(player, finalAmount);
                    if (rollback == null || !rollback.transactionSuccess()) {
                        player.sendMessage("§cBackend не подтвердил донат, и вернуть деньги автоматически не удалось.");
                        player.sendMessage("§cНужно проверить баланс вручную. Сумма: §f" + finalAmount);
                    } else {
                        player.sendMessage("§cBackend не подтвердил донат. Деньги возвращены на твой баланс.");
                    }
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse rollback = plugin.getEconomy().depositPlayer(player, finalAmount);
                    if (rollback == null || !rollback.transactionSuccess()) {
                        player.sendMessage("§cПроизошла ошибка, и вернуть деньги автоматически не удалось.");
                        player.sendMessage("§cНужно проверить баланс вручную. Сумма: §f" + finalAmount);
                    } else {
                        player.sendMessage("§cПроизошла ошибка. Деньги возвращены на твой баланс.");
                    }
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            }
        });

        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return java.util.List.of();
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}