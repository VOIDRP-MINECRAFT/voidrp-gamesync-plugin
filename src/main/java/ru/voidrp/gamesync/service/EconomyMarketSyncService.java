package ru.voidrp.gamesync.service;

import org.bukkit.Bukkit;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.MarketPriceSnapshotResponse;

public final class EconomyMarketSyncService {
    private final VoidRpGameSyncPlugin plugin;
    private final EconomyMarketCache cache;
    private int taskId = -1;
    private int recalcTaskId = -1;
    private volatile boolean refreshRunning = false;
    private volatile boolean recalcRunning = false;
    private volatile long lastSuccessMs = 0L;
    private volatile long lastRecalcMs = 0L;
    private volatile String lastError = "";

    public EconomyMarketSyncService(VoidRpGameSyncPlugin plugin, EconomyMarketCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    public void start() {
        stop();
        if (!plugin.getGameSyncConfig().isEconomyMarketEnabled()) {
            return;
        }
        long period = Math.max(20L, plugin.getGameSyncConfig().getEconomyMarketSyncPeriodTicks());
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshQuietly, 20L, period).getTaskId();

        if (plugin.getGameSyncConfig().isEconomyMarketRecalculateEnabled()) {
            long recalcPeriod = Math.max(1200L, plugin.getGameSyncConfig().getEconomyMarketRecalculatePeriodTicks());
            // First run after ~1 min so a fresh boot records a snapshot promptly.
            recalcTaskId = Bukkit.getScheduler()
                    .runTaskTimerAsynchronously(plugin, this::recalculateQuietly, 1200L, recalcPeriod)
                    .getTaskId();
        }
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        if (recalcTaskId != -1) {
            Bukkit.getScheduler().cancelTask(recalcTaskId);
            recalcTaskId = -1;
        }
    }

    /** Periodic full recalculation — decays demand toward baseline and records price history. */
    public boolean recalculateQuietly() {
        if (recalcRunning) {
            return false;
        }
        recalcRunning = true;
        try {
            plugin.getBackendClient().recalculateMarketPrices(true);
            lastRecalcMs = System.currentTimeMillis();
            // Pull the freshly recalculated prices into the cache immediately.
            refreshQuietly();
            return true;
        } catch (Exception exception) {
            lastError = exception.getMessage();
            if (plugin.getGameSyncConfig().isVerboseSync()) {
                plugin.getLogger().warning("Market price recalculation failed: " + exception.getMessage());
            }
            return false;
        } finally {
            recalcRunning = false;
        }
    }

    public long getLastRecalcMs() {
        return lastRecalcMs;
    }

    public void refreshAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refreshQuietly);
    }

    public boolean refreshQuietly() {
        if (refreshRunning) {
            return false;
        }

        refreshRunning = true;
        try {
            MarketPriceSnapshotResponse response = plugin.getBackendClient().fetchMarketPrices();
            cache.applySnapshot(response);
            lastSuccessMs = System.currentTimeMillis();
            lastError = "";
            if (plugin.getGameSyncConfig().isVerboseSync()) {
                plugin.getLogger().info("Market prices refreshed: " + (response == null ? 0 : response.total));
            }
            return true;
        } catch (Exception exception) {
            lastError = exception.getMessage();
            if (plugin.getGameSyncConfig().isVerboseSync()) {
                plugin.getLogger().warning("Market price refresh failed: " + exception.getMessage());
            }
            return false;
        } finally {
            refreshRunning = false;
        }
    }

    public long getLastSuccessMs() {
        return lastSuccessMs;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isRefreshRunning() {
        return refreshRunning;
    }
}
