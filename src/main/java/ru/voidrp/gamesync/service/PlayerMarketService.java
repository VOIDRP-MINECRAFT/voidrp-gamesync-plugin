package ru.voidrp.gamesync.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import net.milkbowl.vault.economy.EconomyResponse;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.event.PlayerMarketTradeEvent;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderCreateRequest;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderRead;
import ru.voidrp.gamesync.model.PlayerMarketCancelBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCancelSellOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCreateBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCreateSellOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketImmediateFill;
import ru.voidrp.gamesync.model.PlayerMarketPendingDelivery;
import ru.voidrp.gamesync.model.PlayerMarketPendingDeliveriesResponse;
import ru.voidrp.gamesync.model.PlayerMarketSellOrderCreateRequest;
import ru.voidrp.gamesync.model.PlayerMarketSellOrderRead;

public final class PlayerMarketService {

    public enum PendingActionType {
        SELL, BUY,
        /** From GUI: awaiting "count price" in chat (item already in hand) */
        GUI_SELL,
        /** From GUI: awaiting "namespace:id count price" in chat */
        GUI_BUY
    }

    public static final class PendingAction {
        public final PendingActionType type;
        // For SELL / GUI_SELL
        public final ItemStack sample;
        public int amount;
        public double unitPrice;
        // For BUY / GUI_BUY
        public final String itemKey;
        public final String displayName;
        // Shared
        public final long createdAt;

        /** SELL constructor (confirm flow) */
        public PendingAction(ItemStack sample, int amount, double unitPrice) {
            this.type = PendingActionType.SELL;
            this.sample = sample;
            this.amount = amount;
            this.unitPrice = unitPrice;
            this.itemKey = null;
            this.displayName = null;
            this.createdAt = System.currentTimeMillis();
        }

        /** BUY constructor (confirm flow) */
        public PendingAction(String itemKey, String displayName, int amount, double unitPrice) {
            this.type = PendingActionType.BUY;
            this.sample = null;
            this.amount = amount;
            this.unitPrice = unitPrice;
            this.itemKey = itemKey;
            this.displayName = displayName;
            this.createdAt = System.currentTimeMillis();
        }

        /** GUI_SELL constructor — item in hand, waiting for "count price" */
        public static PendingAction guiSell(ItemStack sample) {
            PendingAction a = new PendingAction(sample, 0, 0);
            return new PendingAction(PendingActionType.GUI_SELL, sample, null);
        }

        /** GUI_BUY constructor — waiting for "key count price" */
        public static PendingAction guiBuy() {
            return new PendingAction(PendingActionType.GUI_BUY, null, null);
        }

        private PendingAction(PendingActionType type, ItemStack sample, String itemKey) {
            this.type = type;
            this.sample = sample;
            this.itemKey = itemKey;
            this.displayName = null;
            this.amount = 0;
            this.unitPrice = 0;
            this.createdAt = System.currentTimeMillis();
        }

        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - createdAt > timeoutMs;
        }

