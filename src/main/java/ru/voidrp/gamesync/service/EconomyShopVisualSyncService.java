package ru.voidrp.gamesync.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.MarketPriceItem;

public final class EconomyShopVisualSyncService {
    private static final long SLOW_RELOAD_THRESHOLD_MS = 3_000L;
    private static final long SLOW_RELOAD_COOLDOWN_MS = 30 * 60_000L;

    private final VoidRpGameSyncPlugin plugin;
    private final EconomyMarketCache cache;
    private final Map<String, VisualPrice> lastApplied = new HashMap<>();
    private final ArrayDeque<Long> reloadHistory = new ArrayDeque<>();

    private int taskId = -1;
    private long lastRunMs = 0L;
    private int lastChangedItems = 0;
    private int totalReloads = 0;
    private int pendingChangedItems = 0;
    private String lastMessage = "not started";
    private long slowReloadCooldownUntil = 0L;

    public EconomyShopVisualSyncService(VoidRpGameSyncPlugin plugin, EconomyMarketCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    public void start() {
        stop();
        if (!plugin.getGameSyncConfig().isEconomyMarketEnabled() || !plugin.getGameSyncConfig().isEconomyMarketVisualSyncEnabled()) {
            return;
        }
        long period = Math.max(20L, plugin.getGameSyncConfig().getEconomyMarketVisualSyncPeriodTicks());
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::runOnceSafe, period, period).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public void runOnceSafe() {
        try {
            VisualSyncResult result = runOnce(false);
            lastChangedItems = result.changedItems;
            lastMessage = result.message;
            lastRunMs = System.currentTimeMillis();
            if (plugin.getGameSyncConfig().isVerboseSync() && result.changedItems > 0) {
                plugin.getLogger().info("EconomyShopGUI visual sync: " + result.message);
            }
        } catch (Exception exception) {
            lastMessage = "failed: " + exception.getMessage();
            plugin.getLogger().warning("EconomyShopGUI visual sync failed: " + exception.getMessage());
        }
    }

    public VisualSyncResult runOnce() {
        return runOnce(false);
    }

    public VisualSyncResult runOnce(boolean forceReload) {
        if (!isEconomyShopGuiPresent()) {
            return new VisualSyncResult(0, false, "EconomyShopGUI is not installed");
        }

        int maxPerCycle = plugin.getGameSyncConfig().getEconomyMarketVisualSyncMaxItemsPerCycle();
        int minChanges = plugin.getGameSyncConfig().getEconomyMarketVisualSyncMinChangesBeforeReload();
        double minDiffPercent = plugin.getGameSyncConfig().getEconomyMarketVisualSyncMinDiffPercent();

        int changed = 0;
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        String editCommand = commandRoot(plugin.getGameSyncConfig().getEconomyMarketVisualSyncEditCommand());

        for (MarketPriceItem item : cache.all()) {
            if (changed >= maxPerCycle) break;
            if (!isVisualSyncCandidate(item)) continue;

            VisualPrice next = new VisualPrice(round2(item.current_buy_price), round2(item.current_sell_price));
            VisualPrice previous = lastApplied.get(item.material);
            if (previous != null && !isDifferentEnough(previous, next, minDiffPercent)) {
                continue;
            }

            String section = item.shop_section.trim();
            String index = item.shop_item_index.trim();

            if (next.buy > 0D) {
                Bukkit.dispatchCommand(console, editCommand + " edititem " + section + " " + index + " set buy " + money(next.buy));
            }
            if (next.sell >= 0D) {
                Bukkit.dispatchCommand(console, editCommand + " edititem " + section + " " + index + " set sell " + money(next.sell));
            }

            lastApplied.put(item.material, next);
            changed++;
        }

        if (changed <= 0) {
            return new VisualSyncResult(0, false, "no visual price changes");
        }

        pendingChangedItems += changed;

        if (!forceReload && pendingChangedItems < minChanges) {
            return new VisualSyncResult(changed, false, "changed " + changed + " item(s), pending " + pendingChangedItems + "/" + minChanges + ", reload skipped by threshold");
        }

        if (!canReloadNow()) {
            return new VisualSyncResult(changed, false, "changed " + changed + " item(s), pending " + pendingChangedItems + ", reload skipped by hourly limit");
        }

        if (plugin.getGameSyncConfig().isEconomyMarketVisualSyncRunReload()) {
            long now = System.currentTimeMillis();
            if (!forceReload && now < slowReloadCooldownUntil) {
                long remainingSec = (slowReloadCooldownUntil - now) / 1000L;
                return new VisualSyncResult(changed, false,
                    "changed " + changed + " item(s), reload skipped — slow-reload cooldown active for " + remainingSec + "s more");
            }

            long reloadStart = now;
            Bukkit.dispatchCommand(console, commandRoot(plugin.getGameSyncConfig().getEconomyMarketVisualSyncReloadCommand()));
            long reloadMs = System.currentTimeMillis() - reloadStart;
            now = reloadStart + reloadMs;
            if (reloadMs > SLOW_RELOAD_THRESHOLD_MS) {
                slowReloadCooldownUntil = now + SLOW_RELOAD_COOLDOWN_MS;
                plugin.getLogger().severe(
                    "[VoidRP] /" + plugin.getGameSyncConfig().getEconomyMarketVisualSyncReloadCommand() +
                    " blocked main thread for " + reloadMs + "ms! " +
                    "Check 'advanced-transaction-log' in EconomyShopGUI config.yml — disable it to prevent server hangs. " +
                    "Reload command suppressed for " + (SLOW_RELOAD_COOLDOWN_MS / 60_000L) + " min."
                );
            }
            reloadHistory.addLast(now);
            totalReloads++;
            pendingChangedItems = 0;
            trimReloadHistory(now);
            // Re-apply modded item icon patches after ESGUI reload (reload wipes our setType patches)
            plugin.getModdedShopItemFixupService().scheduleFixupDelayed(10L);
            return new VisualSyncResult(changed, true, "changed " + changed + " item(s), /sreload dispatched in " + reloadMs + "ms" + (forceReload ? " by manual force" : ""));
        }

        return new VisualSyncResult(changed, false, "changed " + changed + " item(s), reload command disabled");
    }

    private boolean isVisualSyncCandidate(MarketPriceItem item) {
        if (item == null || item.material == null) return false;
        if (item.shop_section == null || item.shop_section.isBlank()) return false;
        if (item.shop_item_index == null || item.shop_item_index.isBlank()) return false;
        return item.current_buy_price > 0D || item.current_sell_price > 0D;
    }

    private boolean isEconomyShopGuiPresent() {
        return Bukkit.getPluginManager().getPlugin("EconomyShopGUI") != null
                || Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium") != null;
    }

    private boolean canReloadNow() {
        int limit = plugin.getGameSyncConfig().getEconomyMarketVisualSyncMaxReloadsPerHour();
        if (limit <= 0) return false;
        long now = System.currentTimeMillis();
        trimReloadHistory(now);
        return reloadHistory.size() < limit;
    }

    private void trimReloadHistory(long now) {
        long cutoff = now - 3_600_000L;
        while (!reloadHistory.isEmpty() && reloadHistory.peekFirst() < cutoff) {
            reloadHistory.removeFirst();
        }
    }

    private boolean isDifferentEnough(VisualPrice previous, VisualPrice next, double minDiffPercent) {
        return diffPercent(previous.buy, next.buy) >= minDiffPercent || diffPercent(previous.sell, next.sell) >= minDiffPercent;
    }

    private double diffPercent(double oldValue, double newValue) {
        if (oldValue <= 0D && newValue > 0D) return 100D;
        if (oldValue <= 0D) return 0D;
        return Math.abs(newValue - oldValue) / oldValue * 100D;
    }

    private String commandRoot(String value) {
        String result = value == null || value.isBlank() ? "editshop" : value.trim();
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private String money(double value) {
        return String.format(Locale.US, "%.2f", round2(value));
    }

    public long getLastRunMs() { return lastRunMs; }
    public int getLastChangedItems() { return lastChangedItems; }
    public int getTotalReloads() { return totalReloads; }
    public int getPendingChangedItems() { return pendingChangedItems; }
    public String getLastMessage() { return lastMessage; }

    public record VisualSyncResult(int changedItems, boolean reloadDispatched, String message) {}
    private record VisualPrice(double buy, double sell) {}
}
