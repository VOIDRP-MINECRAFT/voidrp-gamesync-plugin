package ru.voidrp.gamesync.service;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.config.GameSyncConfig;

public final class SyncScheduler {

    private final JavaPlugin plugin;
    private final NationSyncService nationSyncService;
    private final LuckPermsNationMetaService nationMetaService;
    private final GameSyncConfig config;
    private BukkitTask task;

    public SyncScheduler(
        JavaPlugin plugin,
        NationSyncService nationSyncService,
        LuckPermsNationMetaService nationMetaService,
        GameSyncConfig config
    ) {
        this.plugin = plugin;
        this.nationSyncService = nationSyncService;
        this.nationMetaService = nationMetaService;
        this.config = config;
    }

    public void start() {
        stop();

        if (!config.isSyncEnabled()) {
            plugin.getLogger().info("Periodic sync disabled in config.");
            return;
        }

        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(
            plugin,
            () -> {
                nationSyncService.syncAll();
                nationSyncService.syncAllOnlinePlayers();
                if (config.isReconcileNationMetaAfterSync()) {
                    Bukkit.getScheduler().runTask(plugin, nationMetaService::reconcileOnlinePlayers);
                }
            },
            100L,
            config.getSyncPeriodTicks()
        );
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
