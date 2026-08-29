package ru.voidrp.gamesync.service;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.store.PluginDataStore;

/**
 * Grants a one-time coin reward + notification when a player crosses an achievement
 * threshold. Mirrors the display catalogue in the backend ({@code game_ui_home.py}); the
 * metrics come from the persistent plugin counters, Vault balance and the nation registry.
 * Checked on join and on a periodic tick for online players. Awards are tracked once in
 * {@code data.yml} so a reward never repeats.
 */
public final class AchievementRewardService {

    private record Ach(String key, String title, String metric, long goal, double reward) {}

    private static final List<Ach> CATALOG = List.of(
        new Ach("citizen", "Гражданин", "in_nation", 1, 500),
        new Ach("first_blood", "Первая кровь", "kills", 1, 500),
        new Ach("warrior", "Воин", "kills", 25, 2000),
        new Ach("streak5", "На волне", "best_kill_streak", 5, 1000),
        new Ach("streak10", "Неудержимый", "best_kill_streak", 10, 3000),
        new Ach("hunter", "Охотник", "mobkills", 100, 1500),
        new Ach("slayer", "Истребитель", "mobkills", 500, 5000),
        new Ach("miner", "Шахтёр", "blocks_broken", 1000, 2000),
        new Ach("builder", "Строитель", "blocks_placed", 1000, 2000),
        new Ach("veteran", "Ветеран", "playtime_minutes", 600, 3000),
        new Ach("tycoon", "Магнат", "balance", 100000, 5000)
    );

    private final VoidRpGameSyncPlugin plugin;

    public AchievementRewardService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    /** Runs a check for every online player. Call on a periodic (main-thread) task. */
    public void checkAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            checkPlayer(player);
        }
    }

    /** Main-thread: award any newly-earned achievements for this player. */
    public void checkPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        PluginDataStore store = plugin.getDataStore();
        for (Ach a : CATALOG) {
            if (store.isAchievementAwarded(uuid, a.key())) {
                continue;
            }
            if (metric(player, uuid, a.metric()) < a.goal()) {
                continue;
            }
            store.setAchievementAwarded(uuid, a.key());
            grantReward(player, a.reward());
            notify(player.getName(), a.title(), a.reward());
        }
    }

    private long metric(Player player, UUID uuid, String metric) {
        PluginDataStore store = plugin.getDataStore();
        return switch (metric) {
            case "in_nation" -> plugin.getNationRegistry().findByPlayer(player.getName()) != null ? 1L : 0L;
            case "kills" -> store.getStatCounter(uuid, "kills");
            case "mobkills" -> store.getStatCounter(uuid, "mobkills");
            case "best_kill_streak" -> store.getStatCounter(uuid, "best_kill_streak");
            case "blocks_broken" -> store.getBlocksBroken(uuid);
            case "blocks_placed" -> store.getBlocksPlaced(uuid);
            case "playtime_minutes" -> store.getStatCounter(uuid, "playtime_seconds") / 60L;
            case "balance" -> balanceOf(player);
            default -> 0L;
        };
    }

    private long balanceOf(Player player) {
        try {
            return plugin.getEconomy() == null ? 0L : (long) plugin.getEconomy().getBalance(player);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void grantReward(Player player, double coins) {
        if (coins <= 0 || plugin.getEconomy() == null) {
            return;
        }
        try {
            plugin.getEconomy().depositPlayer(player, coins);
        } catch (Exception exception) {
            plugin.getLogger().warning("Achievement reward deposit failed for " + player.getName() + ": " + exception.getMessage());
        }
    }

    private void notify(String nickname, String title, double coins) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("minecraft_nickname", nickname);
        payload.put("type", "achievement");
        payload.put("title", "Достижение: " + title);
        payload.put("body", "Награда: +" + (long) coins + " монет");
        payload.put("icon", "trophy");
        payload.put("accent", "#fbbf24");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushNotification(payload);
            } catch (Exception ignored) {
                // best-effort
            }
        });
    }
}
