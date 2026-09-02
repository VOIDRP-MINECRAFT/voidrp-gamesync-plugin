package ru.voidrp.gamesync.listener;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Removes forbidden items from players and tells them in chat. Catches every acquisition
 * path via a periodic full scan + on-join + on-pickup + on-inventory-click. Item ids come
 * from config ({@code banned-items}), matched by their namespaced key (e.g.
 * {@code reliquary:rod_of_lyssa} — the Rod of Lyssa, which steals from other inventories).
 */
public final class BannedItemsListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;
    private final Set<String> banned = new HashSet<>();
    private final String message;

    public BannedItemsListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        List<String> ids = plugin.getConfig().getStringList("banned-items.ids");
        if (ids == null || ids.isEmpty()) {
            ids = List.of("reliquary:rod_of_lyssa");   // sane default even without config
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) banned.add(id.trim().toLowerCase(Locale.ROOT));
        }
        this.message = plugin.getConfig().getString("banned-items.message",
                "§c⛔ Предмет запрещён на сервере и был удалён из инвентаря.");
    }

    /** Start the periodic full-inventory sweep (catch-all for any acquisition path). */
    public void start() {
        if (banned.isEmpty()) return;
        long period = Math.max(40L, plugin.getConfig().getLong("banned-items.scan-period-ticks", 100L)); // ~5s
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) scan(p);
        }, 100L, period);
    }

    private boolean isBanned(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        try {
            return banned.contains(it.getType().getKey().toString().toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Strip every banned item from the player; message once if anything was removed. */
    public void scan(Player p) {
        if (banned.isEmpty() || p == null) return;
        boolean removed = false;
        Inventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();          // storage + hotbar (+ armor/offhand slots)
        for (int i = 0; i < contents.length; i++) {
            if (isBanned(contents[i])) { inv.setItem(i, null); removed = true; }
        }
        if (isBanned(p.getItemOnCursor())) { p.setItemOnCursor(null); removed = true; }
        if (removed) {
            p.updateInventory();
            p.sendMessage(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> scan(e.getPlayer()), 10L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isBanned(e.getItem().getItemStack())) {
            e.setCancelled(true);
            e.getItem().remove();
            p.sendMessage(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (isBanned(e.getCurrentItem()) || isBanned(e.getCursor())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> scan(p), 1L);
        }
    }
}
