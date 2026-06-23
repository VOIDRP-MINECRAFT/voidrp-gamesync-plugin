package ru.voidrp.gamesync.service;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.ModdedShopEntry;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public final class ModdedShopConfig {

    private final Map<String, ModdedShopEntry> entries;

    public ModdedShopConfig(VoidRpGameSyncPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "modded_items.yml");
        if (!file.exists()) {
            plugin.saveResource("modded_items.yml", false);
        }

        Map<String, ModdedShopEntry> map = new HashMap<>();
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection items = cfg.getConfigurationSection("items");
            if (items != null) {
                // Bukkit parses dots in YAML keys as nested path separators.
                // Use getKeys(true) to walk all deep paths, detect entries by ".id" suffix.
                for (String deepKey : items.getKeys(true)) {
                    if (!deepKey.endsWith(".id")) continue;
                    String entryPath = deepKey.substring(0, deepKey.length() - 3);
                    String id      = items.getString(deepKey, "");
                    String display = items.getString(entryPath + ".display", id);
                    double buy     = items.getDouble(entryPath + ".buy", 0);
                    double sell    = items.getDouble(entryPath + ".sell", 0);
                    boolean locked = items.getBoolean(entryPath + ".locked", false);
                    if (!id.isEmpty()) {
                        map.put(entryPath, new ModdedShopEntry(id, display, buy, sell, locked));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load modded_items.yml: " + e.getMessage(), e);
        }

        this.entries = Collections.unmodifiableMap(map);
        plugin.getLogger().info("Modded shop: " + this.entries.size() + " items loaded.");
    }

    public ModdedShopEntry get(String itemPath) {
        return entries.get(itemPath);
    }

    public int size() {
        return entries.size();
    }
}