        /** Mutable display icon base64 — set after construction for buy orders. */
        public String _displayBase64;
    }

    private static final long CONFIRM_TIMEOUT_MS = 60_000L;

    private final VoidRpGameSyncPlugin plugin;
    private final Map<UUID, PendingAction> pendingActions = new ConcurrentHashMap<>();
    // Prevents concurrent delivery for the same player (join + pickup spam race condition)
    private final Set<UUID> deliveringPlayers = ConcurrentHashMap.newKeySet();

    public PlayerMarketService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Pending action (confirmation flow) ───────────────────────────────────

    public void setPendingAction(UUID playerId, PendingAction action) {
        pendingActions.put(playerId, action);
    }

    public PendingAction peekPendingAction(UUID playerId) {
        PendingAction action = pendingActions.get(playerId);
        if (action == null) return null;
        if (action.isExpired(CONFIRM_TIMEOUT_MS)) {
            pendingActions.remove(playerId);
            return null;
        }
        return action;
    }

    public PendingAction consumePendingAction(UUID playerId) {
        PendingAction action = pendingActions.remove(playerId);
        if (action == null) return null;
        if (action.isExpired(CONFIRM_TIMEOUT_MS)) return null;
        return action;
    }

    public boolean hasPendingAction(UUID playerId) {
        PendingAction action = pendingActions.get(playerId);
        if (action == null) return false;
        if (action.isExpired(CONFIRM_TIMEOUT_MS)) {
            pendingActions.remove(playerId);
            return false;
        }
        return true;
    }

    public void clearPendingAction(UUID playerId) {
        pendingActions.remove(playerId);
    }

    // ── Player join / manual pickup ───────────────────────────────────────────

    /** Manual /pm pickup — same logic as join delivery but with explicit player feedback. */
    public void handlePickupCommand(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        if (!deliveringPlayers.add(playerId)) {
            player.sendMessage("§7[Рынок] Уже выполняется получение, подождите…");
            return;
        }
        player.sendMessage("§7[Рынок] Проверяем незабранные предметы и деньги…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketPendingDeliveriesResponse resp =
                        plugin.getBackendClient().getPlayerMarketPendingDeliveries(playerName);
                if (resp == null || resp.deliveries == null || resp.deliveries.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player onlinePlayer = Bukkit.getPlayerExact(playerName);
                        if (onlinePlayer != null) onlinePlayer.sendMessage("§a[Рынок] Нет незабранных предметов или денег.");
                    });
                    return;
                }
                List<String> allIds = new ArrayList<>();
                for (PlayerMarketPendingDelivery d : resp.deliveries) allIds.add(d.id);
                // Ack FIRST to prevent double-delivery on disconnect
                plugin.getBackendClient().ackPlayerMarketDeliveries(playerName, allIds);
                final List<PlayerMarketPendingDelivery> deliveries = resp.deliveries;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player onlinePlayer = Bukkit.getPlayerExact(playerName);
                    if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
                    int delivered = 0;
                    for (PlayerMarketPendingDelivery delivery : deliveries) {
                        try {
                            deliverOne(onlinePlayer, delivery);
                            delivered++;
                        } catch (Exception ex) {
                            plugin.getLogger().warning("[PlayerMarket] Pickup delivery failed " + delivery.id + ": " + ex.getMessage());
                        }
                    }
                    onlinePlayer.sendMessage("§a[Рынок] Всё получено (" + delivered + " позиций).");
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player onlinePlayer = Bukkit.getPlayerExact(playerName);
                    if (onlinePlayer != null) onlinePlayer.sendMessage("§c[Рынок] Ошибка при получении: " + ex.getMessage());
                });
            } finally {
                deliveringPlayers.remove(playerId);
            }
        });
    }

    public void handlePlayerJoin(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        if (!deliveringPlayers.add(playerId)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketPendingDeliveriesResponse resp =
                        plugin.getBackendClient().getPlayerMarketPendingDeliveries(playerName);
                if (resp == null || resp.deliveries == null || resp.deliveries.isEmpty()) {
                    return;
                }
                List<String> allIds = new ArrayList<>();
                for (PlayerMarketPendingDelivery d : resp.deliveries) allIds.add(d.id);
                // Ack FIRST to prevent double-delivery if player disconnects mid-delivery
                plugin.getBackendClient().ackPlayerMarketDeliveries(playerName, allIds);
                final List<PlayerMarketPendingDelivery> deliveries = resp.deliveries;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player onlinePlayer = Bukkit.getPlayerExact(playerName);
                    if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
                    for (PlayerMarketPendingDelivery delivery : deliveries) {
                        try {
                            deliverOne(onlinePlayer, delivery);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("[PlayerMarket] Failed to deliver " + delivery.id + " to " + playerName + ": " + ex.getMessage());
                        }
                    }
                });
            } catch (Exception ex) {
                plugin.getLogger().warning("[PlayerMarket] Failed to fetch deliveries for " + playerName + ": " + ex.getMessage());
            } finally {
                deliveringPlayers.remove(playerId);
            }
        });
    }

    private void deliverOne(Player player, PlayerMarketPendingDelivery delivery) {
        switch (delivery.delivery_type) {
            case "sell_proceeds", "buy_refund" -> {
                if (delivery.amount_money > 0 && plugin.getEconomy() != null) {
                    plugin.getEconomy().depositPlayer(player, delivery.amount_money);
                    String typeLabel = delivery.delivery_type.equals("sell_proceeds") ? "Выручка с продажи" : "Возврат средств";
                    player.sendMessage("§a[Рынок игроков] " + typeLabel + ": §6+" + money(delivery.amount_money) + " монет");
                }
            }
            case "item_delivery" -> {
                if (delivery.amount_items > 0 && delivery.item_stack_base64 != null && !delivery.item_stack_base64.isBlank()) {
                    ItemStack item = plugin.getItemStackSnapshotService().deserialize(delivery.item_stack_base64, 1);
                    plugin.getNationMarketInventoryService().giveOrDrop(player, item, delivery.amount_items);
                    String displayName = delivery.display_name != null ? delivery.display_name : (delivery.item_key != null ? delivery.item_key : "предмет");
                    player.sendMessage("§a[Рынок игроков] Получены предметы: §f" + displayName + " §7x§f" + delivery.amount_items);
                }
            }
            default -> plugin.getLogger().warning("[PlayerMarket] Unknown delivery type: " + delivery.delivery_type);
        }
    }

    // ── Sell order ────────────────────────────────────────────────────────────

    public void handleSellCommand(Player player, int amount, double unitPrice) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§cВозьми предмет в руку.");
            return;
        }

        ItemStack sample = hand.clone();
        sample.setAmount(1);

        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        if (amount <= 0) {
            player.sendMessage("§cКоличество должно быть больше 0.");
            return;
        }
        if (amount > available) {
            player.sendMessage("§cНедостаточно предметов. Доступно: §f" + available);
            return;
        }
        if (unitPrice <= 0D || !Double.isFinite(unitPrice)) {
            player.sendMessage("§cЦена должна быть больше 0.");
            return;
        }
        if (unitPrice > 1_000_000_000D) {
            player.sendMessage("§cЦена не может превышать 1 000 000 000.");
            return;
        }
        if (amount > 100_000) {
            player.sendMessage("§cКоличество не может превышать 100 000.");
            return;
        }

        double total = round2(unitPrice * amount);
        String itemKey = enrichItemKey(sample);
        String displayName = displayName(sample);

        PendingAction pending = new PendingAction(sample, amount, round2(unitPrice));
        setPendingAction(player.getUniqueId(), pending);

        player.sendMessage("§e[Рынок] Подтверди создание ордера на продажу:");
        player.sendMessage("§7  Предмет: §f" + (displayName != null ? displayName : itemKey));
        player.sendMessage("§7  Количество: §f" + amount);
        player.sendMessage("§7  Цена за шт.: §6" + money(unitPrice));
        player.sendMessage("§7  Итого при полной продаже: §6" + money(total));
        player.sendMessage("§7Введи §f/pm confirm §7или напиши §fда §7в чат для подтверждения.");
        player.sendMessage("§8(Время на подтверждение: 60 сек)");
    }

    public void handleSellCommandAll(Player player, double unitPrice) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§cВозьми предмет в руку.");
            return;
        }
        ItemStack sample = hand.clone();
        sample.setAmount(1);
        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        if (available <= 0) {
            player.sendMessage("§cВ инвентаре нет таких предметов.");
            return;
        }
        handleSellCommand(player, available, unitPrice);
    }

    public void executeSell(Player player, PendingAction action) {
        if (!plugin.getNationMarketInventoryService().removeSimilar(player, action.sample, action.amount)) {
            player.sendMessage("§cНе удалось изъять предметы. Возможно, инвентарь изменился.");
            return;
        }

        String base64;
        try {
            base64 = plugin.getItemStackSnapshotService().serializeSingle(action.sample);
        } catch (Exception ex) {
            plugin.getNationMarketInventoryService().giveOrDrop(player, action.sample, action.amount);
            player.sendMessage("§cНе удалось сохранить предмет. Предметы возвращены.");
            return;
        }

        String itemKey = enrichItemKey(action.sample);
        String displayName = displayName(action.sample);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("created_from", "voidrp_gamesync");
        boolean isPremium = player.hasPermission("voidrp.battlepass.premium");

        PlayerMarketSellOrderCreateRequest request = new PlayerMarketSellOrderCreateRequest(
                player.getName(),
                itemKey,
                displayName,
                base64,
                action.amount,
                action.unitPrice,
                metadata,
                isPremium
        );

        player.sendMessage("§7Размещаем ордер на продажу...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCreateSellOrderResponse response =
                        plugin.getBackendClient().createPlayerMarketSellOrder(request);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[Рынок] Ордер на продажу размещён.");
                    if (response.order != null) {
                        player.sendMessage("§7ID: §f" + response.order.id);
                        player.sendMessage("§7Остаток: §f" + response.order.remaining_amount + " §7Цена: §6" + money(response.order.unit_price));
                    }
                    if (response.immediate_fills != null && !response.immediate_fills.isEmpty()) {
                        for (PlayerMarketImmediateFill fill : response.immediate_fills) {
                            if (fill.funds_released_to_seller > 0 && plugin.getEconomy() != null) {
                                plugin.getEconomy().depositPlayer(player, fill.funds_released_to_seller);
                                player.sendMessage("§a[Рынок] Мгновенная продажа x" + fill.amount_filled + " §8→ §6+" + money(fill.funds_released_to_seller) + " ₽");
                            }
                            // Notify the buyer (if online) that their buy order was just filled
                            notifyOnline(fill.buyer_player_name,
                                    "§b[Рынок] §7✦ Твоя заявка исполнена: §f" + displayName(action.sample)
                                    + " §7×§f" + fill.amount_filled
                                    + " §7по §6" + money(fill.unit_price) + " ₽/шт."
                                    + " §8(продавец: §7" + player.getName() + "§8)");
                            // Fire quest/battlepass integration events
                            Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                    player.getName(), PlayerMarketTradeEvent.Role.SELLER,
                                    itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            if (fill.buyer_player_name != null && !fill.buyer_player_name.isBlank()) {
                                Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                        fill.buyer_player_name, PlayerMarketTradeEvent.Role.BUYER,
                                        itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getNationMarketInventoryService().giveOrDrop(player, action.sample, action.amount);
                    player.sendMessage("§cBackend отклонил ордер. Предметы возвращены.");
                    player.sendMessage("§8Причина: " + ex.getMessage());
                });
            }
        });
    }

    // ── Buy order ─────────────────────────────────────────────────────────────

    public void handleBuyCommand(Player player, String itemKey, int amount, double unitPrice) {
        handleBuyCommand(player, itemKey, amount, unitPrice, null, itemKey);
    }

    public void handleBuyCommand(Player player, String itemKey, int amount, double unitPrice,
                                  String displayBase64, String displayName) {
        if (plugin.getEconomy() == null) {
            player.sendMessage("§cEconomy (Vault) недоступна.");
            return;
        }
        if (amount <= 0) {
            player.sendMessage("§cКоличество должно быть больше 0.");
            return;
        }
        if (amount > 100_000) {
            player.sendMessage("§cКоличество не может превышать 100 000.");
            return;
        }
        if (unitPrice <= 0D || !Double.isFinite(unitPrice)) {
            player.sendMessage("§cЦена должна быть больше 0.");
            return;
        }
        if (unitPrice > 1_000_000_000D) {
            player.sendMessage("§cЦена не может превышать 1 000 000 000.");
            return;
        }

        double reservedFunds = round2(unitPrice * amount);
        if (plugin.getEconomy().getBalance(player) < reservedFunds) {
            player.sendMessage("§cНедостаточно средств. Нужно зарезервировать: §6" + money(reservedFunds));
            return;
        }

        // Store displayBase64 in metadata temporarily via PendingAction
        PendingAction pending = new PendingAction(itemKey, displayName != null ? displayName : itemKey, amount, round2(unitPrice));
        // We store displayBase64 through the action's extra field logic in executeBuy
        pending._displayBase64 = displayBase64;
        setPendingAction(player.getUniqueId(), pending);

        player.sendMessage("§e[Рынок] Подтверди создание ордера на покупку:");
        player.sendMessage("§7  Предмет: §f" + (displayName != null ? displayName : itemKey));
        player.sendMessage("§7  Количество: §f" + amount);
        player.sendMessage("§7  Цена за шт.: §6" + money(unitPrice));
        player.sendMessage("§7  Будет зарезервировано: §6" + money(reservedFunds));
        player.sendMessage("§7Введи §f/pm confirm §7или напиши §fда §7в чат для подтверждения.");
        player.sendMessage("§8(Время на подтверждение: 60 сек)");
    }

    public void executeBuy(Player player, PendingAction action) {
        if (plugin.getEconomy() == null) {
            player.sendMessage("§cEconomy (Vault) недоступна.");
            return;
        }

        double reservedFunds = round2(action.unitPrice * action.amount);
        if (plugin.getEconomy().getBalance(player) < reservedFunds) {
            player.sendMessage("§cНедостаточно средств. Нужно: §6" + money(reservedFunds));
            return;
        }

        EconomyResponse withdraw = plugin.getEconomy().withdrawPlayer(player, reservedFunds);
        if (withdraw == null || !withdraw.transactionSuccess()) {
            player.sendMessage("§cНе удалось списать средства.");
            return;
        }

        String itemKey = action.itemKey;
        String displayName = action.displayName != null ? action.displayName : itemKey;
        String displayBase64 = action._displayBase64 != null ? action._displayBase64
                : resolveItemDisplayBase64(player, itemKey);
        boolean isPremium = player.hasPermission("voidrp.battlepass.premium");

        PlayerMarketBuyOrderCreateRequest request = new PlayerMarketBuyOrderCreateRequest(
                player.getName(),
                itemKey,
                displayName,
                displayBase64,
                action.amount,
                action.unitPrice,
                reservedFunds,
                isPremium
        );

        player.sendMessage("§7Размещаем ордер на покупку...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCreateBuyOrderResponse response =
                        plugin.getBackendClient().createPlayerMarketBuyOrder(request);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[Рынок] Ордер на покупку размещён.");
                    if (response.order != null) {
                        player.sendMessage("§7ID: §f" + response.order.id);
                        player.sendMessage("§7Осталось купить: §f" + response.order.remaining_amount + " §7Цена: §6" + money(response.order.unit_price));
                    }
                    if (response.immediate_fills != null && !response.immediate_fills.isEmpty()) {
                        for (PlayerMarketImmediateFill fill : response.immediate_fills) {
                            if (fill.item_stack_base64 != null && !fill.item_stack_base64.isBlank() && fill.amount_filled > 0) {
                                try {
                                    ItemStack item = plugin.getItemStackSnapshotService().deserialize(fill.item_stack_base64, 1);
                                    plugin.getNationMarketInventoryService().giveOrDrop(player, item, fill.amount_filled);
                                    player.sendMessage("§a[Рынок] Мгновенная покупка x" + fill.amount_filled + " §7получена.");
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("[PlayerMarket] Failed to give fill items to " + player.getName() + ": " + ex.getMessage());
                                }
                            }
                            if (fill.funds_returned_to_buyer > 0 && plugin.getEconomy() != null) {
                                plugin.getEconomy().depositPlayer(player, fill.funds_returned_to_buyer);
                                player.sendMessage("§a[Рынок] Возврат переплаты: §6+" + money(fill.funds_returned_to_buyer) + " ₽");
                            }
                            // Notify the seller (if online) that their sell order was just filled
                            notifyOnline(fill.seller_player_name,
                                    "§a[Рынок] §7✦ Твой ордер исполнен: §f" + displayName
                                    + " §7×§f" + fill.amount_filled
                                    + " §8→ §6+" + money(fill.funds_released_to_seller) + " ₽"
                                    + " §8(покупатель: §7" + player.getName() + "§8)");
                            // Fire quest/battlepass integration events
                            Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                    player.getName(), PlayerMarketTradeEvent.Role.BUYER,
                                    itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            if (fill.seller_player_name != null && !fill.seller_player_name.isBlank()) {
                                Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                        fill.seller_player_name, PlayerMarketTradeEvent.Role.SELLER,
                                        itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            }
                        }
                    }
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getEconomy().depositPlayer(player, reservedFunds);
                    player.sendMessage("§cBackend отклонил ордер. Средства возвращены.");
                    player.sendMessage("§8Причина: " + ex.getMessage());
                });
            }
        });
    }

    // ── Cancel orders ─────────────────────────────────────────────────────────

    public void handleCancelSellCommand(Player player, String orderId) {
        player.sendMessage("§7Отменяем ордер на продажу...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCancelSellOrderResponse response =
                        plugin.getBackendClient().cancelPlayerMarketSellOrder(orderId, player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[Рынок] Ордер на продажу отменён.");
                    if (response.returned_amount > 0 && response.item_stack_base64 != null && !response.item_stack_base64.isBlank()) {
                        try {
                            ItemStack item = plugin.getItemStackSnapshotService().deserialize(response.item_stack_base64, 1);
                            plugin.getNationMarketInventoryService().giveOrDrop(player, item, response.returned_amount);
                            player.sendMessage("§7Возвращено предметов: §f" + response.returned_amount);
                        } catch (Exception ex) {
                            player.sendMessage("§cОшибка возврата предметов. Обратитесь к администратору.");
                            plugin.getLogger().warning("[PlayerMarket] Failed to return items for cancel sell " + orderId + ": " + ex.getMessage());
                        }
                    }
                    if (response.cancel_fee_coins > 0 && plugin.getEconomy() != null) {
                        plugin.getEconomy().withdrawPlayer(player, response.cancel_fee_coins);
                        player.sendMessage("§7Комиссия за отмену: §c-" + money(response.cancel_fee_coins));
                    }
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось отменить ордер.");
                    player.sendMessage("§8Причина: " + ex.getMessage());
                });
            }
        });
    }

    public void handleCancelBuyCommand(Player player, String orderId) {
        player.sendMessage("§7Отменяем ордер на покупку...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCancelBuyOrderResponse response =
                        plugin.getBackendClient().cancelPlayerMarketBuyOrder(orderId, player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[Рынок] Ордер на покупку отменён.");
                    if (response.returned_funds > 0 && plugin.getEconomy() != null) {
                        plugin.getEconomy().depositPlayer(player, response.returned_funds);
                        player.sendMessage("§7Возвращено средств: §6" + money(response.returned_funds));
                    }
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось отменить ордер.");
                    player.sendMessage("§8Причина: " + ex.getMessage());
                });
            }
        });
    }

    // ── GUI flow: sell button clicked ─────────────────────────────────────────

    public void startGuiSellFlow(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§c[Рынок] Возьми предмет в руку, затем нажми кнопку снова.");
            plugin.getPlayerMarketGuiService().open(player);
            return;
        }
        ItemStack sample = hand.clone();
        sample.setAmount(1);
        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        String name = enrichItemKey(sample);
        setPendingAction(player.getUniqueId(), PendingAction.guiSell(sample));
        player.sendMessage("§e[Рынок] Выставление на продажу: §f" + name + " §7(в руке)");
        player.sendMessage("§7Доступно: §f" + available + " §7шт.");
        player.sendMessage("§7Введи в чат: §fколичество цена");
        player.sendMessage("§8Пример: §f32 150.00  §8| Или /pm cancel для отмены");
    }

    public void handleGuiSellInput(Player player, PendingAction pending, String input) {
        String[] parts = input.trim().split("\\s+");
        if (parts.length < 2) {
            player.sendMessage("§c[Рынок] Формат: §fколичество цена  §8Пример: §f32 150.00");
            return;
        }
        int amount;
        double price;
        try {
            amount = Integer.parseInt(parts[0]);
            price  = Double.parseDouble(parts[1].replace(',', '.'));
        } catch (NumberFormatException ex) {
            player.sendMessage("§c[Рынок] Неверный формат числа. Пример: §f32 150.00");
            return;
        }
        if (amount <= 0 || price <= 0 || !Double.isFinite(price)) {
            player.sendMessage("§c[Рынок] Количество и цена должны быть больше нуля.");
            return;
        }
        if (amount > 100_000) {
            player.sendMessage("§c[Рынок] Количество не может превышать 100 000.");
            return;
        }
        if (price > 1_000_000_000D) {
            player.sendMessage("§c[Рынок] Цена не может превышать 1 000 000 000.");
            return;
        }

        ItemStack sample = pending.sample;
        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        if (amount > available) {
            player.sendMessage("§c[Рынок] Недостаточно предметов. В инвентаре: §f" + available);
            return;
        }

        clearPendingAction(player.getUniqueId());
        // Reuse confirm flow
        PendingAction confirm = new PendingAction(sample, amount, round2(price));
        double total = round2(price * amount);
        player.sendMessage("§e[Рынок] Подтверди ордер на продажу:");
        player.sendMessage("§7  Предмет: §f" + displayName(sample));
        player.sendMessage("§7  Количество: §f" + amount);
        player.sendMessage("§7  Цена за шт.: §6" + money(price));
        player.sendMessage("§7  Выручка при полной продаже: §6" + money(total));
        player.sendMessage("§7Введи §fда §7или §f/pm confirm §7для подтверждения. (60 сек)");
        setPendingAction(player.getUniqueId(), confirm);
    }

    // ── GUI flow: buy button clicked ──────────────────────────────────────────

    public void startGuiBuyFlow(Player player) {
        clearPendingAction(player.getUniqueId());
        setPendingAction(player.getUniqueId(), PendingAction.guiBuy());
        player.sendMessage("§b[Рынок] Создание заявки на покупку.");
        player.sendMessage("§7Введи в чат: §fnamespace:id количество цена");
        player.sendMessage("§8Пример: §fminecraft:diamond 64 100.00");
        player.sendMessage("§8Или §f/pm cancel §8для отмены");
    }

    public void handleGuiBuyInput(Player player, String input) {
        clearPendingAction(player.getUniqueId());
        String[] parts = input.trim().split("\\s+");
        if (parts.length < 3) {
            player.sendMessage("§c[Рынок] Формат: §fnamespace:id количество цена");
            player.sendMessage("§8Пример: §fminecraft:diamond 64 100.00");
            return;
        }
        String itemKey = parts[0].toLowerCase(Locale.ROOT);
        if (!itemKey.contains(":")) {
            player.sendMessage("§c[Рынок] Ключ предмета должен быть в формате §fnamespace:id §8(пример: minecraft:diamond)");
            return;
        }
        int amount;
        double price;
        try {
            amount = Integer.parseInt(parts[1]);
            price  = Double.parseDouble(parts[2].replace(',', '.'));
        } catch (NumberFormatException ex) {
            player.sendMessage("§c[Рынок] Неверный формат числа.");
            return;
        }
        if (amount <= 0 || price <= 0 || !Double.isFinite(price)) {
            player.sendMessage("§c[Рынок] Количество и цена должны быть больше нуля.");
            return;
        }
        if (amount > 100_000) {
            player.sendMessage("§c[Рынок] Количество не может превышать 100 000.");
            return;
        }
        if (price > 1_000_000_000D) {
            player.sendMessage("§c[Рынок] Цена не может превышать 1 000 000 000.");
            return;
        }

        // Try to get display base64 for icon
        String displayBase64 = resolveItemDisplayBase64(player, itemKey);

        handleBuyCommand(player, itemKey, amount, price, displayBase64, null);
    }

    /** Try to serialize an icon ItemStack for the given item_key. */
    private String resolveItemDisplayBase64(Player player, String itemKey) {
        // 1) Find in player inventory (works for any item including mods)
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                String key = stack.getType().getKey().toString();
                if (key.equalsIgnoreCase(itemKey)) {
                    try {
                        ItemStack single = stack.clone();
                        single.setAmount(1);
                        return plugin.getItemStackSnapshotService().serializeSingle(single);
                    } catch (Exception ignored) {}
                }
            }
        }
        // 2) Construct vanilla item from Material
        if (itemKey.startsWith("minecraft:")) {
            String matName = itemKey.substring("minecraft:".length()).toUpperCase(Locale.ROOT);
            try {
                Material mat = Material.valueOf(matName);
                if (mat != Material.AIR) {
                    return plugin.getItemStackSnapshotService().serializeSingle(new ItemStack(mat));
                }
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    // ── Direct fill from GUI (buy from sell order) ────────────────────────────

    public void executeBuyFromGui(Player player, PlayerMarketSellOrderRead order, int amount) {
        if (plugin.getEconomy() == null) { player.sendMessage("§c[Рынок] Экономика недоступна."); return; }
        double total = round2((double) amount * order.unit_price);
        if (plugin.getEconomy().getBalance(player) < total) {
            player.sendMessage("§c[Рынок] Недостаточно монет. Нужно: §6" + money(total));
            return;
        }
        EconomyResponse withdraw = plugin.getEconomy().withdrawPlayer(player, total);
        if (withdraw == null || !withdraw.transactionSuccess()) {
            player.sendMessage("§c[Рынок] Не удалось списать деньги.");
            return;
        }

        String itemKey = order.item_key;
        String displayName = order.display_name != null ? order.display_name : itemKey;
        final int finalAmount = amount;

        // Create buy order at same price → backend auto-matches with this sell order
        boolean isPremium = player.hasPermission("voidrp.battlepass.premium");
        PlayerMarketBuyOrderCreateRequest req = new PlayerMarketBuyOrderCreateRequest(
                player.getName(), itemKey, displayName, null, amount, order.unit_price, total, isPremium
        );
        player.sendMessage("§7[Рынок] Покупаем...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCreateBuyOrderResponse resp = plugin.getBackendClient().createPlayerMarketBuyOrder(req);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (resp.immediate_fills != null && !resp.immediate_fills.isEmpty()) {
                        for (PlayerMarketImmediateFill fill : resp.immediate_fills) {
                            if (fill.item_stack_base64 != null && fill.amount_filled > 0) {
                                try {
                                    ItemStack item = plugin.getItemStackSnapshotService().deserialize(fill.item_stack_base64, 1);
                                    plugin.getNationMarketInventoryService().giveOrDrop(player, item, fill.amount_filled);
                                } catch (Exception ex) {
                                    plugin.getLogger().warning("[PlayerMarket] Failed to give items to " + player.getName() + ": " + ex.getMessage());
                                }
                            }
                            if (fill.funds_returned_to_buyer > 0 && plugin.getEconomy() != null) {
                                plugin.getEconomy().depositPlayer(player, fill.funds_returned_to_buyer);
                            }
                            // Notify seller that their item was purchased
                            notifyOnline(order.seller_player_name,
                                    "§a[Рынок] §7✦ §e" + player.getName()
                                    + " §7купил у тебя §f" + displayName
                                    + " §7×§f" + fill.amount_filled
                                    + " §8→ §6+" + money(fill.funds_released_to_seller) + " ₽");
                            // Fire integration events
                            Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                    player.getName(), PlayerMarketTradeEvent.Role.BUYER,
                                    itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            if (order.seller_player_name != null && !order.seller_player_name.isBlank()) {
                                Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                        order.seller_player_name, PlayerMarketTradeEvent.Role.SELLER,
                                        itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            }
                        }
                        player.sendMessage("§a[Рынок] §7Куплено: §f" + displayName
                                + " §7×§f" + finalAmount + " §7за §6" + money(total) + " ₽");
                    } else {
                        player.sendMessage("§a[Рынок] Заявка на покупку размещена (§f" + displayName + " ×" + finalAmount + "§a).");
                        player.sendMessage("§7Предметы доставят при следующем входе после исполнения.");
                    }
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> plugin.getPlayerMarketGuiService().open(player), 10L);
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.getEconomy() != null) plugin.getEconomy().depositPlayer(player, total);
                    player.sendMessage("§c[Рынок] Ошибка покупки. Деньги возвращены.");
                    player.sendMessage("§8" + ex.getMessage());
                });
            }
        });
    }

    // ── Direct fill from GUI (sell to buy order) ──────────────────────────────

    public void executeSellFromGui(Player player, PlayerMarketBuyOrderRead order, int amount) {
        String itemKey = order.item_key;

        // Find item in player inventory by key
        ItemStack sample = findInInventory(player, itemKey);
        if (sample == null) {
            player.sendMessage("§c[Рынок] Нет предмета §f" + itemKey + " §cв инвентаре.");
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> plugin.getPlayerMarketGuiService().open(player), 5L);
            return;
        }

        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        amount = Math.min(amount, Math.min(order.remaining_amount, available));
        if (amount <= 0) {
            player.sendMessage("§c[Рынок] Недостаточно предметов в инвентаре.");
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> plugin.getPlayerMarketGuiService().open(player), 5L);
            return;
        }

        if (!plugin.getNationMarketInventoryService().removeSimilar(player, sample, amount)) {
            player.sendMessage("§c[Рынок] Не удалось изъять предметы.");
            return;
        }

        String base64;
        try {
            base64 = plugin.getItemStackSnapshotService().serializeSingle(sample);
        } catch (Exception ex) {
            plugin.getNationMarketInventoryService().giveOrDrop(player, sample, amount);
            player.sendMessage("§c[Рынок] Ошибка сериализации предмета.");
            return;
        }

        String displayName = null;
        Map<String, Object> meta = new HashMap<>();
        meta.put("created_from", "voidrp_gui");
        boolean isPremiumSell = player.hasPermission("voidrp.battlepass.premium");

        PlayerMarketSellOrderCreateRequest req = new PlayerMarketSellOrderCreateRequest(
                player.getName(), itemKey, displayName, base64, amount, order.unit_price, meta, isPremiumSell
        );

        final int finalAmount = amount;
        player.sendMessage("§7[Рынок] Продаём...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerMarketCreateSellOrderResponse resp = plugin.getBackendClient().createPlayerMarketSellOrder(req);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (resp.immediate_fills != null && !resp.immediate_fills.isEmpty()) {
                        double earned = 0;
                        for (PlayerMarketImmediateFill fill : resp.immediate_fills) {
                            if (fill.funds_released_to_seller > 0 && plugin.getEconomy() != null) {
                                plugin.getEconomy().depositPlayer(player, fill.funds_released_to_seller);
                                earned += fill.funds_released_to_seller;
                            }
                            // Notify buyer that their buy order was just filled
                            notifyOnline(order.buyer_player_name,
                                    "§b[Рынок] §7✦ Твоя заявка исполнена: §f" + itemKey
                                    + " §7×§f" + fill.amount_filled
                                    + " §7по §6" + money(fill.unit_price) + " ₽/шт."
                                    + " §8(продавец: §7" + player.getName() + "§8)");
                            // Fire integration events
                            Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                    player.getName(), PlayerMarketTradeEvent.Role.SELLER,
                                    itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            if (order.buyer_player_name != null && !order.buyer_player_name.isBlank()) {
                                Bukkit.getPluginManager().callEvent(new PlayerMarketTradeEvent(
                                        order.buyer_player_name, PlayerMarketTradeEvent.Role.BUYER,
                                        itemKey, fill.amount_filled, fill.unit_price * fill.amount_filled));
                            }
                        }
                        player.sendMessage("§a[Рынок] §7Продано: §f" + displayName
                                + " §7×§f" + finalAmount + " §8→ §6+" + money(earned) + " ₽");
                    } else {
                        player.sendMessage("§a[Рынок] Ордер на продажу размещён (×" + finalAmount + ").");
                        player.sendMessage("§7Деньги придут после покупки.");
                    }
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> plugin.getPlayerMarketGuiService().open(player), 10L);
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getNationMarketInventoryService().giveOrDrop(player, sample, finalAmount);
                    player.sendMessage("§c[Рынок] Ошибка. Предметы возвращены.");
                    player.sendMessage("§8" + ex.getMessage());
                });
            }
        });
    }

    /** Find an ItemStack in player's inventory matching the given item_key. */
    public ItemStack findInInventoryByKey(Player player, String itemKey) {
        return findInInventory(player, itemKey);
    }

    public String enrichItemKeyPublic(ItemStack item) { return enrichItemKey(item); }
    public String displayNamePublic(ItemStack item)   { return displayName(item); }

    /** Find an ItemStack in player's inventory matching the given item_key. */
    private ItemStack findInInventory(Player player, String itemKey) {
        // Check main hand first (most likely slot for selling)
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() != Material.AIR && itemMatchesKey(hand, itemKey)) {
            ItemStack single = hand.clone();
            single.setAmount(1);
            return single;
        }
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (itemMatchesKey(stack, itemKey)) {
                ItemStack single = stack.clone();
                single.setAmount(1);
                return single;
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the specific market item_key for an ItemStack.
     * Potions → "potion:effect", enchanted books (single enchant) → "enchanted_book:name:level".
     * Falls back to "namespace:material".
     */
    private String enrichItemKey(ItemStack item) {
        String baseKey = item.getType().getKey().toString(); // e.g. "minecraft:potion"
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return baseKey;

        // Potions: generate "potion:effect_name", "splash_potion:effect_name", etc.
        if (meta instanceof PotionMeta potionMeta) {
            PotionType type = potionMeta.getBasePotionType();
            if (type != null) {
                String prefix = switch (baseKey) {
                    case "minecraft:splash_potion" -> "splash_potion";
                    case "minecraft:lingering_potion" -> "lingering_potion";
                    default -> "potion";
                };
                String effectKey = type.getKey().getKey().toLowerCase(Locale.ROOT);
                return prefix + ":" + effectKey;
            }
        }

        // Enchanted books: generate "enchanted_book:enchant:level" for single-enchant books
        if (meta instanceof EnchantmentStorageMeta enchMeta) {
            var stored = enchMeta.getStoredEnchants();
            if (stored.size() == 1) {
                var entry = stored.entrySet().iterator().next();
                String enchName = entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT);
                int level = entry.getValue();
                return "enchanted_book:" + enchName + ":" + level;
            }
            // Multi-enchant books use "minecraft:enchanted_book" (generic)
        }

        return baseKey;
    }

    /**
     * Returns true if the item matches the given market item_key.
     * Handles both specific keys (potion:night_vision) and generic material keys (minecraft:potion).
     */
    private boolean itemMatchesKey(ItemStack item, String key) {
        if (item == null || item.getType() == Material.AIR || key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT);
        // Exact enriched match (e.g. potion:night_vision == potion:night_vision)
        if (enrichItemKey(item).equalsIgnoreCase(normalized)) return true;
        // Generic material match (e.g. minecraft:potion matches if key is potion:anything)
        String matKey = item.getType().getKey().toString().toLowerCase(Locale.ROOT);
        if (matKey.equalsIgnoreCase(normalized)) return true;
        // Also allow "minecraft:potion" to match any "potion:*" buy order (for backward compat)
        String matName = item.getType().getKey().getKey();
        if (normalized.startsWith(matName + ":") || normalized.startsWith("minecraft:" + matName + ":")) return true;
        return false;
    }

    private String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return org.bukkit.ChatColor.stripColor(meta.getDisplayName());
        }
        return null;
    }

    /** Send a message to an online player by name (no-op if offline). Must be called on main thread. */
    private void notifyOnline(String playerName, String message) {
        if (playerName == null || playerName.isBlank()) return;
        Player target = Bukkit.getPlayerExact(playerName);
        if (target != null && target.isOnline()) {
            target.sendMessage(message);
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private String money(double value) {
        return String.format(Locale.US, "%.2f", round2(value));
    }
}
