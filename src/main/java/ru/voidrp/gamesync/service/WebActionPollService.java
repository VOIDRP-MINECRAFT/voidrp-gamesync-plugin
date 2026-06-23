package ru.voidrp.gamesync.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.milkbowl.vault.economy.EconomyResponse;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderCreateRequest;
import ru.voidrp.gamesync.model.PlayerMarketCancelBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCancelSellOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCreateBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketImmediateFill;
import ru.voidrp.gamesync.model.WebActionItem;
import ru.voidrp.gamesync.model.WebActionListResponse;

/**
 * Polls GET /game-sync/market-web-actions every second and executes Vault operations
 * for actions created by the WebGUI browser.
 */
public final class WebActionPollService {

    private static final long POLL_PERIOD_TICKS = 20L;

    private final VoidRpGameSyncPlugin plugin;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public WebActionPollService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 60L, POLL_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        inFlight.clear();
    }

    private void poll() {
        try {
            WebActionListResponse resp = plugin.getBackendClient().pollWebActions();
            if (resp == null || resp.actions == null || resp.actions.isEmpty()) return;
            for (WebActionItem action : resp.actions) {
                if (inFlight.add(action.action_id)) {
                    try {
                        dispatch(action);
                    } catch (Exception ex) {
                        inFlight.remove(action.action_id);
                        plugin.getLogger().warning("[WebAction] dispatch error for " + action.action_id + ": " + ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[WebAction] poll failed: " + ex.getMessage());
        }
    }

    private void dispatch(WebActionItem action) {
        switch (action.action_type) {
            case "buy"          -> processBuy(action);
            case "cancel_sell"  -> processCancelSell(action);
            case "cancel_buy"   -> processCancelBuy(action);
            case "pickup"       -> processPickup(action);
            default             -> ackFailed(action.action_id, "Unknown action_type: " + action.action_type);
        }
    }

    // ── buy ───────────────────────────────────────────────────────────────────

    private void processBuy(WebActionItem action) {
        String actionId   = action.action_id;
        String playerName = action.player_name;
        String itemKey    = str(action.payload, "item_key");
        int    amount     = intVal(action.payload, "amount");
        double unitPrice  = dbl(action.payload, "unit_price");
        String displayName    = str(action.payload, "display_name");
        String displayBase64  = str(action.payload, "display_base64");

        if (itemKey == null || amount <= 0 || unitPrice <= 0) {
            ackFailed(actionId, "Invalid buy payload");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                ackFailed(actionId, "Player offline");
                return;
            }
            if (plugin.getEconomy() == null) {
                ackFailed(actionId, "Economy unavailable");
                return;
            }

            double reserved = round2(unitPrice * amount);
            if (plugin.getEconomy().getBalance(player) < reserved) {
                player.sendMessage("§c[Рынок] Недостаточно средств. Нужно: §6" + money(reserved));
                ackFailed(actionId, "Insufficient funds");
                return;
            }

            EconomyResponse wr = plugin.getEconomy().withdrawPlayer(player, reserved);
            if (wr == null || !wr.transactionSuccess()) {
                ackFailed(actionId, "Withdraw failed");
                return;
            }

            boolean isPremium = player.hasPermission("voidrp.battlepass.premium");
            String resolvedDisplay = displayName != null ? displayName : itemKey;
            PlayerMarketBuyOrderCreateRequest req = new PlayerMarketBuyOrderCreateRequest(
                    playerName, itemKey, resolvedDisplay, displayBase64,
                    amount, unitPrice, reserved, isPremium
            );

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    PlayerMarketCreateBuyOrderResponse resp =
                            plugin.getBackendClient().createPlayerMarketBuyOrder(req);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(playerName);
                        if (p != null && resp.immediate_fills != null) {
                            for (PlayerMarketImmediateFill fill : resp.immediate_fills) {
                                if (fill.item_stack_base64 != null && fill.amount_filled > 0) {
                                    try {
                                        var item = plugin.getItemStackSnapshotService()
                                                .deserialize(fill.item_stack_base64, 1);
                                        plugin.getNationMarketInventoryService()
                                                .giveOrDrop(p, item, fill.amount_filled);
                                    } catch (Exception ex) {
                                        plugin.getLogger().warning("[WebAction] give fill items failed: " + ex.getMessage());
                                    }
                                }
                                if (fill.funds_returned_to_buyer > 0 && plugin.getEconomy() != null) {
                                    plugin.getEconomy().depositPlayer(p, fill.funds_returned_to_buyer);
                                }
                            }
                        }
                        if (p != null) p.sendMessage("§a[Рынок] Заявка на покупку создана через WebGUI.");
                        ackDone(actionId);
                    });
                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(playerName);
                        if (p != null && plugin.getEconomy() != null) {
                            plugin.getEconomy().depositPlayer(p, reserved);
                            p.sendMessage("§c[Рынок] Ошибка покупки, средства возвращены.");
                        }
                    });
                    ackFailed(actionId, ex.getMessage());
                }
            });
        });
    }

    // ── cancel_sell ───────────────────────────────────────────────────────────

    private void processCancelSell(WebActionItem action) {
        String actionId   = action.action_id;
        String playerName = action.player_name;
        String orderId    = str(action.payload, "order_id");

        if (orderId == null) {
            ackFailed(actionId, "Missing order_id");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                ackFailed(actionId, "Player offline");
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    PlayerMarketCancelSellOrderResponse resp =
                            plugin.getBackendClient().cancelPlayerMarketSellOrder(orderId, playerName);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(playerName);
                        if (p != null) {
                            if (resp.returned_amount > 0 && resp.item_stack_base64 != null
                                    && !resp.item_stack_base64.isBlank()) {
                                try {
                                    var item = plugin.getItemStackSnapshotService()
                                            .deserialize(resp.item_stack_base64, 1);
                                    plugin.getNationMarketInventoryService()
                                            .giveOrDrop(p, item, resp.returned_amount);
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("[WebAction] Return items cancel_sell failed: " + ex.getMessage());
                                }
                            }
                            if (resp.cancel_fee_coins > 0 && plugin.getEconomy() != null) {
                                plugin.getEconomy().withdrawPlayer(p, resp.cancel_fee_coins);
                            }
                            p.sendMessage("§a[Рынок] Ордер на продажу отменён.");
                        }
                        ackDone(actionId);
                    });
                } catch (Exception ex) {
                    ackFailed(actionId, ex.getMessage());
                }
            });
        });
    }

    // ── cancel_buy ────────────────────────────────────────────────────────────

    private void processCancelBuy(WebActionItem action) {
        String actionId   = action.action_id;
        String playerName = action.player_name;
        String orderId    = str(action.payload, "order_id");

        if (orderId == null) {
            ackFailed(actionId, "Missing order_id");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                ackFailed(actionId, "Player offline");
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    PlayerMarketCancelBuyOrderResponse resp =
                            plugin.getBackendClient().cancelPlayerMarketBuyOrder(orderId, playerName);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayerExact(playerName);
                        if (p != null && resp.returned_funds > 0 && plugin.getEconomy() != null) {
                            plugin.getEconomy().depositPlayer(p, resp.returned_funds);
                            p.sendMessage("§a[Рынок] Заявка на покупку отменена. Возвращено: §6" + money(resp.returned_funds));
                        }
                        ackDone(actionId);
                    });
                } catch (Exception ex) {
                    ackFailed(actionId, ex.getMessage());
                }
            });
        });
    }

    // ── pickup ────────────────────────────────────────────────────────────────

    private void processPickup(WebActionItem action) {
        String actionId   = action.action_id;
        String playerName = action.player_name;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) {
                ackFailed(actionId, "Player offline");
                return;
            }
            plugin.getPlayerMarketService().handlePickupCommand(player);
            ackDone(actionId);
        });
    }

    // ── Ack helpers ───────────────────────────────────────────────────────────

    private void ackDone(String actionId) {
        runAsync(actionId, () -> {
            try {
                plugin.getBackendClient().ackWebAction(actionId, "done", null);
            } catch (Exception ex) {
                plugin.getLogger().warning("[WebAction] ackDone failed for " + actionId + ": " + ex.getMessage());
            } finally {
                inFlight.remove(actionId);
            }
        });
    }

    private void ackFailed(String actionId, String error) {
        runAsync(actionId, () -> {
            try {
                plugin.getBackendClient().ackWebAction(actionId, "failed", error);
            } catch (Exception ex) {
                plugin.getLogger().warning("[WebAction] ackFailed failed for " + actionId + ": " + ex.getMessage());
            } finally {
                inFlight.remove(actionId);
            }
        });
    }

    private void runAsync(String actionId, Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
        } else {
            r.run();
        }
    }

    // ── Payload helpers ───────────────────────────────────────────────────────

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }

    private int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String money(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.format("%.2f", v);
    }
}
