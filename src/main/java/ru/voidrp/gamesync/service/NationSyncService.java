package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.milkbowl.vault.economy.Economy;
import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.config.NationRegistry;
import ru.voidrp.gamesync.service.DynmapMarkerService;
import ru.voidrp.gamesync.service.DynmapWorldGuardStyleService;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.model.NationMemberStatsSyncRequest;
import ru.voidrp.gamesync.model.PlayerStatCacheSyncRequest;
import ru.voidrp.gamesync.model.PlayerStatSnapshot;
import ru.voidrp.gamesync.store.PluginDataStore;

public final class NationSyncService {

    private final JavaPlugin plugin;
    private final BackendClient backendClient;
    private final NationRegistry registry;
    private final PluginDataStore dataStore;
    private final Economy economy;
    private final GameSyncConfig config;
    private final TerritoryPointsResolver territoryPointsResolver;

    private final DynmapMarkerService dynmapMarkerService;
    private final DynmapWorldGuardStyleService dynmapWgStyleService;

    public NationSyncService(
            JavaPlugin plugin,
            BackendClient backendClient,
            NationRegistry registry,
            PluginDataStore dataStore,
            Economy economy,
            GameSyncConfig config,
            TerritoryPointsResolver territoryPointsResolver,
            DynmapMarkerService dynmapMarkerService,
            DynmapWorldGuardStyleService dynmapWgStyleService
    ) {
        this.plugin = plugin;
        this.backendClient = backendClient;
        this.registry = registry;
        this.dataStore = dataStore;
        this.economy = economy;
        this.config = config;
        this.territoryPointsResolver = territoryPointsResolver;
        this.dynmapMarkerService = dynmapMarkerService;
        this.dynmapWgStyleService = dynmapWgStyleService;
    }

    public void syncAll() {
        registry.refresh();
        for (NationDefinition nation : registry.all()) {
            syncNation(nation.slug());
        }
        updateDynmap();
    }

    private void updateDynmap() {
        List<NationDefinition> all = registry.all();
        try {
            dynmapMarkerService.updateMarkers(all);
        } catch (Exception e) {
            plugin.getLogger().warning("[Dynmap] Marker update failed: " + e.getMessage());
        }
        try {
            dynmapWgStyleService.updateStyles(all);
        } catch (Exception e) {
            plugin.getLogger().warning("[Dynmap] WorldGuard style update failed: " + e.getMessage());
        }
    }

