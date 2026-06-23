package ru.voidrp.gamesync.service;

import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

public final class NationMarketInventoryService {
    private final VoidRpGameSyncPlugin plugin;

    public NationMarketInventoryService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public int countSimilar(Player player, ItemStack sample) {
        if (player == null || sample == null) return 0;
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(sample)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public boolean removeSimilar(Player player, ItemStack sample, int amount) {
        if (player == null || sample == null || amount <= 0) return false;
        if (countSimilar(player, sample) < amount) return false;

        PlayerInventory inventory = player.getInventory();
        int left = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.isSimilar(sample)) continue;
            int take = Math.min(left, item.getAmount());
            item.setAmount(item.getAmount() - take);
            left -= take;
            if (item.getAmount() <= 0) {
                contents[i] = null;
            } else {
                contents[i] = item;
            }
        }
        inventory.setStorageContents(contents);
        player.updateInventory();
        return left <= 0;
    }

    public void giveOrDrop(Player player, ItemStack sample, int amount) {
        if (player == null || sample == null || amount <= 0) return;
        int left = amount;
        while (left > 0) {
            ItemStack stack = sample.clone();
            int stackAmount = Math.min(left, Math.max(1, stack.getMaxStackSize()));
            stack.setAmount(stackAmount);
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) {
                Location location = player.getLocation();
                for (ItemStack leftover : leftovers.values()) {
                    player.getWorld().dropItemNaturally(location, leftover);
                }
                player.sendMessage("§eИнвентарь заполнен. Часть предметов выпала рядом с тобой.");
            }
            left -= stackAmount;
        }
        player.updateInventory();
    }

    public String validateItemAllowed(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "Возьми предмет в руку.";
        }

        String typeName = item.getType().name();
        if (!plugin.getGameSyncConfig().isNationMarketAllowShulkerBoxes() && typeName.endsWith("SHULKER_BOX")) {
            return "Шалкеры нельзя выставлять на рынок на первом этапе.";
        }
        if (!plugin.getGameSyncConfig().isNationMarketAllowBundles() && typeName.equals("BUNDLE")) {
            return "Мешки нельзя выставлять на рынок на первом этапе.";
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }

        if (!plugin.getGameSyncConfig().isNationMarketAllowEnchantedItems() && !item.getEnchantments().isEmpty()) {
            return "Зачарованные предметы пока нельзя выставлять на рынок.";
        }
        if (!plugin.getGameSyncConfig().isNationMarketAllowDamagedItems() && meta instanceof Damageable damageable && damageable.hasDamage() && damageable.getDamage() > 0) {
            return "Повреждённые предметы пока нельзя выставлять на рынок.";
        }
        if (!plugin.getGameSyncConfig().isNationMarketAllowCustomItems()) {
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasCustomModelData() || meta instanceof BlockStateMeta) {
                return "Кастомные/NBT предметы пока нельзя выставлять на рынок.";
            }
        }

        return null;
    }
}
