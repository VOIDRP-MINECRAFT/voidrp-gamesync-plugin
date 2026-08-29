package ru.voidrp.gamesync.store;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.model.PlayerStatSnapshot;

public final class PluginDataStore {

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    // Event-tracked block break/place counters, kept in memory and flushed to yaml on
    // saveNow(). Bukkit's Statistic.MINE_BLOCK/USE_ITEM only see vanilla Materials, so on
    // the modded pack most mining/placing was invisible; counting via block events covers
    // modded blocks too. [0] = broken, [1] = placed.
    private final Map<UUID, AtomicLong[]> blockStats = new ConcurrentHashMap<>();

    public PluginDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    private AtomicLong[] blockEntry(UUID playerId) {
        return blockStats.computeIfAbsent(playerId, id -> new AtomicLong[] {
                new AtomicLong(yaml.getLong("block-stats." + id + ".broken", 0L)),
                new AtomicLong(yaml.getLong("block-stats." + id + ".placed", 0L)),
        });
    }

    public long getBlocksBroken(UUID playerId) {
        return blockEntry(playerId)[0].get();
    }

    public long getBlocksPlaced(UUID playerId) {
        return blockEntry(playerId)[1].get();
    }

    public void addBlockBroken(UUID playerId, long delta) {
        blockEntry(playerId)[0].addAndGet(delta);
    }

    public void addBlockPlaced(UUID playerId, long delta) {
        blockEntry(playerId)[1].addAndGet(delta);
    }

    // Generic persistent per-player counters (kills/deaths/mob_kills/playtime), used because
    // vanilla stats DON'T persist on this server — the stats file is username-keyed and empty,
    // so every `getStatistic` read is session-only. These live under stat-counters.<uuid>.<key>.
    private final Map<UUID, Map<String, AtomicLong>> statCounters = new ConcurrentHashMap<>();

    private AtomicLong counter(UUID playerId, String key) {
        return statCounters
                .computeIfAbsent(playerId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new AtomicLong(yaml.getLong("stat-counters." + playerId + "." + k, 0L)));
    }

    public long getStatCounter(UUID playerId, String key) {
        return counter(playerId, key).get();
    }

    public void addStatCounter(UUID playerId, String key, long delta) {
        counter(playerId, key).addAndGet(delta);
    }

    public String getRewardBundle(UUID playerId) {
        return yaml.getString("reward-cache." + playerId + ".bundle");
    }

    public String getRewardExpiresAt(UUID playerId) {
        return yaml.getString("reward-cache." + playerId + ".expires-at");
    }

    public void setRewardGrant(UUID playerId, String bundleKey, String expiresAt) {
        yaml.set("reward-cache." + playerId + ".bundle", bundleKey);
        yaml.set("reward-cache." + playerId + ".expires-at", expiresAt);
    }

    public int getNationOverride(String slug, String key) {
        return yaml.getInt("nation-overrides." + slug + "." + key, 0);
    }

    public void setNationOverride(String slug, String key, int value) {
        yaml.set("nation-overrides." + slug + "." + key, value);
    }

    public String getNationMetaPrefix(UUID playerId) {
        return yaml.getString("nation-meta-cache." + playerId + ".prefix");
    }

    public String getNationMetaSuffix(UUID playerId) {
        return yaml.getString("nation-meta-cache." + playerId + ".suffix");
    }

    public String getNationMetaSlug(UUID playerId) {
        return yaml.getString("nation-meta-cache." + playerId + ".slug");
    }

    public String getNationMetaRole(UUID playerId) {
        return yaml.getString("nation-meta-cache." + playerId + ".role");
    }

    public void setNationMeta(UUID playerId, String prefix, String suffix, String slug, String role) {
        yaml.set("nation-meta-cache." + playerId + ".prefix", prefix);
        yaml.set("nation-meta-cache." + playerId + ".suffix", suffix);
        yaml.set("nation-meta-cache." + playerId + ".slug", slug);
        yaml.set("nation-meta-cache." + playerId + ".role", role);
    }

    public void clearNationMeta(UUID playerId) {
        yaml.set("nation-meta-cache." + playerId, null);
    }

    public PlayerStatSnapshot getPlayerStatSnapshot(UUID playerId) {
        String base = "player-stats-cache." + playerId;
        if (!yaml.contains(base)) {
            return null;
        }

        return new PlayerStatSnapshot(
            yaml.getString(base + ".minecraft-nickname", ""),
            yaml.getInt(base + ".total-playtime-minutes", 0),
            yaml.getInt(base + ".pvp-kills", 0),
            yaml.getInt(base + ".mob-kills", 0),
            yaml.getInt(base + ".deaths", 0),
            yaml.getLong(base + ".blocks-placed", 0L),
            yaml.getLong(base + ".blocks-broken", 0L),
            yaml.getDouble(base + ".current-balance", 0D),
            yaml.getString(base + ".source", "cached"),
            yaml.getString(base + ".last-seen-at", null),
            yaml.getInt(base + ".completed-quests", 0)
        );
    }

    public void setPlayerStatSnapshot(UUID playerId, PlayerStatSnapshot snapshot) {
        String base = "player-stats-cache." + playerId;
        yaml.set(base + ".minecraft-nickname", snapshot.minecraftNickname());
        yaml.set(base + ".total-playtime-minutes", snapshot.totalPlaytimeMinutes());
        yaml.set(base + ".pvp-kills", snapshot.pvpKills());
        yaml.set(base + ".mob-kills", snapshot.mobKills());
        yaml.set(base + ".deaths", snapshot.deaths());
        yaml.set(base + ".blocks-placed", snapshot.blocksPlaced());
        yaml.set(base + ".blocks-broken", snapshot.blocksBroken());
        yaml.set(base + ".current-balance", snapshot.currentBalance());
        yaml.set(base + ".source", snapshot.source());
        yaml.set(base + ".last-seen-at", snapshot.lastSeenAt());
        yaml.set(base + ".completed-quests", snapshot.completedQuests());
    }

    public void clearPlayerStatSnapshot(UUID playerId) {
        yaml.set("player-stats-cache." + playerId, null);
    }

    public boolean hasStartingBalanceGranted(UUID playerId) {
        return yaml.getBoolean("starting-balance-granted." + playerId, false);
    }

    public void setStartingBalanceGranted(UUID playerId) {
        yaml.set("starting-balance-granted." + playerId, true);
    }

    public boolean getTierUnlocked(UUID playerId, String tierName) {
        return yaml.getBoolean("tier-tracking." + playerId + "." + tierName, false);
    }

    public void setTierUnlocked(UUID playerId, String tierName) {
        yaml.set("tier-tracking." + playerId + "." + tierName, true);
    }

    public void saveNow() {
        for (Map.Entry<UUID, AtomicLong[]> entry : blockStats.entrySet()) {
            yaml.set("block-stats." + entry.getKey() + ".broken", entry.getValue()[0].get());
            yaml.set("block-stats." + entry.getKey() + ".placed", entry.getValue()[1].get());
        }
        for (Map.Entry<UUID, Map<String, AtomicLong>> entry : statCounters.entrySet()) {
            for (Map.Entry<String, AtomicLong> c : entry.getValue().entrySet()) {
                yaml.set("stat-counters." + entry.getKey() + "." + c.getKey(), c.getValue().get());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save data.yml: " + exception.getMessage());
        }
    }
}


