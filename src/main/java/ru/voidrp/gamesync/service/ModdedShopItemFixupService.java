package ru.voidrp.gamesync.service;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.plugin.Plugin;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Patches EconomyShopGUI ShopItem.itemToGive with real modded ItemStacks.
 *
 * Why: ESGUI's sell path calls ShopItem.match(playerItem) which compares
 * playerItem.getType() against itemToGive.getType(). Proxy materials (IRON_INGOT etc.)
 * never match actual mod items. Patching itemToGive with the real mod ItemStack
 * makes ESGUI find mod items in the player's inventory and fires PreTransactionEvent,
 * which EconomyShopGuiBridgeService then intercepts for dynamic pricing.
 *
 * Detection: lore line matching "§8namespace:itemid" (color code + mod id).
 * Runs once with a 5-tick delay after server enables so ESGUI has finished loading.
 */
public final class ModdedShopItemFixupService {

    private static final Pattern MOD_ID_PATTERN =
            Pattern.compile("^[a-z0-9_]+:[a-z0-9_/]+$");

    private final VoidRpGameSyncPlugin plugin;

    public ModdedShopItemFixupService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void scheduleFixup() {
        Bukkit.getScheduler().runTaskLater(plugin, this::applyFixup, 5L);
    }

    public void scheduleFixupDelayed(long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, this::applyFixup, delayTicks);
    }

    @SuppressWarnings({"unchecked", "UnstableApiUsage"})
    private void applyFixup() {
        Plugin esguiPlugin = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
        if (esguiPlugin == null) esguiPlugin = Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium");
        if (esguiPlugin == null) {
            plugin.getLogger().warning("ModdedShopItemFixup: EconomyShopGUI not found, skipping fixup.");
            return;
        }

        int patched = 0;
        int failed = 0;

        try {
            ClassLoader cl = esguiPlugin.getClass().getClassLoader();
            Class<?> hookClass = Class.forName("me.gypopo.economyshopgui.api.EconomyShopGUIHook", true, cl);
            Method getSections = hookClass.getMethod("getSections");
            Map<String, ?> sections = (Map<String, ?>) getSections.invoke(null);
            if (sections == null) {
                plugin.getLogger().warning("ModdedShopItemFixup: getSections() returned null.");
                return;
            }

            for (Map.Entry<String, ?> sectionEntry : sections.entrySet()) {
                Object section = sectionEntry.getValue();

                Method getShopItems = section.getClass().getMethod("getShopItems");
                Collection<?> shopItems = (Collection<?>) getShopItems.invoke(section);
                if (shopItems == null) continue;

                for (Object shopItem : shopItems) {
                    try {
                        Method getDisplayItem = shopItem.getClass().getMethod("getShopItem");
                        ItemStack displayItem = (ItemStack) getDisplayItem.invoke(shopItem);
                        if (displayItem == null || !displayItem.hasItemMeta()) continue;

                        String moddedId = extractModdedId(displayItem);
                        if (moddedId == null) continue;

                        ItemStack realItem = createFromRegistry(moddedId);
                        if (realItem == null) {
                            plugin.getLogger().warning("ModdedShopItemFixup: item not in registry: " + moddedId);
                            failed++;
                            continue;
                        }

                        Field itemToGiveField = getField(shopItem.getClass(), "itemToGive");
                        if (itemToGiveField == null) {
                            plugin.getLogger().warning("ModdedShopItemFixup: no itemToGive field for " + moddedId);
                            failed++;
                            continue;
                        }
                        itemToGiveField.setAccessible(true);
                        itemToGiveField.set(shopItem, realItem);

                        // Patch the display item type so the GUI shows the real mod item icon.
                        // displayItem is the mutable ItemStack returned by getShopItem() —
                        // changing its type in-place is enough; displayname/lore are preserved.
                        try {
                            displayItem.setType(realItem.getType());
                        } catch (Exception ignored) {}

                        patched++;
                    } catch (Exception e) {
                        failed++;
                        plugin.getLogger().warning("ModdedShopItemFixup: failed to patch item: " + e.getMessage());
                        if (plugin.getGameSyncConfig().isVerboseSync()) {
                            plugin.getLogger().warning("ModdedShopItemFixup: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("ModdedShopItemFixup: reflection error: " + e.getMessage());
            return;
        }

        plugin.getLogger().info("ModdedShopItemFixup: patched " + patched + " items, failed " + failed + ".");
    }

    private String extractModdedId(ItemStack stack) {
        if (!stack.hasItemMeta() || stack.getItemMeta() == null) return null;
        java.util.List<String> lore = stack.getItemMeta().getLore();
        if (lore == null) return null;
        for (String line : lore) {
            String stripped = org.bukkit.ChatColor.stripColor(line).trim();
            if (MOD_ID_PATTERN.matcher(stripped).matches() && !stripped.startsWith("minecraft:")) {
                return stripped;
            }
        }
        return null;
    }

    @SuppressWarnings("UnstableApiUsage")
    private ItemStack createFromRegistry(String moddedId) {
        // Attempt 1: Paper ItemType registry (preferred on Mohist)
        try {
            NamespacedKey key = NamespacedKey.fromString(moddedId);
            if (key != null) {
                org.bukkit.Registry<ItemType> registry = Bukkit.getRegistry(ItemType.class);
                if (registry != null) {
                    ItemType type = registry.get(key);
                    if (type != null) return type.createItemStack();
                }
            }
        } catch (Exception ignored) {}

        // Attempt 2: Bukkit Material by exact namespace:id
        try {
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(moddedId);
            if (mat != null && mat != org.bukkit.Material.AIR) return new ItemStack(mat);
        } catch (Exception ignored) {}

        // Attempt 3: Mohist exposes mod materials as NAMESPACE_ID (colon → underscore, uppercase)
        try {
            String enumName = moddedId.toUpperCase(java.util.Locale.ROOT)
                    .replace(":", "_").replace("-", "_");
            org.bukkit.Material mat = org.bukkit.Material.getMaterial(enumName);
            if (mat != null && mat != org.bukkit.Material.AIR) return new ItemStack(mat);
        } catch (Exception ignored) {}

        // Attempt 4: Scan all registered Materials by NamespacedKey (catches unusual Mohist naming)
        try {
            NamespacedKey key = NamespacedKey.fromString(moddedId);
            if (key != null) {
                for (org.bukkit.Material mat : org.bukkit.Material.values()) {
                    if (key.equals(mat.getKey())) return new ItemStack(mat);
                }
            }
        } catch (Exception ignored) {}

        // Attempt 5: NMS BuiltInRegistries → CraftItemStack (for mods not bridged into Bukkit Material)
        try {
            String[] parts = moddedId.split(":", 2);
            if (parts.length == 2) {
                Class<?> birClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
                Field itemField = birClass.getDeclaredField("ITEM");
                itemField.setAccessible(true);
                Object itemRegistry = itemField.get(null);

                Class<?> rlClass = Class.forName("net.minecraft.resources.ResourceLocation");
                Object rl = null;
                for (String fn : new String[]{"parse", "tryParse", "fromNamespaceAndPath"}) {
                    try {
                        if (fn.equals("fromNamespaceAndPath")) {
                            rl = rlClass.getMethod(fn, String.class, String.class)
                                    .invoke(null, parts[0], parts[1]);
                        } else {
                            rl = rlClass.getMethod(fn, String.class).invoke(null, moddedId);
                        }
                        if (rl != null) break;
                    } catch (Exception ignored2) {}
                }
                if (rl == null) {
                    rl = rlClass.getConstructor(String.class, String.class).newInstance(parts[0], parts[1]);
                }

                Object nmsItem = null;
                for (String mName : new String[]{"getValue", "getOptional", "get"}) {
                    try {
                        Object res = itemRegistry.getClass().getMethod(mName, rlClass)
                                .invoke(itemRegistry, rl);
                        if (res instanceof java.util.Optional<?> opt && opt.isPresent()) {
                            nmsItem = opt.get();
                            break;
                        } else if (res != null && !(res instanceof java.util.Optional<?>)) {
                            nmsItem = res;
                            break;
                        }
                    } catch (Exception ignored2) {}
                }
                // Unwrap Holder.Reference<T> if needed
                if (nmsItem != null && nmsItem.getClass().getSimpleName().equals("Reference")) {
                    try { nmsItem = nmsItem.getClass().getMethod("value").invoke(nmsItem); }
                    catch (Exception ignored2) {}
                }
                if (nmsItem == null) return null;

                Class<?> nmsIsClass = Class.forName("net.minecraft.world.item.ItemStack");
                Class<?> itemLikeClass = Class.forName("net.minecraft.world.level.ItemLike");
                Object nmsIs = nmsIsClass.getConstructor(itemLikeClass).newInstance(nmsItem);

                Class<?> craftIsClass = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
                Method asBukkitCopy = craftIsClass.getMethod("asBukkitCopy", nmsIsClass);
                ItemStack result = (ItemStack) asBukkitCopy.invoke(null, nmsIs);
                if (result != null && result.getType() != org.bukkit.Material.AIR) return result;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("ModdedShopItemFixup NMS attempt failed for " + moddedId
                    + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return null;
    }

    /** getDeclaredField walking up the class hierarchy. */
    private Field getField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }
}
