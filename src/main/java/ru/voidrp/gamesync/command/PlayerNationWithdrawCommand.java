package ru.voidrp.gamesync.command;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.milkbowl.vault.economy.EconomyResponse;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.service.BackendApiException;
import ru.voidrp.gamesync.model.GameNationTreasuryWithdrawRequest;
import ru.voidrp.gamesync.model.NationDefinition;

public final class PlayerNationWithdrawCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerNationWithdrawCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }

        if (!player.hasPermission("voidrp.gamesync.treasury.manage")) {
            player.sendMessage("§cУ тебя нет прав на управление казной.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§eИспользование: /" + label + " <amount> [comment]");
            return true;
        }

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }

        String role = nation.roleFor(player.getName());
        if (role == null || (!role.equalsIgnoreCase("leader") && !role.equalsIgnoreCase("officer"))) {
            player.sendMessage("§cСнимать деньги из казны могут только глава и офицеры.");
            return true;
        }

        if (plugin.getEconomy() == null) {
            player.sendMessage("§cЭкономика Vault недоступна.");
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

        String comment = null;
        if (args.length >= 2) {
            comment = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            if (comment.isBlank()) {
                comment = null;
            }
            if (comment != null && comment.length() > 500) {
                comment = comment.substring(0, 500);
            }
        }

        final double finalAmount = amount;
        final String finalComment = comment;

        player.sendMessage("§7Списание из казны отправляется...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            EconomyResponse deposit = plugin.getEconomy().depositPlayer(player, finalAmount);
            if (deposit == null || !deposit.transactionSuccess()) {
                Bukkit.getScheduler().runTask(plugin, ()
                        -> player.sendMessage("§cНе удалось выдать деньги игроку из экономики Vault."));
                return;
            }

            try {
                plugin.getBackendClient().withdrawFromNationTreasury(
                        new GameNationTreasuryWithdrawRequest(
                                nation.slug(),
                                finalAmount,
                                player.getName(),
                                finalComment
                        )
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§aИз казны государства §f" + nation.title() + " §aвыдано §f" + finalAmount);
                    if (finalComment != null) {
                        player.sendMessage("§7Комментарий: §f" + finalComment);
                    }
                });
            } catch (BackendApiException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse rollback = plugin.getEconomy().withdrawPlayer(player, finalAmount);
                    if (rollback == null || !rollback.transactionSuccess()) {
                        player.sendMessage("§c" + exception.getMessage());
                        player.sendMessage("§cОткат баланса не удался. Сумма: §f" + finalAmount);
                    } else {
                        player.sendMessage("§c" + exception.getMessage());
                    }
                });
            } catch (IOException | InterruptedException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse rollback = plugin.getEconomy().withdrawPlayer(player, finalAmount);
                    if (rollback == null || !rollback.transactionSuccess()) {
                        player.sendMessage("§cBackend не подтвердил списание, и откат по балансу игрока выполнить не удалось.");
                        player.sendMessage("§cНужно проверить treasury и баланс вручную. Сумма: §f" + finalAmount);
                    } else {
                        player.sendMessage("§cBackend не подтвердил списание. Деньги игрока откатены.");
                    }
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    EconomyResponse rollback = plugin.getEconomy().withdrawPlayer(player, finalAmount);
                    if (rollback == null || !rollback.transactionSuccess()) {
                        player.sendMessage("§cПроизошла ошибка, и откат по балансу игрока выполнить не удалось.");
                        player.sendMessage("§cНужно проверить treasury и баланс вручную. Сумма: §f" + finalAmount);
                    } else {
                        player.sendMessage("§cПроизошла ошибка. Деньги игрока откатены.");
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
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
