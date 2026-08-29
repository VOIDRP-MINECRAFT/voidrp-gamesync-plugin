package ru.voidrp.gamesync.listener;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.store.PluginDataStore;

/**
 * Tracks consecutive-day login streaks and rewards milestones (3 / 7 / 14 / 30 days).
 * On join: same day = no change; yesterday = streak+1; anything else = streak resets to 1.
 * Reward (Vault coins + notification) fires once when the streak first reaches a milestone.
 */
public final class LoginStreakListener implements Listener {

    private static final Map<Integer, Double> MILESTONES = Map.of(
        3, 1000.0, 7, 3000.0, 14, 7000.0, 30, 20000.0
    );

    private final VoidRpGameSyncPlugin plugin;

    public LoginStreakListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PluginDataStore store = plugin.getDataStore();

        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        String lastIso = store.getLoginStreakDay(uuid);
        int streak = store.getLoginStreakCount(uuid);

        if (lastIso != null) {
            LocalDate last;
            try {
                last = LocalDate.parse(lastIso);
            } catch (Exception ex) {
                last = null;
            }
            if (last != null && last.equals(today)) {
                return; // already counted today
            }
            streak = (last != null && last.plusDays(1).equals(today)) ? streak + 1 : 1;
        } else {
            streak = 1;
        }

        store.setLoginStreak(uuid, today.toString(), streak);

        Double reward = MILESTONES.get(streak);
        if (reward != null) {
            grant(player, reward);
            notify(player.getName(), streak, reward);
        }
    }

    private void grant(Player player, double coins) {
        if (plugin.getEconomy() == null) {
            return;
        }
        try {
            plugin.getEconomy().depositPlayer(player, coins);
        } catch (Exception ex) {
            plugin.getLogger().warning("Streak reward deposit failed for " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void notify(String nickname, int streak, double coins) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("minecraft_nickname", nickname);
        payload.put("type", "login_streak");
        payload.put("title", streak + " дней подряд!");
        payload.put("body", "Награда за серию входов: +" + (long) coins + " монет");
        payload.put("icon", "star");
        payload.put("accent", "#8b7bff");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushNotification(payload);
            } catch (Exception ignored) {
                // best-effort
            }
        });
    }
}
