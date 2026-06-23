package ru.voidrp.gamesync.listener;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

public final class TabListListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;
    private LuckPerms luckPerms;

    public TabListListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        try {
            this.luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("[TabList] LuckPerms not available, prefixes will be empty");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Delay 5 ticks so LuckPerms has time to load user data
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            updateTabEntry(event.getPlayer());
        }, 5L);
    }

    public void updateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            updateTabEntry(player);
        }
    }

    public void updateTabEntry(Player player) {
        String prefix = getPrefix(player);
        player.setPlayerListName(prefix + "§f" + player.getName() + "§r");
    }

    private String getPrefix(Player player) {
        if (luckPerms == null) return "";
        try {
            User user = luckPerms.getUserManager().getUser(player.getUniqueId());
            if (user == null) return "";
            String raw = user.getCachedData().getMetaData().getPrefix();
            if (raw == null || raw.isEmpty()) return "";
            return ChatColor.translateAlternateColorCodes('&', raw);
        } catch (Exception e) {
            return "";
        }
    }
}
