package ru.voidrp.gamesync.service;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.milkbowl.vault.economy.EconomyResponse;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationMarketListing;
import ru.voidrp.gamesync.model.NationMarketListingListResponse;
import ru.voidrp.gamesync.model.NationMarketPurchaseRequest;
import ru.voidrp.gamesync.model.NationMarketPurchaseResponse;

public final class NationMarketGuiService {
    private final VoidRpGameSyncPlugin plugin;
    private final ItemStackSnapshotService itemCodec;
    private final NationMarketInventoryService inventoryService;

    public NationMarketGuiService(
            VoidRpGameSyncPlugin plugin,
            ItemStackSnapshotService itemCodec,
            NationMarketInventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.itemCodec = itemCodec;
        this.inventoryService = inventoryService;
    }

    public void open(Player player) {
        player.sendMessage("§7Загружаем рынок государств...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationMarketListingListResponse response = plugin.getBackendClient().listNationMarketListings();
                List<NationMarketListing> listings = response == null || response.items == null
                        ? List.of()
                        : response.items;
                Bukkit.getScheduler().runTask(plugin, () -> openLoaded(player, listings));
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cНе удалось загрузить рынок: §f" + exception.getMessage()));
            }
        });
    }

    private void openLoaded(Player player, List<NationMarketListing> listings) {
        MarketHolder holder = new MarketHolder(new ArrayList<>(listings));
        Inventory inventory = Bukkit.createInventory(holder, 54, ChatColor.DARK_GREEN + plugin.getGameSyncConfig().getNationMarketGuiTitle());
        holder.inventory = inventory;

        int slot = 0;
        for (NationMarketListing listing : listings) {
            if (slot >= 45) break;
            inventory.setItem(slot, iconFor(player, listing));
            slot++;
        }

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§aРынок государств");
            meta.setLore(List.of(
                    "§7ЛКМ: купить 1",
                    "§7ПКМ: купить 16",
                    "§7Shift + ЛКМ: купить 64",
                    "§7Shift + ПКМ: купить весь остаток",
                    "§8Цены динамически двигаются вместе с рынком."
            ));
            info.setItemMeta(meta);
        }
        inventory.setItem(49, info);
        player.openInventory(inventory);
    }

    private ItemStack iconFor(Player viewer, NationMarketListing listing) {
        ItemStack icon;
        try {
            icon = itemCodec.deserialize(listing.item_stack_base64, Math.max(1, Math.min(64, listing.remaining_amount)));
        } catch (Exception exception) {
            icon = new ItemStack(Material.BARRIER);
        }

        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            String name = listing.display_name != null && !listing.display_name.isBlank()
                    ? listing.display_name
                    : readableMaterial(listing.material);
            meta.setDisplayName("§f" + name + " §8#" + shortId(listing.id));
            boolean ownListing = isSamePlayer(viewer.getName(), listing.seller_player_name);
            if (ownListing) {
                meta.setLore(List.of(
                        "§7Государство: §b" + safe(listing.nation_title) + " §8[" + safe(listing.nation_tag) + "]",
                        "§7Выставил: §f" + safe(listing.seller_player_name),
                        "§7Осталось: §a" + listing.remaining_amount + "§7/§f" + listing.total_amount,
                        "§7Цена сейчас: §6" + money(listing.current_unit_price) + " §7за шт.",
                        "§7Стартовая цена: §f" + money(listing.anchor_unit_price),
                        "§7Множитель лота: §f" + money(listing.relative_price_multiplier) + "x",
                        "",
                        "§cЭто твой лот — покупать его нельзя.",
                        "§7Чтобы вернуть остаток: §f/nmarket cancel " + safe(listing.id)
                ));
            } else {
                meta.setLore(List.of(
                        "§7Государство: §b" + safe(listing.nation_title) + " §8[" + safe(listing.nation_tag) + "]",
                        "§7Выставил: §f" + safe(listing.seller_player_name),
                        "§7Осталось: §a" + listing.remaining_amount + "§7/§f" + listing.total_amount,
                        "§7Цена сейчас: §6" + money(listing.current_unit_price) + " §7за шт.",
                        "§7Стартовая цена: §f" + money(listing.anchor_unit_price),
                        "§7Множитель лота: §f" + money(listing.relative_price_multiplier) + "x",
                        "",
                        "§eЛКМ §7купить 1, §eПКМ §7купить 16",
                        "§eShift+ЛКМ §7купить 64, §eShift+ПКМ §7купить всё"
                ));
            }
            meta.addItemFlags(ItemFlag.values());
            icon.setItemMeta(meta);
        }
        return icon;
    }

    public void handleClick(Player player, NationMarketListing listing, ClickType click) {
        int amount = switch (click) {
            case LEFT -> 1;
            case RIGHT -> 16;
            case SHIFT_LEFT -> 64;
            case SHIFT_RIGHT -> listing.remaining_amount;
            default -> 0;
        };
        if (amount <= 0) return;
        amount = Math.min(amount, listing.remaining_amount);
        if (amount <= 0) {
            player.sendMessage("§cЭтот лот уже распродан.");
            return;
        }

        if (isSamePlayer(player.getName(), listing.seller_player_name)) {
            player.sendMessage("§cНельзя покупать свой же лот.");
            player.sendMessage("§7Чтобы вернуть непроданный остаток, используй: §f/nmarket cancel " + safe(listing.id));
            return;
        }

        if (plugin.getEconomy() == null) {
            player.sendMessage("§cVault экономика недоступна.");
            return;
        }

        double expectedUnitPrice = round2(listing.current_unit_price);
        double expectedTotal = round2(expectedUnitPrice * amount);
        if (plugin.getEconomy().getBalance(player) < expectedTotal) {
            player.sendMessage("§cНедостаточно средств. Нужно: §f" + money(expectedTotal));
            return;
        }

        player.closeInventory();
        final int finalAmount = amount;
        player.sendMessage("§7Покупаем на рынке государств...");

        EconomyResponse withdraw = plugin.getEconomy().withdrawPlayer(player, expectedTotal);
        if (withdraw == null || !withdraw.transactionSuccess()) {
            player.sendMessage("§cНе удалось списать деньги.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationMarketPurchaseResponse response = plugin.getBackendClient().purchaseNationMarketListing(
                        listing.id,
                        new NationMarketPurchaseRequest(player.getName(), finalAmount, expectedUnitPrice)
                );

                Bukkit.getScheduler().runTask(plugin, () -> {
                    double actualTotal = round2(response.gross_total);
                    if (actualTotal < expectedTotal) {
                        plugin.getEconomy().depositPlayer(player, round2(expectedTotal - actualTotal));
                    } else if (actualTotal > expectedTotal) {
                        double diff = round2(actualTotal - expectedTotal);
                        EconomyResponse extra = plugin.getEconomy().withdrawPlayer(player, diff);
                        if (extra == null || !extra.transactionSuccess()) {
                            // Backend committed — must still give items to avoid item loss.
                            // Absorb the small price difference (≤2% by backend contract).
                            plugin.getLogger().warning("Could not collect extra " + diff + " from " + player.getName() + " for nation market purchase. Absorbed.");
                        }
                    }

                    ItemStack item = itemCodec.deserialize(response.item_stack_base64, 1);
                    inventoryService.giveOrDrop(player, item, response.purchased_amount);
                    player.sendMessage("§aКуплено: §f" + readableMaterial(response.listing.material)
                            + " §7x§f" + response.purchased_amount
                            + " §7за §6" + money(response.gross_total));
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getEconomy().depositPlayer(player, expectedTotal);
                    player.sendMessage("§cПокупка не прошла. Деньги возвращены.");
                    player.sendMessage("§8Причина: " + exception.getMessage());
                });
            }
        });
    }

    public static final class MarketHolder implements InventoryHolder {
        private final List<NationMarketListing> listings;
        private Inventory inventory;

        public MarketHolder(List<NationMarketListing> listings) {
            this.listings = listings;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public NationMarketListing listingAt(int slot) {
            if (slot < 0 || slot >= listings.size() || slot >= 45) return null;
            return listings.get(slot);
        }
    }

    private String safe(String value) { return value == null ? "" : value; }
    private boolean isSamePlayer(String left, String right) { return left != null && right != null && left.equalsIgnoreCase(right); }
    private String shortId(String value) { return value == null || value.length() < 8 ? "?" : value.substring(0, 8); }
    private String readableMaterial(String material) { return material == null ? "ITEM" : material.toLowerCase(java.util.Locale.ROOT).replace('_', ' '); }
    private double round2(double value) { return Math.round(value * 100.0D) / 100.0D; }
    private String money(double value) { return String.format(java.util.Locale.US, "%.2f", round2(value)); }
}
