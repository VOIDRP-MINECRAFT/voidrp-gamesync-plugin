package ru.voidrp.gamesync.service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.OminousBottleMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.MarketPriceItem;
import ru.voidrp.gamesync.model.MarketTransactionPushRequest;
import ru.voidrp.gamesync.model.ModdedShopEntry;

public final class EconomyShopGuiBridgeService {
    private static final String PRE_EVENT = "me.gypopo.economyshopgui.api.events.PreTransactionEvent";
    private static final String POST_EVENT = "me.gypopo.economyshopgui.api.events.PostTransactionEvent";

    private static final Set<Material> POTION_MATERIALS = Set.of(
        Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION, Material.TIPPED_ARROW
    );

    private final VoidRpGameSyncPlugin plugin;
    private final ModdedShopConfig moddedShopConfig;
    private final Listener internalListener = new Listener() {};
    private boolean registered = false;
    private boolean economyShopGuiPresent = false;
    private long handledPreTransactions = 0L;
    private long pushedPostTransactions = 0L;
    private long skippedTransactions = 0L;
    private String lastError = "";

    public EconomyShopGuiBridgeService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        this.moddedShopConfig = new ModdedShopConfig(plugin);
    }

    @SuppressWarnings("unchecked")
    public void registerIfAvailable() {
        if (registered || !plugin.getGameSyncConfig().isEconomyMarketEnabled() || !plugin.getGameSyncConfig().isEconomyShopGuiBridgeEnabled()) {
            return;
        }

        Plugin esguiPlugin = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
        if (esguiPlugin == null) {
            esguiPlugin = Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium");
        }
        economyShopGuiPresent = esguiPlugin != null;

        if (!economyShopGuiPresent) {
            plugin.getLogger().info("EconomyShopGUI not found. Dynamic shop bridge is disabled.");
            return;
        }

        try {
            ClassLoader apiClassLoader = esguiPlugin.getClass().getClassLoader();
            Class<?> preRaw = Class.forName(PRE_EVENT, true, apiClassLoader);
            Class<?> postRaw = Class.forName(POST_EVENT, true, apiClassLoader);

            if (!Event.class.isAssignableFrom(preRaw) || !Event.class.isAssignableFrom(postRaw)) {
                lastError = "EconomyShopGUI API classes are not Bukkit events.";
                plugin.getLogger().warning(lastError);
                return;
            }

            Class<? extends Event> preClass = (Class<? extends Event>) preRaw;
            Class<? extends Event> postClass = (Class<? extends Event>) postRaw;

            Bukkit.getPluginManager().registerEvent(
                    preClass,
                    internalListener,
                    EventPriority.HIGHEST,
                    (listener, event) -> {
                        try {
                            handlePreTransaction(event);
                        } catch (Throwable throwable) {
                            lastError = throwable.getMessage();
                            throw new EventException(throwable);
                        }
                    },
                    plugin,
                    true
            );

            Bukkit.getPluginManager().registerEvent(
                    postClass,
                    internalListener,
                    EventPriority.MONITOR,
                    (listener, event) -> {
                        try {
                            handlePostTransaction(event);
                        } catch (Throwable throwable) {
                            lastError = throwable.getMessage();
                            throw new EventException(throwable);
                        }
                    },
                    plugin,
                    true
            );

            registered = true;
            plugin.getLogger().info("EconomyShopGUI dynamic bridge registered.");
        } catch (ClassNotFoundException exception) {
            lastError = "EconomyShopGUI API events were not found: " + exception.getMessage();
            plugin.getLogger().warning(lastError);
        } catch (Throwable throwable) {
            lastError = throwable.getMessage();
            plugin.getLogger().warning("EconomyShopGUI bridge registration failed: " + throwable.getMessage());
        }
    }

    private void handlePreTransaction(Event event) throws Exception {
        if (!PRE_EVENT.equals(event.getClass().getName())) return;

        Player player = (Player) call(event, "getPlayer");
        if (player == null) return;

        // --- Modded item proxy (runs before real-price guard) ---
        Map<?, ?> multiItems = asMap(call(event, "getItems"));
        if (multiItems == null || multiItems.isEmpty()) {
            Object shopItemProxy = call(event, "getShopItem");
            if (shopItemProxy != null) {
                String proxySection = safeString(fieldQuiet(shopItemProxy, "section"));
                String proxyLoc     = safeString(fieldQuiet(shopItemProxy, "itemLoc"));
                ModdedShopEntry modded = moddedShopConfig.get(proxySection + "." + proxyLoc);
                if (modded != null) {
                    handleModdedTransaction(event, player, modded);
                    return;
                }
            }
        }
        // --- End modded proxy ---

        // --- Potion type sell guard ---
        // ESGUI's match() on Mohist compares only Material, not PotionType.
        // This lets players sell any POTION/TIPPED_ARROW as any specific type (e.g. water bottle as healing).
        // We intercept here unconditionally and cancel if the player lacks the required potion type.
        if (multiItems == null || multiItems.isEmpty()) {
            Object shopItemPot = call(event, "getShopItem");
            if (shopItemPot != null) {
                String potTxType = enumName(call(event, "getTransactionType"));
                if (isSell(potTxType)) {
                    ItemStack expectedPotion = itemToGive(shopItemPot);
                    if (expectedPotion != null && POTION_MATERIALS.contains(expectedPotion.getType())) {
                        if (expectedPotion.getItemMeta() instanceof PotionMeta pm) {
                            PotionType expectedPt = pm.getBasePotionType();
                            if (expectedPt != null) {
                                int sellAmt = Math.max(1, ((Number) call(event, "getAmount")).intValue());
                                int found = countPotionTypeInInventory(player, expectedPotion.getType(), expectedPt);
                                if (found < sellAmt) {
                                    call(event, "setCancelled", new Class[]{boolean.class}, new Object[]{true});
                                    player.sendMessage("§cУ вас нет §e"
                                            + potionLabel(expectedPotion.getType(), expectedPt)
                                            + "§c для продажи.");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
        // --- End potion type guard ---

        // --- Potion type sellall guard ---
        // For sellall, ESGUI fires one PreTransactionEvent with getItems() populated.
        // match() on Mohist ignores PotionType, so water bottles etc. appear in the sell list.
        // We cancel the event and manually process only potions of the correct type.
        if (multiItems != null && !multiItems.isEmpty()) {
            String txType = enumName(call(event, "getTransactionType"));
            if (isSell(txType) && handleSellAllPotionGuard(event, player, multiItems)) return;
        }
        // --- End sellall potion guard ---

        if (!plugin.getGameSyncConfig().isEconomyMarketRealPriceEnabled()) {
            return;
        }

        if (shouldIgnore(player)) {
            skippedTransactions++;
            return;
        }

        Map<?, ?> items = asMap(call(event, "getItems"));
        if (items != null && !items.isEmpty()) {
            // Sell-all/multi-item events expose only grouped prices per EcoType.
            // We do not rewrite them in Stage 3 to avoid wrong payouts; we still record them in PostTransactionEvent.
            return;
        }

        Object shopItem = call(event, "getShopItem");
        if (shopItem == null) {
            return;
        }

        ItemStack stack = itemToGive(shopItem);
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }

        String type = enumName(call(event, "getTransactionType"));
        boolean buy = isBuy(type);
        boolean sell = isSell(type);
        if (buy && !plugin.getGameSyncConfig().isEconomyMarketApplyBuyPrice()) return;
        if (sell && !plugin.getGameSyncConfig().isEconomyMarketApplySellPrice()) return;
        if (!buy && !sell) return;

        MarketPriceItem market = plugin.getEconomyMarketCache().get(resolveMaterial(stack));
        if (market == null) {
            market = plugin.getEconomyMarketCache().get(stack.getType());
        }
        if (market == null) {
            return;
        }

        int amount = Math.max(1, ((Number) call(event, "getAmount")).intValue());
        double unit = buy ? market.marketBuyPrice() : market.marketSellPrice();
        if (unit <= 0D) {
            return;
        }

        double originalTotal = number(call(event, "getOriginalPrice"));
        double targetTotal = round2(unit * amount);
        if (targetTotal < 0D) {
            return;
        }

        call(event, "setPrice", new Class<?>[]{double.class}, new Object[]{targetTotal});
        handledPreTransactions++;

        if (plugin.getGameSyncConfig().isEconomyMarketNotifyActionbar()) {
            String label = buy ? "покупки" : "продажи";
            sendActionBar(player, "§6VoidRP рынок: §fцена " + label + " §e" + money(targetTotal) + "§7 (" + stack.getType().name() + " x" + amount + ")");
        }
        if (plugin.getGameSyncConfig().isEconomyMarketNotifyChat() && Math.abs(targetTotal - originalTotal) >= 0.01D) {
            player.sendMessage("§7Рыночная цена применена: §f" + money(targetTotal) + " §8(было " + money(originalTotal) + ")");
        }
    }

    private void handlePostTransaction(Event event) throws Exception {
        if (!POST_EVENT.equals(event.getClass().getName())) return;
        if (!plugin.getGameSyncConfig().isEconomyMarketPushTransactions()) {
            return;
        }

        Player player = (Player) call(event, "getPlayer");
        if (shouldIgnore(player)) {
            skippedTransactions++;
            return;
        }

        String result = enumName(call(event, "getTransactionResult"));
        if (!isSuccessfulResult(result)) {
            return;
        }

        String type = enumName(call(event, "getTransactionType")).toLowerCase(Locale.ROOT);
        Map<?, ?> items = asMap(call(event, "getItems"));
        if (items != null && !items.isEmpty()) {
            if (!plugin.getGameSyncConfig().isEconomyMarketRecordMultiItemTransactions()) {
                return;
            }
            for (Map.Entry<?, ?> entry : items.entrySet()) {
                Object shopItem = entry.getKey();
                int amount = asInt(entry.getValue(), 0);
                if (amount <= 0) continue;
                ItemStack stack = itemToGive(shopItem);
                if (stack == null || stack.getType() == Material.AIR) continue;
                pushTransactionAsync(player, shopItem, stack, amount, type, 0D, 0D, 1D, true);
            }
            return;
        }

        Object shopItem = call(event, "getShopItem");
        if (shopItem == null) return;

        ItemStack stack = itemToGive(shopItem);
        if (stack == null || stack.getType() == Material.AIR) return;

        int amount = Math.max(1, ((Number) call(event, "getAmount")).intValue());
        double finalTotal = number(call(event, "getPrice"));

        double marketMultiplier = 1D;
        MarketPriceItem market = plugin.getEconomyMarketCache().get(stack.getType());
        if (market != null) {
            if (isBuy(type) && market.base_buy_price > 0D) {
                marketMultiplier = market.current_buy_price / market.base_buy_price;
            } else if (isSell(type) && market.base_sell_price > 0D) {
                marketMultiplier = market.current_sell_price / market.base_sell_price;
            }
        }

        pushTransactionAsync(player, shopItem, stack, amount, type, finalTotal, finalTotal, marketMultiplier, false);
    }

    private void pushTransactionAsync(
            Player player,
            Object shopItem,
            ItemStack stack,
            int amount,
            String transactionType,
            double baseTotal,
            double finalTotal,
            double multiplier,
            boolean multiItemApproximation
    ) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "economyshopgui");
        metadata.put("transaction_type_raw", transactionType);
        metadata.put("multi_item_approximation", multiItemApproximation);
        metadata.put("shop_item_path", safeString(callQuiet(shopItem, "getItemPath")));

        String section = safeString(fieldQuiet(shopItem, "section"));
        String itemLoc = safeString(fieldQuiet(shopItem, "itemLoc"));
        metadata.put("shop_section", section);
        metadata.put("shop_item_index", itemLoc);

        MarketTransactionPushRequest request = new MarketTransactionPushRequest(
                player.getName(),
                resolveMaterial(stack),
                amount,
                transactionType,
                round2(baseTotal),
                round2(finalTotal),
                round6(multiplier <= 0D ? 1D : multiplier),
                displayName(stack),
                emptyToNull(section),
                emptyToNull(itemLoc),
                "economyshopgui",
                metadata
        );

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushMarketTransaction(request);
                pushedPostTransactions++;
                if (plugin.getGameSyncConfig().isVerboseSync()) {
                    plugin.getLogger().info("Pushed ESGUI transaction: " + request.material() + " x" + request.amount() + " " + request.transaction_type());
                }
            } catch (Exception exception) {
                lastError = exception.getMessage();
                if (plugin.getGameSyncConfig().isVerboseSync()) {
                    plugin.getLogger().warning("Failed to push ESGUI transaction: " + exception.getMessage());
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Modded item proxy
    // -------------------------------------------------------------------------

    private void handleModdedTransaction(Event event, Player player, ModdedShopEntry modded) throws Exception {
        String type  = enumName(call(event, "getTransactionType"));
        boolean buy  = isBuy(type);
        boolean sell = isSell(type);
        int amount   = Math.max(1, ((Number) call(event, "getAmount")).intValue());

        // Always cancel ESGUI's default handling — we do everything ourselves
        call(event, "setCancelled", new Class[]{boolean.class}, new Object[]{true});

        // Check market cache for dynamic price; fall back to modded_items.yml base price.
        // locked=true entries always use the base price regardless of market cache.
        MarketPriceItem market = modded.locked() ? null : plugin.getEconomyMarketCache().get(modded.moddedId());

        if (buy) {
            if (plugin.getEconomy() == null) {
                player.sendMessage("§cЭкономика недоступна.");
                return;
            }
            double unitBuy = (market != null && market.marketBuyPrice() > 0)
                    ? market.marketBuyPrice() : modded.buyPrice();
            double price    = round2(unitBuy * amount);
            double basePrice = round2(modded.buyPrice() * amount);
            if (plugin.getEconomy().getBalance(player) < price) {
                player.sendMessage("§cНедостаточно средств! Нужно §e" + money(price)
                        + "§c, у вас §e" + money(plugin.getEconomy().getBalance(player)));
                return;
            }
            plugin.getEconomy().withdrawPlayer(player, price);
            String cmd = buildGiveCommand(player.getName(), modded.moddedId(), amount);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            if (plugin.getGameSyncConfig().isEconomyMarketNotifyActionbar()) {
                sendActionBar(player, "§6Куплено: §f" + modded.displayName()
                        + " §ex" + amount + " §8за §e" + money(price));
            }
            pushModdedTransactionAsync(player, modded, amount, "buy", basePrice, price);

        } else if (sell) {
            if (modded.sellPrice() <= 0D) {
                player.sendMessage("§cЭтот предмет нельзя продать.");
                return;
            }
            if (plugin.getEconomy() == null) {
                player.sendMessage("§cЭкономика недоступна.");
                return;
            }
            int found = countModdedInInventory(player, modded.moddedId());
            if (found <= 0) {
                player.sendMessage("§cУ вас нет §e" + modded.displayName() + " §cдля продажи.");
                return;
            }
            int toSell = Math.min(amount, found);
            double unitSell = (market != null && market.marketSellPrice() > 0)
                    ? market.marketSellPrice() : modded.sellPrice();
            removeModdedFromInventory(player, modded.moddedId(), toSell);
            double price    = round2(unitSell * toSell);
            double basePrice = round2(modded.sellPrice() * toSell);
            plugin.getEconomy().depositPlayer(player, price);
            if (plugin.getGameSyncConfig().isEconomyMarketNotifyActionbar()) {
                sendActionBar(player, "§6Продано: §f" + modded.displayName()
                        + " §ex" + toSell + " §8за §e" + money(price));
            }
            pushModdedTransactionAsync(player, modded, toSell, "sell", basePrice, price);
        }
    }

    private int countModdedInInventory(Player player, String moddedId) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isModdedItem(stack, moddedId)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void removeModdedFromInventory(Player player, String moddedId, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (!isModdedItem(stack, moddedId)) continue;
            int stackSize = stack.getAmount();
            if (stackSize <= remaining) {
                remaining -= stackSize;
                player.getInventory().setItem(i, null);
            } else {
                stack.setAmount(stackSize - remaining);
                remaining = 0;
            }
        }
    }

    private boolean isModdedItem(ItemStack stack, String moddedId) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        // Special: ominous bottle distinguished by amplifier level (minecraft:ominous_bottle_1 … _5)
        if (moddedId.startsWith("minecraft:ominous_bottle_")) {
            if (stack.getType() != Material.OMINOUS_BOTTLE) return false;
            try {
                int strength = Integer.parseInt(moddedId.substring("minecraft:ominous_bottle_".length()));
                if (stack.getItemMeta() instanceof OminousBottleMeta meta) {
                    int expected = strength - 1; // strength 1-5 → amplifier 0-4
                    // amplifier=0 is the default — component may be absent on naturally-dropped level-1 bottles
                    if (expected == 0) return !meta.hasAmplifier() || meta.getAmplifier() == 0;
                    return meta.hasAmplifier() && meta.getAmplifier() == expected;
                }
            } catch (NumberFormatException ignored) {}
            return false;
        }
        // Primary: Mohist maps NeoForge items → Bukkit Material with matching NamespacedKey
        String keyStr = stack.getType().getKey().toString();
        if (moddedId.equalsIgnoreCase(keyStr)) return true;
        // Fallback: some Mohist builds use NAMESPACE_KEY format for material name
        String expectedName = moddedId.toUpperCase(Locale.ROOT).replace(":", "_").replace("-", "_");
        return stack.getType().name().equals(expectedName);
    }

    private String buildGiveCommand(String playerName, String moddedId, int amount) {
        // Ominous bottles need the ominous_bottle_amplifier component (amplifier = strength - 1)
        if (moddedId.startsWith("minecraft:ominous_bottle_")) {
            try {
                int strength = Integer.parseInt(moddedId.substring("minecraft:ominous_bottle_".length()));
                int amplifier = strength - 1;
                return "minecraft:give " + playerName + " minecraft:ominous_bottle[ominous_bottle_amplifier=" + amplifier + "] " + amount;
            } catch (NumberFormatException ignored) {}
        }
        return "minecraft:give " + playerName + " " + moddedId + " " + amount;
    }

    private void pushModdedTransactionAsync(Player player, ModdedShopEntry modded,
                                            int amount, String type, double basePrice, double finalPrice) {
        if (!plugin.getGameSyncConfig().isEconomyMarketPushTransactions()) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("source", "economyshopgui_modded");
        meta.put("modded_proxy", true);
        double multiplier = (basePrice > 0D) ? round6(finalPrice / basePrice) : 1D;
        MarketTransactionPushRequest req = new MarketTransactionPushRequest(
                player.getName(),
                modded.moddedId(),
                amount,
                type,
                round2(basePrice),
                round2(finalPrice),
                multiplier,
                modded.displayName(),
                null, null,
                "economyshopgui_modded",
                meta
        );
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushMarketTransaction(req);
                pushedPostTransactions++;
            } catch (Exception exception) {
                lastError = exception.getMessage();
            }
        });
    }

    /** Returns true if potions were found in the sell-all items map and handled (event cancelled). */
    private boolean handleSellAllPotionGuard(Event event, Player player, Map<?, ?> items) throws Exception {
        // Build list of potion entries only
        List<Object> potionShopItems = new ArrayList<>();
        for (Map.Entry<?, ?> entry : items.entrySet()) {
            ItemStack expected = itemToGive(entry.getKey());
            if (expected != null && POTION_MATERIALS.contains(expected.getType())
                    && expected.getItemMeta() instanceof PotionMeta pm
                    && pm.getBasePotionType() != null) {
                potionShopItems.add(entry.getKey());
            }
        }
        if (potionShopItems.isEmpty()) return false;

        // Cancel ESGUI's default handling — it counted wrong types
        call(event, "setCancelled", new Class[]{boolean.class}, new Object[]{true});

        if (plugin.getEconomy() == null) {
            player.sendMessage("§cЭкономика недоступна.");
            return true;
        }

        int totalSold = 0;
        double totalEarned = 0;

        for (Object shopItem : potionShopItems) {
            ItemStack expected = itemToGive(shopItem);
            if (expected == null) continue;
            PotionMeta pm = (PotionMeta) expected.getItemMeta();
            PotionType expectedPt = pm.getBasePotionType();
            if (expectedPt == null) continue;

            int found = countPotionTypeInInventory(player, expected.getType(), expectedPt);
            if (found <= 0) continue;

            double unitSell = getSellPriceForShopItem(shopItem, player, expected);
            if (unitSell <= 0) continue;

            removePotionTypeFromInventory(player, expected.getType(), expectedPt, found);
            double earned = round2(unitSell * found);
            plugin.getEconomy().depositPlayer(player, earned);
            totalSold += found;
            totalEarned += earned;

            if (plugin.getGameSyncConfig().isEconomyMarketPushTransactions()) {
                pushTransactionAsync(player, shopItem, expected, found, "sell", earned, earned, 1D, false);
            }
        }

        if (totalSold > 0) {
            sendActionBar(player, "§6VoidRP: §fпродано зелий §e" + totalSold + " §8на §e" + money(totalEarned));
        } else {
            player.sendMessage("§cНет подходящих зелий нужного типа для продажи.");
        }
        return true;
    }

    private double getSellPriceForShopItem(Object shopItem, Player player, ItemStack stack) {
        // 1. Market cache (dynamic pricing, keyed as POTION:HEALING etc.)
        MarketPriceItem market = plugin.getEconomyMarketCache().get(resolveMaterial(stack));
        if (market == null) market = plugin.getEconomyMarketCache().get(stack.getType());
        if (market != null && market.marketSellPrice() > 0) return market.marketSellPrice();

        // 2. ESGUI getSellPrice(Player) — player-specific price modifiers included
        try {
            Method m = shopItem.getClass().getMethod("getSellPrice", Player.class);
            Object r = m.invoke(shopItem, player);
            if (r instanceof Number n && n.doubleValue() > 0) return n.doubleValue();
        } catch (Exception ignored) {}

        // 3. getSellPrice() no-arg fallback
        try {
            Object r = call(shopItem, "getSellPrice");
            if (r instanceof Number n && n.doubleValue() > 0) return n.doubleValue();
        } catch (Exception ignored) {}

        // 4. Direct field access
        Object f = fieldQuiet(shopItem, "sellPrice");
        if (f instanceof Number n && n.doubleValue() > 0) return n.doubleValue();

        return 0;
    }

    private void removePotionTypeFromInventory(Player player, Material material, PotionType type, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            if (!(stack.getItemMeta() instanceof PotionMeta pm)) continue;
            if (!type.equals(pm.getBasePotionType())) continue;
            int size = stack.getAmount();
            if (size <= remaining) {
                player.getInventory().setItem(i, null);
                remaining -= size;
            } else {
                stack.setAmount(size - remaining);
                remaining = 0;
            }
        }
        player.updateInventory();
    }

    private int countPotionTypeInInventory(Player player, Material material, PotionType expectedType) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() != material) continue;
            if (!(stack.getItemMeta() instanceof PotionMeta pm)) continue;
            if (expectedType.equals(pm.getBasePotionType())) count += stack.getAmount();
        }
        return count;
    }

    private String potionLabel(Material material, PotionType type) {
        String prefix = switch (material) {
            case SPLASH_POTION -> "Зелье-снаряд";
            case LINGERING_POTION -> "Длительное зелье";
            case TIPPED_ARROW -> "Стрела";
            default -> "Зелье";
        };
        return prefix + " (" + type.name().toLowerCase(Locale.ROOT).replace("_", " ") + ")";
    }

    private boolean shouldIgnore(Player player) {
        if (player == null) return true;
        if (plugin.getGameSyncConfig().isEconomyMarketIgnoreOpPlayers() && player.isOp()) return true;
        return plugin.getGameSyncConfig().isEconomyMarketIgnoreCreativePlayers()
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR);
    }

    private ItemStack itemToGive(Object shopItem) {
        Object value = callQuiet(shopItem, "getItemToGive");
        if (value instanceof ItemStack stack) return stack;
        value = callQuiet(shopItem, "getShopItem");
        if (value instanceof ItemStack stack) return stack;
        return null;
    }

    private String resolveMaterial(ItemStack stack) {
        if (stack == null) return "AIR";
        Material type = stack.getType();
        if (type == Material.ENCHANTED_BOOK) {
            if (!(stack.getItemMeta() instanceof EnchantmentStorageMeta esm)) {
                return Material.ENCHANTED_BOOK.name();
            }
            Map<Enchantment, Integer> stored = esm.getStoredEnchants();
            if (stored.isEmpty()) {
                return Material.ENCHANTED_BOOK.name();
            }
            // Single enchantment — most common case
            if (stored.size() == 1) {
                Map.Entry<Enchantment, Integer> entry = stored.entrySet().iterator().next();
                String key = entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT);
                return "ENCHANTED_BOOK:" + key + ":" + entry.getValue();
            }
            // Multiple enchantments — sort for determinism
            return stored.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getKey().getKey()))
                .map(e -> e.getKey().getKey().getKey().toUpperCase(Locale.ROOT) + ":" + e.getValue())
                .collect(Collectors.joining("+", "ENCHANTED_BOOK:", ""));
        }
        if (POTION_MATERIALS.contains(type)) {
            if (stack.getItemMeta() instanceof PotionMeta pm) {
                PotionType pt = pm.getBasePotionType();
                if (pt != null) {
                    return type.name() + ":" + pt.name();
                }
            }
            return type.name();
        }
        return type.name();
    }

    private String displayName(ItemStack stack) {
        if (stack == null) return "";
        if (stack.hasItemMeta() && stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()) {
            return stack.getItemMeta().getDisplayName();
        }
        Material type = stack.getType();
        if (type == Material.ENCHANTED_BOOK
                && stack.getItemMeta() instanceof EnchantmentStorageMeta esm
                && !esm.getStoredEnchants().isEmpty()) {
            return esm.getStoredEnchants().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getKey().getKey()))
                .map(e -> e.getKey().getKey().getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ", "Enchanted Book (", ")"));
        }
        if (POTION_MATERIALS.contains(type) && stack.getItemMeta() instanceof PotionMeta pm) {
            PotionType pt = pm.getBasePotionType();
            if (pt != null) {
                String prefix = switch (type) {
                    case SPLASH_POTION -> "Splash Potion (";
                    case LINGERING_POTION -> "Lingering Potion (";
                    case TIPPED_ARROW -> "Tipped Arrow (";
                    default -> "Potion (";
                };
                return prefix + pt.name() + ")";
            }
        }
        return type.name();
    }

    private boolean isBuy(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return value.contains("buy") && !value.contains("sell");
    }

    private boolean isSell(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return value.contains("sell");
    }

    private boolean isSuccessfulResult(String result) {
        String value = result == null ? "" : result.toUpperCase(Locale.ROOT);
        return value.startsWith("SUCCESS") || value.equals("NOT_ALL_ITEMS_ADDED");
    }

    private void sendActionBar(Player player, String message) {
        try {
            Method method = player.getClass().getMethod("sendActionBar", String.class);
            method.invoke(player, message);
        } catch (Exception ignored) {
            player.sendMessage(message);
        }
    }

    private Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private Object call(Object target, String method, Class<?>[] types, Object[] args) throws Exception {
        Method m = target.getClass().getMethod(method, types);
        return m.invoke(target, args);
    }

    private Object callQuiet(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object fieldQuiet(Object target, String field) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getField(field);
            return f.get(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private String enumName(Object value) {
        if (value instanceof Enum<?> e) return e.name();
        return value == null ? "" : String.valueOf(value);
    }

    private double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0D;
    }

    private int asInt(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0D) / 1_000_000.0D;
    }

    private String money(double value) {
        return String.format(Locale.US, "%.2f", round2(value));
    }

    public ModdedShopConfig getModdedShopConfig() {
        return moddedShopConfig;
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean isEconomyShopGuiPresent() {
        return economyShopGuiPresent;
    }

    public long getHandledPreTransactions() {
        return handledPreTransactions;
    }

    public long getPushedPostTransactions() {
        return pushedPostTransactions;
    }

    public long getSkippedTransactions() {
        return skippedTransactions;
    }

    public String getLastError() {
        return lastError;
    }
}
