package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

public final class AllianceCacheService {

    private final JavaPlugin plugin;
    private final BackendClient backendClient;
    private final ScheduledExecutorService scheduler;

    private volatile Map<String, String> pvpMap = Collections.emptyMap(); // nation_slug → alliance_slug

    public AllianceCacheService(JavaPlugin plugin, BackendClient backendClient) {
        this.plugin = plugin;
        this.backendClient = backendClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alliance-cache-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::refresh, 0, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public boolean isSameAllianceWithPvpProtection(String nationSlug1, String nationSlug2) {
        if (nationSlug1 == null || nationSlug2 == null) return false;
        String a1 = pvpMap.get(nationSlug1);
        String a2 = pvpMap.get(nationSlug2);
        return a1 != null && a1.equals(a2);
    }

    private void refresh() {
        try {
            Map<String, String> updated = backendClient.fetchAlliancePvpMap();
            pvpMap = updated != null ? updated : Collections.emptyMap();
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[AllianceCache] PvP map refresh failed: " + e.getMessage());
        }
    }
}
