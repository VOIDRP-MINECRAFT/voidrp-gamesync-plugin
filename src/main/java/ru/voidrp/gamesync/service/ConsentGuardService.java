package ru.voidrp.gamesync.service;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Applies the players' personal-data choices in game.
 *
 * <p>Live positions on the public BlueMap are personal data made public, so a player is shown there
 * only after allowing it on the site (consent to distribution, 152-FZ art. 10.1). Everyone else is
 * hidden through the BlueMap API. Until the list of allowed players is loaded, everyone stays hidden.
 *
 * <p>On join, players who have not accepted the current offer and personal data consent get a chat
 * reminder; the backend adds a HUD notification (at most once per 72 hours).
 */
public final class ConsentGuardService implements Listener {

    private final VoidRpGameSyncPlugin plugin;
    private volatile Set<String> mapVisible = Set.of();
    private volatile boolean bluemapWarned;

    public ConsentGuardService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshMapVisibility, 20L * 10, 20L * 60);
    }

    private void refreshMapVisibility() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Set<String> fresh = new HashSet<>(plugin.getBackendClient().fetchMapVisibleNicknames());
                mapVisible = fresh;
            } catch (Exception e) {
                plugin.getLogger().warning("[Consent] could not load map visibility list: " + e.getMessage());
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) applyMapVisibility(p);
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        applyMapVisibility(player);
        String nick = player.getName();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                if (!plugin.getBackendClient().remindMissingConsents(nick)) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    player.sendMessage("§6⚠ §fМы обновили договор оферты и политику конфиденциальности.");
                    player.sendMessage("§7Зайди на §dvoid-rp.ru §7в личный кабинет и подтверди согласие.");
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[Consent] reminder check failed for " + nick + ": " + e.getMessage());
            }
        }, 20L * 15);
    }

    private void applyMapVisibility(Player player) {
        boolean visible = mapVisible.contains(player.getName().toLowerCase(Locale.ROOT));
        setBlueMapVisibility(player.getUniqueId(), visible);
    }

    /** BlueMap is a NeoForge mod here, so its API is reached reflectively through whichever loader sees it. */
    private void setBlueMapVisibility(UUID uuid, boolean visible) {
        try {
            Class<?> apiClass = findClass("de.bluecolored.bluemap.api.BlueMapAPI");
            if (apiClass == null) return;
            Object instance = ((Optional<?>) apiClass.getMethod("getInstance").invoke(null)).orElse(null);
            if (instance == null) return;
            Object webApp = apiClass.getMethod("getWebApp").invoke(instance);
            Method set = webApp.getClass().getMethod("setPlayerVisibility", UUID.class, boolean.class);
            set.setAccessible(true);
            set.invoke(webApp, uuid, visible);
        } catch (Exception e) {
            if (!bluemapWarned) {
                bluemapWarned = true;
                plugin.getLogger().warning("[Consent] BlueMap visibility unavailable: " + e);
            }
        }
    }

    private Class<?> findClass(String name) {
        for (ClassLoader loader : new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()}) {
            if (loader == null) continue;
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException ignored) {
                // try the next loader
            }
        }
        if (!bluemapWarned) {
            bluemapWarned = true;
            plugin.getLogger().warning("[Consent] BlueMap API class not found; live map positions are not filtered");
        }
        return null;
    }
}
