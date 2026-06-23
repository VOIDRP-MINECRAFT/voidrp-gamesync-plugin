package ru.voidrp.gamesync.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
