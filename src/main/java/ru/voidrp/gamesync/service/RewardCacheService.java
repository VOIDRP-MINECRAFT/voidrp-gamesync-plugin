package ru.voidrp.gamesync.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.store.PluginDataStore;

public final class RewardCacheService {

    private final JavaPlugin plugin;
    private final PluginDataStore dataStore;

    public RewardCacheService(JavaPlugin plugin, PluginDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public boolean isAlreadyGranted(UUID playerId, String bundleKey, String expiresAtRaw) {
        String storedBundle = dataStore.getRewardBundle(playerId);
        String storedExpires = dataStore.getRewardExpiresAt(playerId);

        if (storedBundle == null || storedExpires == null) return false;
        if (!storedBundle.equalsIgnoreCase(bundleKey)) return false;

        try {
            Instant stored = Instant.parse(storedExpires);
            Instant incoming = Instant.parse(expiresAtRaw);
            return !stored.isBefore(incoming);
        } catch (DateTimeParseException exception) {
            plugin.getLogger().warning("Failed to parse reward expiry: " + exception.getMessage());
            return false;
        }
    }

    public void markGranted(UUID playerId, String bundleKey, String expiresAtRaw) {
        dataStore.setRewardGrant(playerId, bundleKey, expiresAtRaw);
        dataStore.saveNow();
    }
}
