package ru.voidrp.gamesync.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.store.PluginDataStore;

/**
 * Rotating weekly challenges. A fixed pool; 3 are active each ISO week (deterministic by
 * week number, same for everyone). Progress is measured from a per-player baseline snapshot
 * of the plugin's persistent counters, taken at the player's first check of a new week.
 * Completing one grants a coin reward + notification (once per week). The current state is
 * pushed to the backend for display in the game-ui home.
 */
public final class WeeklyChallengeService {

    private record Challenge(String key, String title, String metric, int goal, int reward) {}

    private static final List<Challenge> POOL = List.of(
        new Challenge("mobs100", "Убей 100 мобов", "mobkills", 100, 3000),
        new Challenge("mobs300", "Убей 300 мобов", "mobkills", 300, 6000),
        new Challenge("mine500", "Добудь 500 блоков", "blocks_broken", 500, 3000),
        new Challenge("mine2000", "Добудь 2000 блоков", "blocks_broken", 2000, 6000),
        new Challenge("place300", "Поставь 300 блоков", "blocks_placed", 300, 2500),
        new Challenge("play180", "Наиграй 3 часа", "playtime_minutes", 180, 3000),
        new Challenge("play360", "Наиграй 6 часов", "playtime_minutes", 360, 6000),
        new Challenge("pvp5", "Убей 5 игроков", "kills", 5, 4000)
    );
    private static final int ACTIVE = 3;

    private final VoidRpGameSyncPlugin plugin;

    public WeeklyChallengeService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    private static String weekId(LocalDate d) {
        WeekFields wf = WeekFields.ISO;
        int week = d.get(wf.weekOfWeekBasedYear());
        int year = d.get(wf.weekBasedYear());
        return String.format("%d-W%02d", year, week);
    }

    private List<Challenge> activeFor(LocalDate d) {
        WeekFields wf = WeekFields.ISO;
        int idx = d.get(wf.weekBasedYear()) * 53 + d.get(wf.weekOfWeekBasedYear());
        int offset = Math.floorMod(idx * ACTIVE, POOL.size());
        // Walk the pool from a week-derived offset, taking distinct metrics so a week
        // never lands 3 challenges of the same kind.
        List<Challenge> out = new ArrayList<>(ACTIVE);
        java.util.Set<String> metrics = new java.util.HashSet<>();
        for (int i = 0; i < POOL.size() && out.size() < ACTIVE; i++) {
            Challenge c = POOL.get((offset + i) % POOL.size());
            if (metrics.add(c.metric())) {
                out.add(c);
            }
        }
        return out;
    }

    private long metric(UUID uuid, String metric) {
        PluginDataStore s = plugin.getDataStore();
        return switch (metric) {
            case "kills" -> s.getStatCounter(uuid, "kills");
            case "mobkills" -> s.getStatCounter(uuid, "mobkills");
            case "blocks_broken" -> s.getBlocksBroken(uuid);
            case "blocks_placed" -> s.getBlocksPlaced(uuid);
            case "playtime_minutes" -> s.getStatCounter(uuid, "playtime_seconds") / 60L;
            default -> 0L;
        };
    }

    public void checkAllOnline() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String week = weekId(today);
        List<Challenge> active = activeFor(today);
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                checkPlayer(player, week, active);
            } catch (Exception ignored) {
                // one player's transient failure must not abort the rest of the tick
            }
        }
    }

    private void checkPlayer(Player player, String week, List<Challenge> active) {
        UUID uuid = player.getUniqueId();
        PluginDataStore store = plugin.getDataStore();

        if (!week.equals(store.getWeeklyWeek(uuid))) {
            store.resetWeekly(uuid, week);
        }
        // Lazily snapshot each active metric's baseline (also covers a mid-week pool change).
        for (Challenge c : active) {
            store.ensureWeeklyBaseline(uuid, c.metric(), metric(uuid, c.metric()));
        }

        List<Map<String, Object>> state = new ArrayList<>();
        for (Challenge c : active) {
            long progress = Math.max(0, metric(uuid, c.metric()) - store.getWeeklyBaseline(uuid, c.metric()));
            boolean done = store.isWeeklyDone(uuid, c.key()) || progress >= c.goal();
            if (progress >= c.goal() && !store.isWeeklyDone(uuid, c.key())) {
                store.setWeeklyDone(uuid, c.key());
                grantReward(player, c.reward());
                notify(player.getName(), c.title(), c.reward());
            }
            Map<String, Object> item = new HashMap<>();
            item.put("key", c.key());
            item.put("title", c.title());
            item.put("goal", c.goal());
            item.put("progress", (int) Math.min(progress, c.goal()));
            item.put("reward", c.reward());
            item.put("done", done);
            state.add(item);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("minecraft_nickname", player.getName());
        payload.put("week_id", week);
        payload.put("challenges", state);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushWeeklyChallenges(payload);
            } catch (Exception ignored) {
                // best-effort display push
            }
        });
    }

    private void grantReward(Player player, double coins) {
        if (coins <= 0 || plugin.getEconomy() == null) {
            return;
        }
        try {
            plugin.getEconomy().depositPlayer(player, coins);
        } catch (Exception ex) {
            plugin.getLogger().warning("Weekly reward deposit failed for " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void notify(String nickname, String title, double coins) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("minecraft_nickname", nickname);
        payload.put("type", "weekly_challenge");
        payload.put("title", "Челлендж недели выполнен!");
        payload.put("body", title + " · +" + (long) coins + " монет");
        payload.put("icon", "quest");
        payload.put("accent", "#34d399");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushNotification(payload);
            } catch (Exception ignored) {
                // best-effort
            }
        });
    }
}
