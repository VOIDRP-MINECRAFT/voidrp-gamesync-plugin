package ru.voidrp.gamesync.service;

import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.PlayerSkinResponse;

public final class SkinCommandService {

    private final VoidRpGameSyncPlugin plugin;

    public SkinCommandService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        return plugin.getServer().getPluginManager().getPlugin("SkinsRestorer") != null
                && plugin.getServer().getPluginManager().getPlugin("SkinsRestorer").isEnabled();
    }

    public void applyOrClear(Player player, PlayerSkinResponse response) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!isAvailable()) {
            if (plugin.getGameSyncConfig().isVerboseSync()) {
                plugin.getLogger().warning("SkinsRestorer not found or not enabled. Skin sync skipped for " + player.getName());
            }
            return;
        }

        if (response == null || !response.player_exists) {
            if (plugin.getGameSyncConfig().isVerboseSync()) {
                plugin.getLogger().warning("Skin sync skipped for " + player.getName() + ": backend player record not found.");
            }
            return;
        }

        if (!response.has_skin || response.skin_url == null || response.skin_url.isBlank()) {
            if (plugin.getGameSyncConfig().isClearMissingSkin()) {
                dispatch("skin clear " + player.getName());
            }
            return;
        }

        String variant = normalizeVariant(response.model_variant);
        String customName = buildCustomName(player.getName(), response.sha256);
        dispatch("sr createcustom " + customName + " " + response.skin_url + " " + variant);
        dispatch("skin set " + customName + " " + player.getName());
    }

    public void clear(Player player) {
        if (player == null || !player.isOnline() || !isAvailable()) {
            return;
        }
        dispatch("skin clear " + player.getName());
    }

    public void refresh(Player player, PlayerSkinResponse response) {
        applyOrClear(player, response);
    }

    private void dispatch(String command) {
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        Bukkit.dispatchCommand(console, command);
    }

    private String normalizeVariant(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("slim") ? "slim" : "classic";
    }

    private String buildCustomName(String playerName, String sha256) {
        String safePlayer = playerName == null ? "player" : playerName.replaceAll("[^A-Za-z0-9_-]", "").toLowerCase(Locale.ROOT);
        String suffix = (sha256 == null || sha256.isBlank()) ? "skin" : sha256.substring(0, Math.min(12, sha256.length())).toLowerCase(Locale.ROOT);
        return "voidrp_" + safePlayer + "_" + suffix;
    }
}
