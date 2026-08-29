package ru.voidrp.gamesync.listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.store.PluginDataStore;

/**
 * Tracks kills / mob-kills / deaths / playtime per player and persists them in
 * {@link PluginDataStore}. Vanilla stats do NOT persist on this server (the stats file is
 * username-keyed and empty, so every {@code getStatistic} read is session-only), so these
 * plugin-side counters are the reliable source. Keys: {@code kills}, {@code mobkills},
 * {@code deaths}, {@code playtime_seconds}.
 */
public final class CombatPlaytimeStatsListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;
    // player UUID -> epoch-ms of the last playtime checkpoint (join or last flush)
    private final Map<UUID, Long> checkpoints = new ConcurrentHashMap<>();
    // player UUID -> current PvP kill streak (transient; best is persisted)
    private final Map<UUID, Integer> killStreaks = new ConcurrentHashMap<>();

    public CombatPlaytimeStatsListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        // Seed checkpoints for players already online (e.g. after a plugin hot-reload).
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            checkpoints.put(online.getUniqueId(), now);
        }
        // Flush accrued playtime every 60s so a crash loses at most a minute.
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushAll, 1200L, 1200L);
    }

    private PluginDataStore store() {
        return plugin.getDataStore();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        checkpoints.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flush(event.getPlayer().getUniqueId());
        checkpoints.remove(event.getPlayer().getUniqueId());
        killStreaks.remove(event.getPlayer().getUniqueId());
    }

    private void flushAll() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            flush(online.getUniqueId());
        }
    }

    private void flush(UUID uuid) {
        Long start = checkpoints.get(uuid);
        long now = System.currentTimeMillis();
        if (start == null) {
            checkpoints.put(uuid, now);
            return;
        }
        long seconds = (now - start) / 1000L;
        if (seconds > 0) {
            store().addStatCounter(uuid, "playtime_seconds", seconds);
            checkpoints.put(uuid, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        store().addStatCounter(victim.getUniqueId(), "deaths", 1L);
        killStreaks.put(victim.getUniqueId(), 0); // death ends the streak

        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            UUID kid = killer.getUniqueId();
            store().addStatCounter(kid, "kills", 1L);
            int streak = killStreaks.merge(kid, 1, Integer::sum);
            long best = store().getStatCounter(kid, "best_kill_streak");
            if (streak > best) {
                store().addStatCounter(kid, "best_kill_streak", streak - best); // raise best to streak
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        // PlayerDeathEvent is a subclass and handled above; count only non-player mobs here.
        if (event.getEntity() instanceof Player) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            store().addStatCounter(killer.getUniqueId(), "mobkills", 1L);
        }
    }
}
