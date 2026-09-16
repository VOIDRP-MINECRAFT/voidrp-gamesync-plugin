package ru.voidrp.gamesync.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * {@code /гайд} — reopen the newcomer guide. Without the WebGUI mod (or with WebGUI disabled)
 * the same steps are printed to chat, so the command always helps.
 */
public final class GuideCommand implements CommandExecutor {

    private final VoidRpGameSyncPlugin plugin;

    public GuideCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    /** Cache-busted like the menu/HUD: CEF would otherwise keep serving a stale build. */
    public static String welcomeUrl(VoidRpGameSyncPlugin plugin) {
        String url = plugin.getGameSyncConfig().getWebGuiWelcomeUrl();
        return url + (url.contains("?") ? "&" : "?") + "v=" + (System.currentTimeMillis() / 1000L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        if (plugin.getGameSyncConfig().isWebGuiEnabled()) {
            plugin.getWebGuiBridgeService().openGui(player, welcomeUrl(plugin));
        }
        // Short version in chat as well: works without the WebGUI mod and stays in the chat log.
        player.sendMessage("§d§l✦ Гайд новичка");
        player.sendMessage("§7• §fТемно: ставь факелы, еда и свет — в стартовом наборе.");
        player.sendMessage("§7• §d/rtp §f— место для базы, §d/sethome §fи §d/home §f— точки дома, §d/spawn §f— на спавн.");
        player.sendMessage("§7• §d/ftbteams party create §fи §d/ftbteams party invite §f— своя команда.");
        player.sendMessage("§7• §fПриват: карта на §dM§f, выдели чанки левой кнопкой мыши.");
        player.sendMessage("§7• §d/bp §f— батл пасс, §dF6 §f— меню сервера.");
        return true;
    }
}
