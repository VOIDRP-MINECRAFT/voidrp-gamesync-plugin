package ru.voidrp.gamesync.config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import ru.voidrp.gamesync.model.GameNationListResponse;
import ru.voidrp.gamesync.model.GameNationSnapshot;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.service.BackendClient;
import ru.voidrp.gamesync.util.NameNormalizer;

public final class NationRegistry {

    private final JavaPlugin plugin;
    private final BackendClient backendClient;
    private final GameSyncConfig config;
    private final Map<String, NationDefinition> nations = new TreeMap<>();
    private String source = "empty";

    public NationRegistry(JavaPlugin plugin, BackendClient backendClient, GameSyncConfig config) {
        this.plugin = plugin;
        this.backendClient = backendClient;
        this.config = config;
        refresh();
    }

    public synchronized void refresh() {
        Map<String, NationDefinition> loaded = new TreeMap<>();

        if (config.isBackendNationSourceEnabled()) {
            try {
                GameNationListResponse response = backendClient.fetchNationDefinitions();
                if (response != null && response.items != null && !response.items.isEmpty()) {
                    for (GameNationSnapshot snapshot : response.items) {
                        NationDefinition def = fromSnapshot(snapshot);
                        if (def != null) loaded.put(def.slug(), def);
                    }
                    if (!loaded.isEmpty()) {
                        nations.clear();
                        nations.putAll(loaded);
                        source = "backend";
                        plugin.getLogger().info("NationRegistry loaded " + nations.size() + " nations from backend.");
                        return;
                    }
                }
            } catch (IOException | InterruptedException exception) {
                plugin.getLogger().warning("Failed to load nations from backend: " + exception.getMessage());
            } catch (Exception exception) {
                plugin.getLogger().warning("Unexpected backend nation load error: " + exception.getMessage());
            }
        }

        if (config.isFallbackYmlEnabled()) {
            loaded = loadFromYaml();
            nations.clear();
            nations.putAll(loaded);
            source = "yml";
            plugin.getLogger().info("NationRegistry loaded " + nations.size() + " nations from fallback nations.yml.");
            return;
        }

        nations.clear();
        source = "empty";
        plugin.getLogger().warning("NationRegistry is empty: backend load failed and fallback yml disabled.");
    }

    public synchronized List<NationDefinition> all() { return new ArrayList<>(nations.values()); }

    public synchronized NationDefinition get(String slug) {
        if (slug == null) return null;
        return nations.get(slug.toLowerCase(Locale.ROOT));
    }

    public synchronized NationDefinition findByPlayer(String minecraftNickname) {
        if (minecraftNickname == null || minecraftNickname.isBlank()) return null;
        for (NationDefinition definition : nations.values()) {
            if (definition.contains(minecraftNickname)) return definition;
        }
        return null;
    }

    public synchronized String getSource() { return source; }

    private NationDefinition fromSnapshot(GameNationSnapshot snapshot) {
        if (snapshot == null || snapshot.nation_slug == null || snapshot.nation_slug.isBlank()) return null;
        String leader = normalizeOrNull(snapshot.leader_minecraft_nickname);
        List<String> officers = normalizeList(snapshot.officers);
        List<String> members = normalizeList(snapshot.members);
        String tag = snapshot.tag == null || snapshot.tag.isBlank() ? snapshot.title : snapshot.tag;
        Map<String, String> customPrefixes = snapshot.member_custom_prefixes != null
            ? Collections.unmodifiableMap(snapshot.member_custom_prefixes)
            : Collections.emptyMap();

        return new NationDefinition(
            snapshot.nation_slug.toLowerCase(Locale.ROOT),
            snapshot.title == null || snapshot.title.isBlank() ? snapshot.nation_slug : snapshot.title,
            tag,
            leader,
            officers,
            members,
            0, 0, 0, 0,
            snapshot.accent_color,
            snapshot.capital_x,
            snapshot.capital_z,
            snapshot.capital_world,
            customPrefixes
        );
    }

    private Map<String, NationDefinition> loadFromYaml() {
        File file = new File(plugin.getDataFolder(), "nations.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        Map<String, NationDefinition> loaded = new TreeMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("nations");
        if (root == null) return loaded;

        for (String slugKey : root.getKeys(false)) {
            ConfigurationSection nationSection = root.getConfigurationSection(slugKey);
            if (nationSection == null) continue;

            String slug = slugKey.toLowerCase(Locale.ROOT);
            String title = nationSection.getString("title", slug);
            String tag = nationSection.getString("tag", title);
            String leader = normalizeOrNull(nationSection.getString("leader"));
            List<String> officers = normalizeList(nationSection.getStringList("officers"));
            List<String> members = normalizeList(nationSection.getStringList("members"));

            int territoryPoints = nationSection.getInt("manual.territory-points", 0);
            int bossKills = nationSection.getInt("manual.boss-kills", 0);
            int eventsCompleted = nationSection.getInt("manual.events-completed", 0);
            int prestigeBonus = nationSection.getInt("manual.prestige-bonus", 0);

            NationDefinition definition = new NationDefinition(
                slug, title, tag, leader, officers, members,
                territoryPoints, bossKills, eventsCompleted, prestigeBonus,
                null, null, null, null,
                Collections.emptyMap()
            );
            loaded.put(slug, definition);
        }

        return loaded;
    }

    private String normalizeOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        return NameNormalizer.normalizeMinecraftName(value);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            result.add(NameNormalizer.normalizeMinecraftName(value));
        }
        return result;
    }
}
