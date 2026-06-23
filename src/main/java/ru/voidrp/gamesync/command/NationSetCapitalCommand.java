package ru.voidrp.gamesync.command;

import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;

public final class NationSetCapitalCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public NationSetCapitalCommand(VoidRpGameSyncPlugin plugin) {
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

        if (!"leader".equals(nation.roleFor(player.getName()))) {
            player.sendMessage("§cТолько лидер государства может менять столицу.");
            return true;
        }

        int x = player.getLocation().getBlockX();
        int z = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();

        player.sendMessage("§7Устанавливаю столицу §f" + nation.title() + " §7...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().setNationCapital(nation.slug(), x, z, world);

                Bukkit.getScheduler().runTask(plugin, () ->
                    player.sendMessage("§aСтолица государства §f" + nation.title()
                        + " §aустановлена в §f" + world + " §a(" + x + ", " + z + ").")
                );
            } catch (IOException | InterruptedException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось установить столицу. Попробуй позже.");
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + exception.getMessage());
                    }
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cПроизошла ошибка при установке столицы.");
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
}