    public void syncNation(String slug) {
        NationDefinition definition = registry.get(slug);
        if (definition == null) {
            plugin.getLogger().warning("Nation not found in registry: " + slug);
            return;
        }

        try {
            if (config.isSyncMembership()) {
                backendClient.syncNationMembership(definition);
                if (config.isVerboseSync()) {
                    plugin.getLogger().info("Membership synced for nation " + definition.slug());
                }
            }

            if (config.isSyncStats()) {
                NationMemberStatsSyncRequest payload = buildMemberStatsSyncRequest(definition);
                backendClient.upsertNationMemberSnapshots(payload);
                dataStore.saveNow();
                if (config.isVerboseSync()) {
                    plugin.getLogger().info("Member snapshots synced for nation " + definition.slug());
                }
            }
        } catch (IOException | InterruptedException exception) {
            plugin.getLogger().warning("Failed to sync nation " + definition.slug() + ": " + exception.getMessage());
        } catch (Exception exception) {
            plugin.getLogger().warning("Unexpected sync error for nation " + definition.slug() + ": " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    public void syncNationForPlayer(String minecraftNickname) {
        NationDefinition definition = registry.findByPlayer(minecraftNickname);
        if (definition == null) {
            if (config.isVerboseSync()) {
                plugin.getLogger().info("Join sync skipped for " + minecraftNickname + ": nation not found.");
            }
            return;
        }
        syncNation(definition.slug());
    }

    public void syncAllOnlinePlayers() {
        List<PlayerStatSnapshot> snapshots = new ArrayList<>();

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            String nickname = player.getName();
            UUID uuid = player.getUniqueId();
            int completedQuests = countCompletedQuests(uuid);
            PlayerStatSnapshot snapshot = readLiveSnapshot(player, nickname, completedQuests);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }

        if (snapshots.isEmpty()) {
            return;
        }

        try {
            backendClient.upsertPlayerStatsCache(new PlayerStatCacheSyncRequest(snapshots));
            if (config.isVerboseSync()) {
                plugin.getLogger().info("Player stats cache synced for " + snapshots.size() + " online players");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to sync player stats cache: " + e.getMessage());
        }
    }

    public TerritoryPointsResolver.TerritoryDebugReport buildTerritoryDebugReport(String slug) {
        NationDefinition definition = registry.get(slug);
        if (definition == null) {
            throw new IllegalArgumentException("Nation not found in registry: " + slug);
        }
        return territoryPointsResolver.buildDebugReport(definition);
    }

    public NationMemberStatsSyncRequest buildMemberStatsSyncRequest(NationDefinition definition) {
        List<PlayerStatSnapshot> snapshots = new ArrayList<>();

        for (String nickname : definition.allMembersIncludingRoles()) {
            if (nickname == null || nickname.isBlank()) {
                continue;
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(nickname);
            if (offlinePlayer == null) {
                continue;
            }

            UUID uuid = offlinePlayer.getUniqueId();
            if (uuid == null) {
                continue;
            }

            PlayerStatSnapshot snapshot = resolveBestSnapshot(offlinePlayer, nickname);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }

        int territoryPoints = territoryPointsResolver.resolve(definition);
        int bossKills = definition.bossKills() + dataStore.getNationOverride(definition.slug(), "bosskills");
        int eventsCompleted = definition.eventsCompleted() + dataStore.getNationOverride(definition.slug(), "events");
        int prestigeBonus = definition.prestigeBonus() + dataStore.getNationOverride(definition.slug(), "prestige");

        return new NationMemberStatsSyncRequest(
                definition.slug(),
                territoryPoints,
                bossKills,
                eventsCompleted,
                prestigeBonus,
                snapshots
        );
    }

    private PlayerStatSnapshot resolveBestSnapshot(OfflinePlayer offlinePlayer, String nickname) {
        UUID uuid = offlinePlayer.getUniqueId();
        if (uuid == null) {
            return null;
        }

        int completedQuests = countCompletedQuests(uuid);

        PlayerStatSnapshot live = readLiveSnapshot(offlinePlayer, nickname, completedQuests);
        if (live != null) {
            dataStore.setPlayerStatSnapshot(uuid, live);
            return live;
        }

        PlayerStatSnapshot fromFile = readFileSnapshot(offlinePlayer, nickname, completedQuests);
        if (fromFile != null) {
            dataStore.setPlayerStatSnapshot(uuid, fromFile);
            return fromFile;
        }

        PlayerStatSnapshot cached = dataStore.getPlayerStatSnapshot(uuid);
        if (cached != null) {
            return new PlayerStatSnapshot(
                    nickname,
                    cached.totalPlaytimeMinutes(),
                    cached.pvpKills(),
                    cached.mobKills(),
                    cached.deaths(),
                    cached.blocksPlaced(),
                    cached.blocksBroken(),
                    cached.currentBalance(),
                    "cached",
                    cached.lastSeenAt(),
                    completedQuests
            );
        }

        if (config.isVerboseSync()) {
            plugin.getLogger().warning("No stat source available for " + nickname + " (" + uuid + ")");
        }

        return new PlayerStatSnapshot(
                nickname,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0D,
                "missing",
                null,
                0
        );
    }

    private PlayerStatSnapshot readLiveSnapshot(OfflinePlayer offlinePlayer, String nickname, int completedQuests) {
        Player onlinePlayer = Bukkit.getPlayer(offlinePlayer.getUniqueId());
        if (onlinePlayer == null) {
            return null;
        }

        // Vanilla stats don't persist on this server (session-only), so prefer the plugin's
        // persistent event counters and only fall back to the live vanilla stat if it's higher.
        UUID uuid = offlinePlayer.getUniqueId();
        int playtimeMinutes = Math.max(
                ticksToMinutes(safeGetStatistic(onlinePlayer, Statistic.PLAY_ONE_MINUTE)),
                (int) (dataStore.getStatCounter(uuid, "playtime_seconds") / 60L));
        int pvpKills = Math.max(safeGetStatistic(onlinePlayer, Statistic.PLAYER_KILLS),
                (int) dataStore.getStatCounter(uuid, "kills"));
        int mobKills = Math.max(safeGetStatistic(onlinePlayer, Statistic.MOB_KILLS),
                (int) dataStore.getStatCounter(uuid, "mobkills"));
        int deaths = Math.max(safeGetStatistic(onlinePlayer, Statistic.DEATHS),
                (int) dataStore.getStatCounter(uuid, "deaths"));
        // Event-tracked counts include modded blocks; vanilla Statistic misses them.
        long blocksBroken = Math.max(sumMineBlockStats(onlinePlayer), dataStore.getBlocksBroken(uuid));
        long blocksPlaced = Math.max(sumUsedBlockItems(onlinePlayer), dataStore.getBlocksPlaced(uuid));
        double currentBalance = resolveCurrentBalance(offlinePlayer);

        return new PlayerStatSnapshot(
                nickname,
                playtimeMinutes,
                pvpKills,
                mobKills,
                deaths,
                blocksPlaced,
                blocksBroken,
                currentBalance,
                "live",
                Instant.now().toString(),
                completedQuests
        );
    }

    private PlayerStatSnapshot readFileSnapshot(OfflinePlayer offlinePlayer, String nickname, int completedQuests) {
        if (config.isStatsOnlineOnly()) {
            return null;
        }

        UUID uuid = offlinePlayer.getUniqueId();
        JsonObject root = readStatsFile(uuid);
        boolean hasCounters = dataStore.getStatCounter(uuid, "playtime_seconds") > 0
                || dataStore.getStatCounter(uuid, "kills") > 0
                || dataStore.getStatCounter(uuid, "deaths") > 0
                || dataStore.getBlocksBroken(uuid) > 0
                || dataStore.getBlocksPlaced(uuid) > 0;
        // The vanilla stats file is username-keyed here so it's usually absent; still emit a
        // snapshot from the persistent plugin counters. Bail only if we truly have nothing.
        if (root == null && !hasCounters) {
            return null;
        }
        if (root == null) {
            root = new JsonObject();
        }

        // Persistent plugin counters are authoritative (vanilla stats file is empty here).
        int playtimeMinutes = Math.max(
                ticksToMinutes(readCustomStatFromRoot(root, "minecraft:play_time")),
                (int) (dataStore.getStatCounter(uuid, "playtime_seconds") / 60L));
        int pvpKills = Math.max(readCustomStatFromRoot(root, "minecraft:player_kills"),
                (int) dataStore.getStatCounter(uuid, "kills"));
        int mobKills = Math.max(readCustomStatFromRoot(root, "minecraft:mob_kills"),
                (int) dataStore.getStatCounter(uuid, "mobkills"));
        int deaths = Math.max(readCustomStatFromRoot(root, "minecraft:deaths"),
                (int) dataStore.getStatCounter(uuid, "deaths"));
        long blocksBroken = Math.max(readCategorySumFromRoot(root, "minecraft:mined"), dataStore.getBlocksBroken(uuid));
        long blocksPlaced = Math.max(readPlacedBlocksEstimateFromRoot(root), dataStore.getBlocksPlaced(uuid));
        double currentBalance = resolveCurrentBalance(offlinePlayer);

        String lastSeenAt = null;
        if (offlinePlayer.getLastPlayed() > 0L) {
            lastSeenAt = Instant.ofEpochMilli(offlinePlayer.getLastPlayed()).toString();
        }

        return new PlayerStatSnapshot(
                nickname,
                playtimeMinutes,
                pvpKills,
                mobKills,
                deaths,
                blocksPlaced,
                blocksBroken,
                currentBalance,
                "stats_file",
                lastSeenAt,
                completedQuests
        );
    }

    private double resolveCurrentBalance(OfflinePlayer offlinePlayer) {
        if (economy == null || offlinePlayer == null) {
            return 0D;
        }

        try {
            return round2(economy.getBalance(offlinePlayer));
        } catch (Exception ignored) {
            Player player = Bukkit.getPlayer(offlinePlayer.getUniqueId());
            if (player != null) {
                try {
                    return round2(economy.getBalance(player));
                } catch (Exception ignoredAgain) {
                }
            }
            return 0D;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private int safeGetStatistic(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long sumMineBlockStats(Player player) {
        long total = 0L;
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (!material.isBlock()) {
                continue;
            }
            try {
                total += player.getStatistic(Statistic.MINE_BLOCK, material);
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    private long sumUsedBlockItems(Player player) {
        long total = 0L;
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (!material.isBlock()) {
                continue;
            }
            try {
                total += player.getStatistic(Statistic.USE_ITEM, material);
            } catch (Exception ignored) {
            }
        }
        return total;
    }

    private int ticksToMinutes(int ticks) {
        return ticks <= 0 ? 0 : ticks / (20 * 60);
    }

    private int readCustomStatFromRoot(JsonObject root, String statKey) {
        JsonObject statsObject = getObject(root, "stats");
        if (statsObject == null) {
            return 0;
        }

        JsonObject customObject = getObject(statsObject, "minecraft:custom");
        if (customObject == null) {
            return 0;
        }

        JsonElement statElement = customObject.get(statKey);
        if (statElement == null || !statElement.isJsonPrimitive()) {
            return 0;
        }

        try {
            return statElement.getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long readCategorySumFromRoot(JsonObject root, String categoryKey) {
        JsonObject statsObject = getObject(root, "stats");
        if (statsObject == null) {
            return 0L;
        }

        JsonObject categoryObject = getObject(statsObject, categoryKey);
        if (categoryObject == null) {
            return 0L;
        }

        long total = 0L;
        for (String key : categoryObject.keySet()) {
            JsonElement value = categoryObject.get(key);
            if (value != null && value.isJsonPrimitive()) {
                try {
                    total += value.getAsLong();
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    private long readPlacedBlocksEstimateFromRoot(JsonObject root) {
        JsonObject statsObject = getObject(root, "stats");
        if (statsObject == null) {
            return 0L;
        }

        JsonObject usedObject = getObject(statsObject, "minecraft:used");
        if (usedObject == null) {
            return 0L;
        }

        long total = 0L;
        for (String key : usedObject.keySet()) {
            if (!isLikelyPlaceableBlockKey(key)) {
                continue;
            }
            JsonElement value = usedObject.get(key);
            if (value != null && value.isJsonPrimitive()) {
                try {
                    total += value.getAsLong();
                } catch (Exception ignored) {
                }
            }
        }
        return total;
    }

    private boolean isLikelyPlaceableBlockKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        return !key.contains("sword")
                && !key.contains("pickaxe")
                && !key.contains("axe")
                && !key.contains("shovel")
                && !key.contains("hoe")
                && !key.contains("bow")
                && !key.contains("crossbow")
                && !key.contains("shield")
                && !key.contains("bucket")
                && !key.contains("potion")
                && !key.contains("helmet")
                && !key.contains("chestplate")
                && !key.contains("leggings")
                && !key.contains("boots");
    }

    private JsonObject readStatsFile(UUID uuid) {
        try {
            Path path = resolveStatsFile(uuid);
            if (path == null || !Files.exists(path)) {
                return null;
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (Exception exception) {
            if (config.isVerboseSync()) {
                plugin.getLogger().warning("Failed to read stats file for " + uuid + ": " + exception.getMessage());
            }
            return null;
        }
    }

    private Path resolveStatsFile(UUID uuid) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }

        Path primaryWorldPath = worlds.get(0).getWorldFolder().toPath();
        return primaryWorldPath.resolve("stats").resolve(uuid.toString() + ".json");
    }

    private JsonObject getObject(JsonObject parent, String key) {
        if (parent == null) {
            return null;
        }
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        return value.getAsJsonObject();
    }

    // FTB Quests SNBT format: completed: { A1B2C3D4E5F60708: 1777795384400L ... }
    // Keys are unquoted 16-char hex IDs; values are long timestamps with L suffix.
    private static final Pattern FTB_COMPLETED_BLOCK = Pattern.compile(
        "\\bcompleted\\s*:\\s*\\{([^}]*?)\\}", Pattern.DOTALL
    );
    private static final Pattern FTB_QUEST_ENTRY = Pattern.compile("[0-9A-Fa-f]{16}");

    private int countCompletedQuests(UUID uuid) {
        try {
            Path path = resolveFtbQuestsFile(uuid);
            if (path == null || !Files.exists(path)) {
                return 0;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher blockMatcher = FTB_COMPLETED_BLOCK.matcher(content);
            if (!blockMatcher.find()) {
                return 0;
            }
            String block = blockMatcher.group(1);
            Matcher entryMatcher = FTB_QUEST_ENTRY.matcher(block);
            int count = 0;
            while (entryMatcher.find()) {
                count++;
            }
            return count;
        } catch (Exception e) {
            if (config.isVerboseSync()) {
                plugin.getLogger().warning("Failed to read FTB Quests data for " + uuid + ": " + e.getMessage());
            }
            return 0;
        }
    }

    private Path resolveFtbQuestsFile(UUID uuid) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        Path worldPath = worlds.get(0).getWorldFolder().toPath();
        return worldPath.resolve("ftbquests/" + uuid + ".snbt");
    }
}
