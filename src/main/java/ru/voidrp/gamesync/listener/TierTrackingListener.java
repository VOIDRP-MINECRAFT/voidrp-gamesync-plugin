package ru.voidrp.gamesync.listener;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

public final class TierTrackingListener implements Listener {

    private static final Map<String, String> TIER_GATES = Map.of(
        "kubejs:andesite_machine_frame", "create_age",
        "mekanism:steel_casing",         "mekanism_age",
        "ae2:controller",                "ae2_age",
        "kubejs:quantum_circuit",        "quantum_age",
        "kubejs:reactor_heart",          "reactor_age",
        "draconicevolution:draconium_core", "draconic_age",
        "avaritia:infinity_catalyst",    "endgame"
    );

    private static final Map<String, String> TIER_ANNOUNCE = Map.of(
        "create_age",   "открыл Эпоху механизмов!",
        "mekanism_age", "открыл Эпоху стали!",
        "ae2_age",      "открыл Эпоху автоматизации!",
        "quantum_age",  "открыл Квантовую эпоху!",
        "reactor_age",  "открыл Эпоху реакторов!",
        "draconic_age", "открыл Эпоху дракона!",
        "endgame",      "достиг Эндгейма!"
    );

    private final VoidRpGameSyncPlugin plugin;

    public TierTrackingListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        var result = event.getRecipe().getResult();
        var key = result.getType().getKey().toString();

        var tierName = TIER_GATES.get(key);
        if (tierName == null) return;

        UUID uuid = player.getUniqueId();
        if (plugin.getDataStore().getTierUnlocked(uuid, tierName)) return;

        plugin.getDataStore().setTierUnlocked(uuid, tierName);
        plugin.getDataStore().saveNow();

        String playerName = player.getName();
        String announce = TIER_ANNOUNCE.getOrDefault(tierName, "открыл новую эпоху!");

        plugin.getLogger().info("[TierTracking] " + playerName + " unlocked " + tierName);
        Bukkit.broadcastMessage("§6[VoidRP] §f" + playerName + " §a" + announce);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().reportTierUnlock(uuid.toString(), playerName, tierName);
                plugin.getLogger().info("[TierTracking] Backend confirmed " + tierName + " for " + playerName);
            } catch (IOException | InterruptedException e) {
                plugin.getLogger().warning("[TierTracking] Backend report failed for " + playerName + ": " + e.getMessage());
            }
        });
    }
}
