package ru.voidrp.gamesync.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import ru.voidrp.gamesync.model.NationDefinition;

public final class DynmapWorldGuardStyleService {

    private static final String SITE_BASE = "https://void-rp.ru/nation/";

    private final JavaPlugin plugin;
    private final Set<String> writtenRegionIds = new HashSet<>();

    public DynmapWorldGuardStyleService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void updateStyles(List<NationDefinition> nations) {
        Plugin dwg = Bukkit.getPluginManager().getPlugin("Dynmap-WorldGuard");
        if (dwg == null || !dwg.isEnabled()) return;

        Map<String, NationDefinition> regionToNation;
        try {
            regionToNation = scanWorldGuardRegions(nations);
        } catch (Exception e) {
            plugin.getLogger().warning("[DynmapWGStyle] WorldGuard scan failed: " + e.getMessage());
            return;
        }

        File configFile = new File(dwg.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        ConfigurationSection custstyle = config.getConfigurationSection("custstyle");
        if (custstyle == null) {
            custstyle = config.createSection("custstyle");
        }

        // Remove entries written in the previous run
        for (String regionId : writtenRegionIds) {
            custstyle.set(regionId, null);
        }
        writtenRegionIds.clear();

        // Write new entries
        for (Map.Entry<String, NationDefinition> entry : regionToNation.entrySet()) {
            String regionId = entry.getKey();
            NationDefinition nation = entry.getValue();
            String color = nation.accentColor();

            custstyle.set(regionId + ".strokeColor", color);
            custstyle.set(regionId + ".strokeOpacity", 0.85);
            custstyle.set(regionId + ".strokeWeight", 2);
            custstyle.set(regionId + ".fillColor", color);
            custstyle.set(regionId + ".fillOpacity", 0.15);
            custstyle.set(regionId + ".infowindow", buildInfoWindow(nation));

            writtenRegionIds.add(regionId);
        }

        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[DynmapWGStyle] Failed to save config: " + e.getMessage());
        }
    }

    private Map<String, NationDefinition> scanWorldGuardRegions(List<NationDefinition> nations) {
        Map<String, NationDefinition> regionToNation = new HashMap<>();

        List<NationDefinition> nationsWithColor = new ArrayList<>();
        for (NationDefinition n : nations) {
            if (n.accentColor() != null && !n.accentColor().isBlank()) {
                nationsWithColor.add(n);
            }
        }
        if (nationsWithColor.isEmpty()) return regionToNation;

        for (World world : Bukkit.getWorlds()) {
            RegionManager rm = WorldGuard.getInstance().getPlatform()
                    .getRegionContainer().get(BukkitAdapter.adapt(world));
            if (rm == null) continue;

            regions:
            for (ProtectedRegion region : rm.getRegions().values()) {
                if ("__global__".equals(region.getId())) continue;

                for (UUID ownerUuid : region.getOwners().getUniqueIds()) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(ownerUuid);
                    String opName = op.getName();
                    if (opName == null) continue;

                    for (NationDefinition nation : nationsWithColor) {
                        if (nation.contains(opName)) {
                            regionToNation.put(region.getId(), nation);
                            continue regions;
                        }
                    }
                }
            }
        }

        return regionToNation;
    }

    private String buildInfoWindow(NationDefinition nation) {
        String color = nation.accentColor();
        String leader = nation.leader() != null ? nation.leader() : "—";
        return "<div class=\"regioninfo\">"
                + "<strong style=\"font-size:110%;color:" + color + "\">"
                + "[" + nation.tag() + "] " + nation.title()
                + "</strong>"
                + "<br/><span style=\"font-size:90%;color:#aaa\">Лидер: <b>" + leader + "</b></span>"
                + "<br/><span style=\"font-size:90%;color:#aaa\">Территория государства</span>"
                + "<br/><a href=\"" + SITE_BASE + nation.slug()
                + "\" target=\"_blank\" style=\"color:#a78bfa;font-size:88%\">→ Государство на сайте</a>"
                + "</div>";
    }
}
