package ru.voidrp.gamesync.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Feeds the in-game roadmap ("путеводитель"): every 30 s looks at online inventories for the
 * roadmap's key items and reports the ones a player holds for the first time.
 *
 * <p>Same detection as {@link EpochService} — inventory, not craft events, because most key
 * items come out of modded machines and multiblocks. Already-reported items are remembered in
 * data.yml ({@code guide-items.<uuid>}) so each is sent once; a failed push is retried on the
 * next scan because the local mark is set only after the backend accepted it.
 */
public final class GuideItemTracker {

    private final VoidRpGameSyncPlugin plugin;
    private volatile Set<String> tracked = Set.of();
    private final Map<UUID, Boolean> pushing = new ConcurrentHashMap<>();

    public GuideItemTracker(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("guide.track-items", true)) return;
        fetchTracked();
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 20L * 45, 20L * 30);
    }

    private volatile long lastFetchAttempt;

    /** The list comes from the backend; if it was down at startup, scan() retries every few minutes. */
    private void fetchTracked() {
        lastFetchAttempt = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> items = plugin.getBackendClient().fetchGuideTrackedItems();
                tracked = Set.copyOf(items);
                plugin.getLogger().info("[Guide] tracking " + items.size() + " roadmap items");
            } catch (Exception e) {
                plugin.getLogger().warning("[Guide] could not fetch tracked items: " + e.getMessage());
            }
        });
    }

    private void scan() {
        if (tracked.isEmpty()) {
            if (System.currentTimeMillis() - lastFetchAttempt > 3 * 60_000L) fetchTracked();
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (pushing.containsKey(id)) continue;
            Set<String> fresh = new HashSet<>();
            for (ItemStack stack : p.getInventory().getContents()) {
                if (stack == null || stack.getType().isAir()) continue;
                String itemId = stack.getType().getKey().toString().toLowerCase(Locale.ROOT);
                if (!tracked.contains(itemId)) {
                    // Mohist may expose a modded item as Material NAMESPACE_ITEM instead of its key
                    itemId = fromMaterialName(stack.getType().name());
                    if (itemId == null) continue;
                }
                if (!plugin.getDataStore().isGuideItemSeen(id, itemId)) fresh.add(itemId);
            }
            if (fresh.isEmpty()) continue;
            pushing.put(id, true);
            final String nick = p.getName();
            final List<String> batch = new ArrayList<>(fresh);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getBackendClient().pushGuideItems(id.toString(), nick, batch);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        batch.forEach(i -> plugin.getDataStore().setGuideItemSeen(id, i));
                        plugin.getDataStore().saveNow();
                    });
                } catch (Exception e) {
                    plugin.getLogger().warning("[Guide] push failed for " + nick + ": " + e.getMessage());
                } finally {
                    pushing.remove(id);
                }
            });
        }
    }

    private String fromMaterialName(String materialName) {
        for (String id : tracked) {
            if (id.toUpperCase(Locale.ROOT).replace(":", "_").replace("-", "_").equals(materialName)) return id;
        }
        return null;
    }
}
