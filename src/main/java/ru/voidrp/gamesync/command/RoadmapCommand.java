package ru.voidrp.gamesync.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * {@code /путь} — open the progression roadmap: which stage of the pack the player is on and
 * the next item to get. Progress comes from items the plugin has seen in the inventory.
 */
public final class RoadmapCommand implements CommandExecutor {

    private final VoidRpGameSyncPlugin plugin;

    public RoadmapCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public static String roadmapUrl(VoidRpGameSyncPlugin plugin) {
        String url = plugin.getGameSyncConfig().getWebGuiRoadmapUrl();
        return url + (url.contains("?") ? "&" : "?") + "v=" + (System.currentTimeMillis() / 1000L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        if (!plugin.getGameSyncConfig().isWebGuiEnabled()) {
            player.sendMessage("§7Путеводитель открывается в игровом меню (нужен мод WebGUI из лаунчера).");
            player.sendMessage("§7Короткий путь: §fжелезо → алмазы → механизмы → сталь → энергия → автоматизация → индустрия → квант → Пирамида§7. Подробно — в книге квестов FTB.");
            return true;
        }
        // Same chat-close race as /гайд: open a little later so the chat screen doesn't take it down.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.getWebGuiBridgeService().openGui(player, roadmapUrl(plugin));
        }, 10L);
        player.sendMessage("§d✦ §fПутеводитель: где ты сейчас и что делать дальше. Галочки ставятся сами, когда нужный предмет побывает у тебя в инвентаре.");
        return true;
    }
}
