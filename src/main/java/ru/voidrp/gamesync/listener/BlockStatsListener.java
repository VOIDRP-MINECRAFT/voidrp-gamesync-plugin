package ru.voidrp.gamesync.listener;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Counts blocks mined/placed per player via block events so the stat covers modded
 * blocks too. Bukkit's {@code Statistic.MINE_BLOCK}/{@code USE_ITEM} only iterate vanilla
 * {@code Material} values, so on the modded pack most mining/placing was never counted.
 * Counters live in {@link ru.voidrp.gamesync.store.PluginDataStore} and feed the player
 * stat snapshot ({@code blocks_broken}/{@code blocks_placed}).
 */
public final class BlockStatsListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public BlockStatsListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        plugin.getDataStore().addBlockBroken(player.getUniqueId(), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        plugin.getDataStore().addBlockPlaced(player.getUniqueId(), 1L);
    }
}
