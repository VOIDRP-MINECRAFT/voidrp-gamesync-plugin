package ru.voidrp.gamesync.listener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Removes forbidden items from players and tells them in chat. Catches every acquisition
 * path via a periodic full scan + on-join + on-pickup + on-inventory-click.
 *
 * <p>The list is set in the admin panel (void-rp.ru/admin → «Бан предметов») per server and
 * fetched from the backend every {@link #POLL_SECONDS} s, applied on the fly. Until the first
 * answer, and whenever the backend is down, the last known list is used — at startup the one
 * in {@code config.yml} ({@code banned-items}). At startup the plugin also reports every item id
 * this server has, so the admin search offers only real items.
 */
public final class BannedItemsListener implements Listener {

    private static final long POLL_SECONDS = 30L;

    private final VoidRpGameSyncPlugin plugin;
    // Replaced whole, never mutated: read on the main thread, swapped from the poll thread.
    private volatile Set<String> banned;
    private volatile String message;
    private long scanPeriod;
    private BukkitTask scanTask;
    private volatile String lastPollError;

    public BannedItemsListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        Set<String> fromConfig = new HashSet<>();
        for (String id : plugin.getConfig().getStringList("banned-items.ids")) {
            if (id != null && !id.isBlank()) fromConfig.add(id.trim().toLowerCase(Locale.ROOT));
        }
        this.banned = Set.copyOf(fromConfig);
        this.message = plugin.getConfig().getString("banned-items.message",
                "§c⛔ Предмет запрещён на сервере и был удалён из инвентаря.");
        this.scanPeriod = Math.max(40L, plugin.getConfig().getLong("banned-items.scan-period-ticks", 100L));
    }

    /** Starts the periodic sweep, the list poll and the one-off registry report. */
    public void start() {
        scheduleScan();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 20L * 5, 20L * POLL_SECONDS);
        // Late enough for every mod to have registered its items.
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::reportRegistry, 20L * 20);
    }

    private void scheduleScan() {
        if (scanTask != null) scanTask.cancel();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (banned.isEmpty()) return;
            for (Player p : Bukkit.getOnlinePlayers()) scan(p);
        }, 100L, scanPeriod);
    }

    private String version() {
        return plugin.getDescription().getVersion();
    }

    /** Takes the list from the backend; keeps the current one if the backend fails. */
    private void poll() {
        try {
            JsonObject o = plugin.getBackendClient().fetchItemBans(version());
            Set<String> next = new HashSet<>();
            if (o != null && o.has("ids")) {
                for (JsonElement e : o.getAsJsonArray("ids")) next.add(e.getAsString().toLowerCase(Locale.ROOT));
            }
            if (!next.equals(banned)) {
                plugin.getLogger().info("[BannedItems] Список из админки: " + next.size() + " предм. " + new ArrayList<>(next));
            }
            banned = Set.copyOf(next);
            if (o != null && o.has("message") && !o.get("message").isJsonNull()) message = o.get("message").getAsString();
            if (o != null && o.has("scan_period_ticks")) {
                long period = Math.max(40L, o.get("scan_period_ticks").getAsLong());
                if (period != scanPeriod) {
                    scanPeriod = period;
                    Bukkit.getScheduler().runTask(plugin, this::scheduleScan);
                }
            }
            if (lastPollError != null) {
                plugin.getLogger().info("[BannedItems] Связь с админкой восстановлена");
                lastPollError = null;
            }
        } catch (Exception ex) {
            String text = ex.getClass().getSimpleName() + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            if (!text.equals(lastPollError)) {
                plugin.getLogger().warning("[BannedItems] Не удалось взять список из админки, работаю по прежнему ("
                        + banned.size() + " предм.): " + text);
                lastPollError = text;
            }
        }
    }

    /** Every item this server knows, as the admin search should offer them. */
    private void reportRegistry() {
        try {
            List<String> ids = new ArrayList<>();
            for (Material m : Material.values()) {
                try {
                    if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                    ids.add(m.getKey().toString().toLowerCase(Locale.ROOT));
                } catch (Throwable ignored) {
                    // a hybrid core can expose a Material without a usable key
                }
            }
            plugin.getBackendClient().reportItemRegistry(ids, version());
            plugin.getLogger().info("[BannedItems] Реестр предметов отправлен в админку: " + ids.size());
        } catch (Exception ex) {
            plugin.getLogger().warning("[BannedItems] Не удалось отправить реестр предметов: " + ex.getMessage());
        }
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
