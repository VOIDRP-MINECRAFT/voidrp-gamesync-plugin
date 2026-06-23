package ru.voidrp.gamesync.listener;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.PlayerSkinResponse;

public final class PlayerJoinRewardListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerJoinRewardListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getGameSyncConfig().isResolveOnJoin()) {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                plugin,
                () -> plugin.getReferralRewardService().resolveAndMaybeApply(event.getPlayer(), false),
                plugin.getGameSyncConfig().getJoinDelayTicks()
            );
        }

        if (plugin.getGameSyncConfig().isApplyNationMetaOnJoin()) {
            plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> plugin.getLuckPermsNationMetaService().applyForPlayer(event.getPlayer()),
                Math.max(20L, plugin.getGameSyncConfig().getJoinDelayTicks())
            );
        }

        if (plugin.getGameSyncConfig().isSyncOnPlayerJoin()) {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                plugin,
                () -> plugin.getNationSyncService().syncNationForPlayer(event.getPlayer().getName()),
                plugin.getGameSyncConfig().getPlayerJoinSyncDelayTicks()
            );
        }

        if (plugin.getGameSyncConfig().isSkinSyncEnabled() && plugin.getGameSyncConfig().isSkinApplyOnJoin()) {
            scheduleSkinApply(event.getPlayer().getName());
        }

        if (plugin.getGameSyncConfig().isStartingBalanceEnabled()) {
            maybeGrantStartingBalance(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        }
    }

    private void maybeGrantStartingBalance(UUID playerId, String playerName) {
        if (plugin.getDataStore().hasStartingBalanceGranted(playerId)) {
            return;
        }

        long delay = plugin.getGameSyncConfig().getStartingBalanceDelayTicks();
        double amount = plugin.getGameSyncConfig().getStartingBalanceAmount();
        long amountLong = (long) amount;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            var player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) return;

            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "eco give " + playerName + " " + amountLong);

            plugin.getDataStore().setStartingBalanceGranted(playerId);
            plugin.getDataStore().saveNow();

            player.sendMessage("§a§lДобро пожаловать! §r§aВам начислено §6" + amountLong + " монет§a.");
            plugin.getLogger().info("Starting balance " + amountLong + " granted to " + playerName);
        }, delay);
    }

    private void scheduleSkinApply(String playerName) {
        long delayTicks = plugin.getGameSyncConfig().getSkinJoinDelayTicks();

        plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                plugin,
                () -> {
                    try {
                        PlayerSkinResponse response = plugin.getBackendClient().getPlayerSkin(playerName);
                        Bukkit.getScheduler().runTask(
                                plugin,
                                () -> {
                                    var player = Bukkit.getPlayerExact(playerName);
                                    if (player != null && player.isOnline()) {
                                        plugin.getSkinCommandService().applyOrClear(player, response);
                                    }
                                }
                        );
                    } catch (Exception exception) {
                        if (plugin.getGameSyncConfig().isVerboseSync()) {
                            plugin.getLogger().warning("Failed to sync skin for " + playerName + ": " + exception.getMessage());
                        }
                    }
                },
                delayTicks
        );
    }
}
