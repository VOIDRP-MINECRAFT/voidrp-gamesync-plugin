package ru.voidrp.gamesync.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.InventoryHolder;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.service.PlayerMarketGuiService;

public final class PlayerMarketGuiListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerMarketGuiListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onShopCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim();
        String lower = msg.toLowerCase();
        if (lower.equals("/shop") || lower.startsWith("/shop ")
                || lower.equals("/market") || lower.startsWith("/market ")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.getGameSyncConfig().isWebGuiEnabled()) {
                    plugin.getWebGuiBridgeService().openMarket(player);
                } else {
                    plugin.getPlayerMarketGuiService().open(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryHolder holder = event.getInventory().getHolder();
        if (!plugin.getPlayerMarketGuiService().isPlayerMarketGui(holder)) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        plugin.getPlayerMarketGuiService().handleClick(player, slot, event.getClick());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (plugin.getPlayerMarketGuiService().isPlayerMarketGui(holder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (plugin.getPlayerMarketGuiService().isPlayerMarketGui(holder)) {
            plugin.getPlayerMarketGuiService().onClose(player);
        }
    }
}
