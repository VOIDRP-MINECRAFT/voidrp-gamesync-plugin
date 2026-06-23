package ru.voidrp.gamesync.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationMarketListing;
import ru.voidrp.gamesync.service.NationMarketGuiService.MarketHolder;

public final class NationMarketGuiListener implements Listener {
    private final VoidRpGameSyncPlugin plugin;

    public NationMarketGuiListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MarketHolder marketHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
            return;
        }
        NationMarketListing listing = marketHolder.listingAt(event.getRawSlot());
        if (listing == null) {
            return;
        }
        plugin.getNationMarketGuiService().handleClick(player, listing, event.getClick());
    }
}
