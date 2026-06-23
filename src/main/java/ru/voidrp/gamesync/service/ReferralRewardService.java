package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.model.ReferralResolveResponse;

public final class ReferralRewardService {

    private final JavaPlugin plugin;
    private final BackendClient backendClient;
    private final RewardCacheService rewardCacheService;
    private final GameSyncConfig config;

    public ReferralRewardService(
        JavaPlugin plugin,
        BackendClient backendClient,
        RewardCacheService rewardCacheService,
        GameSyncConfig config
    ) {
        this.plugin = plugin;
        this.backendClient = backendClient;
        this.rewardCacheService = rewardCacheService;
        this.config = config;
    }

    public void resolveAndMaybeApply(Player player, boolean forceApply) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReferralResolveResponse response = backendClient.resolveReferralReward(player.getName());

                if (response == null || !response.player_exists || !response.has_active_reward || response.reward == null) {
                    if (config.isVerboseSync()) {
                        plugin.getLogger().info("No active referral reward for " + player.getName());
                    }
                    return;
                }

                if (config.isSuppressDuplicateRewardUntilExpiry() && !forceApply) {
                    if (rewardCacheService.isAlreadyGranted(player.getUniqueId(), response.reward.reward_bundle_key, response.reward.expires_at)) {
                        if (config.isVerboseSync()) {
                            plugin.getLogger().info("Reward already granted for " + player.getName() + " until " + response.reward.expires_at);
                        }
                        return;
                    }
                }

                if (!config.isAutoApplyOnJoin() && !forceApply) {
                    return;
                }

                applyReward(player, response);
            } catch (IOException | InterruptedException exception) {
                plugin.getLogger().warning("Failed to resolve referral reward for " + player.getName() + ": " + exception.getMessage());
            }
        });
    }

    public void applyReward(Player player, ReferralResolveResponse response) {
        if (response == null || response.reward == null) return;

        String playerName = player.getName();
        if (!playerName.matches("[a-zA-Z0-9_]{1,16}")) {
            plugin.getLogger().warning("[Referral] Rejected reward dispatch: unsafe player name '" + playerName + "'");
            return;
        }

        List<String> commands = config.getRewardCommands(response.reward.reward_bundle_key);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String command : commands) {
                String resolved = command.replace("%player%", playerName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            }

            rewardCacheService.markGranted(
                player.getUniqueId(),
                response.reward.reward_bundle_key,
                response.reward.expires_at
            );

            player.sendMessage("§aТвой реферальный бонус активирован: §f" + response.reward.reward_bundle_key);
        });
    }
}
