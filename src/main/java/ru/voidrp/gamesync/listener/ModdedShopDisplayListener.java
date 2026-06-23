package ru.voidrp.gamesync.listener;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.ModdedShopEntry;
import ru.voidrp.gamesync.service.ModdedShopConfig;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Replaces vanilla placeholder items in the ModdedItems shop GUI
 * with actual modded ItemStacks so players see the real item icon.
 *
 * Detection: title contains "компоненты" or "модов" (case-insensitive,
 *   matches "Модовые компоненты" and "Ресурсы модов" sections).
 * Identification: lore line matching "namespace:itemid" pattern.
 * Creation: Paper ItemType registry → Mohist returns real NeoForge ItemStack.
 */
public final class ModdedShopDisplayListener implements Listener {

    private static final Pattern MODDED_ID_PATTERN =
            Pattern.compile("^[a-z0-9_]+:[a-z0-9_/]+$");

    private final VoidRpGameSyncPlugin plugin;
    private final ModdedShopConfig moddedShopConfig;

    public ModdedShopDisplayListener(VoidRpGameSyncPlugin plugin, ModdedShopConfig moddedShopConfig) {
        this.plugin = plugin;
        this.moddedShopConfig = moddedShopConfig;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        String rawTitle = event.getView().getOriginalTitle();
        String lower = rawTitle.toLowerCase();
        if (!lower.contains("компоненты") && !lower.contains("модов")) return;

        Inventory inv = event.getInventory();
        // 1-tick delay: ESGUI fills items async after the open event
        Bukkit.getScheduler().runTaskLater(plugin, () -> replaceDisplayItems(inv), 1L);
    }

    private void replaceDisplayItems(Inventory inv) {
        int navBarStart = inv.getSize() - 9;
        for (int slot = 0; slot < navBarStart; slot++) {
            ItemStack display = inv.getItem(slot);
            if (display == null || !display.hasItemMeta()) continue;
            ItemMeta meta = display.getItemMeta();
            if (meta == null) continue;

            String moddedId = extractModdedId(meta);
            if (moddedId == null) continue;

            ItemStack realItem = createFromRegistry(moddedId);
            if (realItem == null) continue;

            // Keep ESGUI's display name and lore, just swap the item type for icon
            ItemMeta realMeta = realItem.getItemMeta();
            if (realMeta == null) continue;
            realMeta.setDisplayName(meta.getDisplayName());
            realMeta.setLore(meta.getLore());
            realItem.setItemMeta(realMeta);
            inv.setItem(slot, realItem);
        }
    }

    /** Extract "namespace:itemid" from lore (e.g. "§8kubejs:rough_mechanism"). */
    private String extractModdedId(ItemMeta meta) {
        List<String> lore = meta.getLore();
        if (lore == null) return null;
        for (String line : lore) {
            String stripped = org.bukkit.ChatColor.stripColor(line).trim();
            if (MODDED_ID_PATTERN.matcher(stripped).matches()
                    && !stripped.startsWith("minecraft:")) {
                return stripped;
            }
        }
        return null;
    }

    /** Try Paper's ItemType registry — Mohist registers all NeoForge items here. */
    @SuppressWarnings("UnstableApiUsage")
    private ItemStack createFromRegistry(String moddedId) {
        try {
            NamespacedKey key = NamespacedKey.fromString(moddedId);
            if (key == null) return null;
            org.bukkit.Registry<ItemType> registry = Bukkit.getRegistry(ItemType.class);
            if (registry == null) {
                plugin.getLogger().warning("ModdedShopDisplay: ItemType registry is null");
                return null;
            }
            ItemType type = registry.get(key);
            if (type == null) {
                plugin.getLogger().warning("ModdedShopDisplay: ItemType not found for " + moddedId);
                return null;
            }
            return type.createItemStack();
        } catch (Exception e) {
            plugin.getLogger().warning("ModdedShopDisplay: failed to create item " + moddedId + ": " + e.getMessage());
            return null;
        }
    }
}
