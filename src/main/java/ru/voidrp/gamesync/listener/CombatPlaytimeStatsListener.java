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
    // player UUID -> seconds accrued but not yet pushed to the backend daily bucket
    private final Map<UUID, Long> pendingPlaytime = new ConcurrentHashMap<>();
    private static final long PLAYTIME_PUSH_THRESHOLD_SECONDS = 600L; // batch pushes to ~10 min

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
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        flush(uuid);
        pushPendingPlaytime(uuid, name); // flush whatever is left to the daily bucket
        checkpoints.remove(uuid);
        killStreaks.remove(uuid);
        pendingPlaytime.remove(uuid);
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
            long pending = pendingPlaytime.merge(uuid, seconds, Long::sum);
            if (pending >= PLAYTIME_PUSH_THRESHOLD_SECONDS) {
                org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(uuid);
                if (p != null) {
                    pushPendingPlaytime(uuid, p.getName());
                }
            }
        }
    }

    private void pushPendingPlaytime(UUID uuid, String name) {
        Long pending = pendingPlaytime.get(uuid);
        if (pending == null || pending <= 0) {
            return;
        }
        pendingPlaytime.put(uuid, 0L);
        // Server-local day so the activity chart lines up with the login-streak day boundary.
        String day = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushPlaytime(name, pending, day);
            } catch (Exception ignored) {
                // best-effort; re-queue so the seconds are not lost on transient failure
                pendingPlaytime.merge(uuid, pending, Long::sum);
            }
        });
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
            if (streak >= 5 && streak % 5 == 0) {
                notifyKillStreak(killer.getName(), streak);
            }
        }
    }

    // Fire a HUD toast / notification-center card when a player hits a 5-kill milestone.
    private void notifyKillStreak(String nickname, int streak) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("minecraft_nickname", nickname);
        payload.put("type", "kill_streak");
        payload.put("title", "Серия из " + streak + " убийств!");
        payload.put("body", "Ты в ударе — не останавливайся.");
        payload.put("icon", streak >= 15 ? "crown" : "shield");
        payload.put("accent", streak >= 15 ? "#f59e0b" : "#fb7185");
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushNotification(payload);
            } catch (Exception ignored) {
                // best-effort: a missed streak toast is not worth logging spam
            }
        });
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
