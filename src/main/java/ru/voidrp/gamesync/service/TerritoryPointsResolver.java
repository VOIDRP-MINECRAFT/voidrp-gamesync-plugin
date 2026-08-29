package ru.voidrp.gamesync.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.store.PluginDataStore;

public final class TerritoryPointsResolver {

    private final JavaPlugin plugin;
    private final PluginDataStore dataStore;
    private final GameSyncConfig config;

    public TerritoryPointsResolver(JavaPlugin plugin, PluginDataStore dataStore, GameSyncConfig config) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.config = config;
    }

    public int resolve(NationDefinition definition) {
        int manualValue = definition.territoryPoints() + dataStore.getNationOverride(definition.slug(), "territory");

        String source = config.getTerritorySourceMode();
        if (source == null || source.isBlank() || source.equalsIgnoreCase("manual")) {
            return manualValue;
        }

        if (source.equalsIgnoreCase("worldguard")) {
            Integer resolved = resolveViaWorldGuard(definition, null);
            if (resolved != null) {
                return resolved;
            }
            if (config.isTerritoryWorldGuardFallbackToManual()) {
                return manualValue;
            }
            return 0;
        }

        if (source.equalsIgnoreCase("ftbchunks")) {
            Integer resolved = resolveViaFtbChunks(definition, null);
            if (resolved != null) {
                return resolved;
            }
            return config.isTerritoryFtbChunksFallbackToManual() ? manualValue : 0;
        }

        return manualValue;
    }

    public TerritoryDebugReport buildDebugReport(NationDefinition definition) {
        int manualValue = definition.territoryPoints() + dataStore.getNationOverride(definition.slug(), "territory");
        String source = config.getTerritorySourceMode();

        TerritoryDebugReport report = new TerritoryDebugReport(
                definition.slug(),
                source,
                manualValue,
                config.getTerritoryWorldGuardCountMode(),
                config.isTerritoryWorldGuardFallbackToManual()
        );

        if (source == null || source.isBlank() || source.equalsIgnoreCase("manual")) {
            report.finalValue = manualValue;
            report.resolutionMode = "manual";
            return report;
        }

        if (source.equalsIgnoreCase("ftbchunks")) {
            Integer resolved = resolveViaFtbChunks(definition, report);
            report.worldguardValue = resolved == null ? 0 : resolved;
            if (resolved != null) {
                report.finalValue = resolved;
                report.resolutionMode = "ftbchunks-by-nation-members";
            } else {
                report.finalValue = config.isTerritoryFtbChunksFallbackToManual() ? manualValue : 0;
                report.resolutionMode = config.isTerritoryFtbChunksFallbackToManual()
                        ? "ftbchunks-error-fallback-manual"
                        : "ftbchunks-error-zero";
            }
            return report;
        }

        if (!source.equalsIgnoreCase("worldguard")) {
            report.finalValue = manualValue;
            report.resolutionMode = "unknown-source-fallback-manual";
            return report;
        }

        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            report.finalValue = config.isTerritoryWorldGuardFallbackToManual() ? manualValue : 0;
            report.resolutionMode = config.isTerritoryWorldGuardFallbackToManual()
                    ? "worldguard-missing-fallback-manual"
                    : "worldguard-missing-zero";
            return report;
        }

        Integer resolved = resolveViaWorldGuard(definition, report);
        report.worldguardValue = resolved == null ? 0 : resolved;

        if (resolved != null) {
            report.finalValue = resolved;
            report.resolutionMode = "worldguard-by-nation-members";
            return report;
        }

        if (config.isTerritoryWorldGuardFallbackToManual()) {
            report.finalValue = manualValue;
            report.resolutionMode = "worldguard-error-fallback-manual";
            return report;
        }

        report.finalValue = 0;
        report.resolutionMode = "worldguard-error-zero";
        return report;
    }

    private Integer resolveViaWorldGuard(NationDefinition definition, TerritoryDebugReport debugReport) {
        if (plugin.getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            return null;
        }

        List<String> rawMembers = definition.allMembersIncludingRoles();
        if (debugReport != null) {
            debugReport.membersChecked = rawMembers.size();
        }

        ResolvedMembers resolvedMembers = resolveMembers(rawMembers);

        if (debugReport != null) {
            debugReport.memberUuidsResolved = resolvedMembers.uuids.size();
            debugReport.memberNamesResolved = resolvedMembers.names.size();
            debugReport.unresolvedMembers.addAll(resolvedMembers.unresolved);
        }

        if (resolvedMembers.uuids.isEmpty() && resolvedMembers.names.isEmpty()) {
            return 0;
        }

        String countMode = config.getTerritoryWorldGuardCountMode();
        boolean count3d = countMode != null && countMode.equalsIgnoreCase("3d");

        long total = 0L;
        Set<String> countedRegionKeys = new HashSet<>();

        try {
            for (World world : Bukkit.getWorlds()) {
                RegionManager regionManager = WorldGuard.getInstance()
                        .getPlatform()
                        .getRegionContainer()
                        .get(BukkitAdapter.adapt(world));

                if (regionManager == null) {
                    continue;
                }

                for (ProtectedRegion region : regionManager.getRegions().values()) {
                    if (debugReport != null) {
                        debugReport.regionsScanned++;
                    }

                    MatchResult ownersMatch = findMatch(region.getOwners(), resolvedMembers, "owner");
                    MatchResult membersMatch = findMatch(region.getMembers(), resolvedMembers, "member");

                    MatchResult chosenMatch = ownersMatch != null ? ownersMatch : membersMatch;
                    if (chosenMatch == null) {
                        continue;
                    }

                    String regionKey = world.getName().toLowerCase(Locale.ROOT) + ":" + region.getId().toLowerCase(Locale.ROOT);
                    if (!countedRegionKeys.add(regionKey)) {
                        continue;
                    }

                    long area = count3d ? calculate3d(region) : calculate2d(region);
                    if (area <= 0L) {
                        continue;
                    }

                    total += area;

                    if (debugReport != null) {
                        debugReport.matches.add(
                                new TerritoryMatch(
                                        world.getName(),
                                        region.getId(),
                                        chosenMatch.matchType,
                                        chosenMatch.matchedValue,
                                        area,
                                        count3d ? "3d" : "2d"
                                )
                        );
                    }
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to calculate WorldGuard territory for " + definition.slug() + ": " + exception.getMessage());
            if (debugReport != null) {
                debugReport.error = exception.getMessage();
            }
            return null;
        }

        if (total > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    // FTB Chunks claims belong to an FTB *team*, not a player, and are stored in
    // <world>/ftbchunks/<teamId>.snbt as chunks:{ "dim": [ { x, z, time } ... ] }.
    // A solo player's team id equals their player UUID; a party team gets its own
    // random UUID and lists its members in <world>/ftbteams/party/<teamId>.snbt.
    // So a nation's territory = sum of the claims of the DISTINCT teams its members
    // belong to (dedup, so two members of the same party team count that team once).
    private static final Pattern FTB_CHUNK_ENTRY = Pattern.compile("\\{\\s*x:\\s*-?\\d+\\s*,\\s*z:\\s*-?\\d+");
    private static final Pattern FTB_DIM_HEADER = Pattern.compile("\"([\\w.-]+:[\\w./-]+)\"\\s*:\\s*\\[");
    private static final Pattern FTB_TEAM_ID = Pattern.compile("id:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final Pattern FTB_RANK_MEMBER = Pattern.compile("([0-9a-fA-F-]{36}):\\s*\"\\w+\"");

    private Integer resolveViaFtbChunks(NationDefinition definition, TerritoryDebugReport debugReport) {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return null;
        }
        Path worldPath = worlds.get(0).getWorldFolder().toPath();
        Path ftbDir = worldPath.resolve("ftbchunks");

        List<String> rawMembers = definition.allMembersIncludingRoles();
        if (debugReport != null) {
            debugReport.membersChecked = rawMembers.size();
        }

        ResolvedMembers resolvedMembers = resolveMembers(rawMembers);
        if (debugReport != null) {
            debugReport.memberUuidsResolved = resolvedMembers.uuids.size();
            debugReport.memberNamesResolved = resolvedMembers.names.size();
            debugReport.unresolvedMembers.addAll(resolvedMembers.unresolved);
        }

        if (resolvedMembers.uuids.isEmpty()) {
            return 0;
        }

        Set<String> dimWhitelist = new HashSet<>();
        for (String dim : config.getTerritoryFtbChunksDimensions()) {
            if (dim != null && !dim.isBlank()) {
                dimWhitelist.add(dim.trim().toLowerCase(Locale.ROOT));
            }
        }

        long totalChunks = 0L;
        try {
            // player UUID -> party team id (players not in a party keep their own team)
            java.util.Map<String, String> playerToTeam = buildPartyMemberMap(worldPath);

            // resolve each member to their effective team, then sum distinct teams once
            Set<String> countedTeams = new HashSet<>();
            for (UUID uuid : resolvedMembers.uuids) {
                String key = uuid.toString().toLowerCase(Locale.ROOT);
                String teamId = playerToTeam.getOrDefault(key, uuid.toString());
                if (!countedTeams.add(teamId.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Path file = ftbDir.resolve(teamId + ".snbt");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                long claimed = countClaimedChunks(file, dimWhitelist);
                totalChunks += claimed;
                if (debugReport != null && claimed > 0) {
                    debugReport.matches.add(new TerritoryMatch(
                            "ftbchunks", teamId, "team", uuid.toString(), claimed, "chunks"));
                    debugReport.regionsScanned++;
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to calculate FTB Chunks territory for " + definition.slug() + ": " + exception.getMessage());
            if (debugReport != null) {
                debugReport.error = exception.getMessage();
            }
            return null;
        }

        long total = totalChunks * config.getTerritoryFtbChunksBlocksPerChunk();
        if (total > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    // Maps every party-team member's player UUID -> that party team's id, by reading
    // the ranks:{ <uuid>: "owner|member|..." } block of each <world>/ftbteams/party/*.snbt.
    private java.util.Map<String, String> buildPartyMemberMap(Path worldPath) throws java.io.IOException {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        Path partyDir = worldPath.resolve("ftbteams").resolve("party");
        if (!Files.isDirectory(partyDir)) {
            return map;
        }
        try (java.util.stream.Stream<Path> files = Files.list(partyDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.getFileName().toString().endsWith(".snbt")) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher idMatch = FTB_TEAM_ID.matcher(text);
                if (!idMatch.find()) {
                    continue;
                }
                String teamId = idMatch.group(1);
                String ranks = extractBlock(text, "ranks:");
                if (ranks == null) {
                    continue;
                }
                Matcher member = FTB_RANK_MEMBER.matcher(ranks);
                while (member.find()) {
                    map.put(member.group(1).toLowerCase(Locale.ROOT), teamId);
                }
            }
        }
        return map;
    }

    // Returns the { ... } block that follows the given label, matched by brace depth.
    private String extractBlock(String text, String label) {
        int i = text.indexOf(label);
        if (i < 0) {
            return null;
        }
        int open = text.indexOf('{', i);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int j = open; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, j + 1);
                }
            }
        }
        return null;
    }

    private long countClaimedChunks(Path file, Set<String> dimWhitelist) throws java.io.IOException {
        long count = 0L;
        String currentDim = null;
        boolean allDims = dimWhitelist.isEmpty();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher dimMatch = FTB_DIM_HEADER.matcher(line);
            if (dimMatch.find()) {
                currentDim = dimMatch.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (line.indexOf(']') >= 0 && line.indexOf('{') < 0) {
                currentDim = null;
            }
            if (FTB_CHUNK_ENTRY.matcher(line).find()) {
                if (allDims || (currentDim != null && dimWhitelist.contains(currentDim))) {
                    count++;
                }
            }
        }
        return count;
    }

    private MatchResult findMatch(DefaultDomain domain, ResolvedMembers members, String sourcePrefix) {
        if (domain == null) {
            return null;
        }

        Set<UUID> regionUuids = domain.getUniqueIds();
        if (regionUuids != null && !regionUuids.isEmpty()) {
            for (UUID uuid : regionUuids) {
                if (uuid != null && members.uuids.contains(uuid)) {
                    return new MatchResult(sourcePrefix + "_uuid", uuid.toString());
                }
            }
        }

        Set<String> regionPlayers = domain.getPlayers();
        if (regionPlayers != null && !regionPlayers.isEmpty()) {
            for (String name : regionPlayers) {
                if (name == null || name.isBlank()) {
                    continue;
                }

                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (members.names.contains(normalized)) {
                    return new MatchResult(sourcePrefix + "_name", name);
                }
            }
        }

        return null;
    }

    private ResolvedMembers resolveMembers(List<String> rawMembers) {
        ResolvedMembers result = new ResolvedMembers();

        if (rawMembers == null || rawMembers.isEmpty()) {
            return result;
        }

        for (String rawName : rawMembers) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }

            String trimmed = rawName.trim();
            result.names.add(trimmed.toLowerCase(Locale.ROOT));

            try {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(trimmed);
                if (offlinePlayer != null && offlinePlayer.getUniqueId() != null) {
                    result.uuids.add(offlinePlayer.getUniqueId());
                } else {
                    result.unresolved.add(trimmed);
                }
            } catch (Exception exception) {
                result.unresolved.add(trimmed);
                if (config.isVerboseSync()) {
                    plugin.getLogger().warning("Failed to resolve UUID for nation member " + trimmed + ": " + exception.getMessage());
                }
            }
        }

        return result;
    }

    private long calculate2d(ProtectedRegion region) {
        long minX = region.getMinimumPoint().x();
        long maxX = region.getMaximumPoint().x();
        long minZ = region.getMinimumPoint().z();
        long maxZ = region.getMaximumPoint().z();

        long width = (maxX - minX) + 1L;
        long length = (maxZ - minZ) + 1L;
        if (width <= 0L || length <= 0L) {
            return 0L;
        }
        return width * length;
    }

    private long calculate3d(ProtectedRegion region) {
        long minX = region.getMinimumPoint().x();
        long maxX = region.getMaximumPoint().x();
        long minY = region.getMinimumPoint().y();
        long maxY = region.getMaximumPoint().y();
        long minZ = region.getMinimumPoint().z();
        long maxZ = region.getMaximumPoint().z();

        long width = (maxX - minX) + 1L;
        long height = (maxY - minY) + 1L;
        long length = (maxZ - minZ) + 1L;
        if (width <= 0L || height <= 0L || length <= 0L) {
            return 0L;
        }
        return width * height * length;
    }

    private static final class ResolvedMembers {
        private final Set<UUID> uuids = new HashSet<>();
        private final Set<String> names = new HashSet<>();
        private final List<String> unresolved = new ArrayList<>();
    }

    private static final class MatchResult {
        private final String matchType;
        private final String matchedValue;

        private MatchResult(String matchType, String matchedValue) {
            this.matchType = matchType;
            this.matchedValue = matchedValue;
        }
    }

    public static final class TerritoryDebugReport {
        public final String slug;
        public final String source;
        public final int manualValue;
        public final String countMode;
        public final boolean fallbackToManual;

        public int membersChecked = 0;
        public int memberUuidsResolved = 0;
        public int memberNamesResolved = 0;
        public int regionsScanned = 0;
        public int worldguardValue = 0;
        public int finalValue = 0;
        public String resolutionMode = null;
        public String error = null;
        public final List<String> unresolvedMembers = new ArrayList<>();
        public final List<TerritoryMatch> matches = new ArrayList<>();

        public TerritoryDebugReport(
                String slug,
                String source,
                int manualValue,
                String countMode,
                boolean fallbackToManual
        ) {
            this.slug = slug;
            this.source = source;
            this.manualValue = manualValue;
            this.countMode = countMode;
            this.fallbackToManual = fallbackToManual;
        }
    }

    public record TerritoryMatch(
            String worldName,
            String regionId,
            String matchType,
            String matchedValue,
            long contributedArea,
            String countMode
    ) {
    }
}
