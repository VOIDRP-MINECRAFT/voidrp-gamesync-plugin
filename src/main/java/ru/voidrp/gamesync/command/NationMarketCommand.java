package ru.voidrp.gamesync.command;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.MarketPriceItem;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.model.NationMarketCancelRequest;
import ru.voidrp.gamesync.model.NationMarketCancelResponse;
import ru.voidrp.gamesync.model.NationMarketCreateRequest;
import ru.voidrp.gamesync.model.NationMarketListing;
import ru.voidrp.gamesync.model.NationMarketListingListResponse;
import ru.voidrp.gamesync.service.NationMarketConfirmService.PendingListing;

public final class NationMarketCommand implements CommandExecutor, TabCompleter {
    private final VoidRpGameSyncPlugin plugin;

    public NationMarketCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }
        if (!plugin.getGameSyncConfig().isNationMarketEnabled()) {
            player.sendMessage("§cРынок государств сейчас отключён.");
            return true;
        }
        if (args.length == 0) {
            plugin.getNationMarketGuiService().open(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "sell" -> handleSell(player, label, args);
            case "confirm" -> handleConfirm(player);
            case "cancel" -> handleCancel(player, args);
            case "list", "listings" -> handleListings(player);
            case "open", "gui" -> {
                plugin.getNationMarketGuiService().open(player);
                yield true;
            }
            default -> {
                sendHelp(player, label);
                yield true;
            }
        };
    }

    private boolean handleSell(Player player, String label, String[] args) {
        if (!player.hasPermission("voidrp.nmarket.sell")) {
            player.sendMessage("§cНет прав на выставление товаров.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("§eИспользование: /" + label + " sell <количество|all> <цена-за-штуку>");
            return true;
        }

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }
        String role = nation.roleFor(player.getName());
        if (role == null || (!role.equalsIgnoreCase("leader") && !role.equalsIgnoreCase("officer"))) {
            player.sendMessage("§cВыставлять товары могут только глава и офицеры государства.");
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§eВозьми предмет в руку.");
            return true;
        }
        ItemStack sample = hand.clone();
        sample.setAmount(1);

        String itemError = plugin.getNationMarketInventoryService().validateItemAllowed(sample);
        if (itemError != null) {
            player.sendMessage("§c" + itemError);
            return true;
        }

        int available = plugin.getNationMarketInventoryService().countSimilar(player, sample);
        int amount;
        if (args[1].equalsIgnoreCase("all")) {
            amount = available;
        } else {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                player.sendMessage("§cКоличество должно быть числом или all.");
                return true;
            }
        }
        if (amount <= 0) {
            player.sendMessage("§cКоличество должно быть больше 0.");
            return true;
        }
        if (amount > available) {
            player.sendMessage("§cНедостаточно предметов. Доступно: §f" + available);
            return true;
        }

        double price;
        try {
            price = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException exception) {
            player.sendMessage("§cЦена должна быть числом.");
            return true;
        }
        price = round2(price);
        if (price <= 0D) {
            player.sendMessage("§cЦена должна быть больше 0.");
            return true;
        }

        PriceGuardResult guard = checkPrice(sample.getType().name(), price);
        if (guard.hardBlocked()) {
            player.sendMessage("§c" + guard.message());
            return true;
        }

        PendingListing pending = new PendingListing(
                nation,
                role,
                sample,
                amount,
                price,
                guard.marketPrice(),
                guard.message(),
                System.currentTimeMillis()
        );

        if (guard.needsConfirm()) {
            plugin.getNationMarketConfirmService().put(player.getUniqueId(), pending);
            player.sendMessage("§e" + guard.message());
            player.sendMessage("§7Предмет: §f" + sample.getType().name() + " §7x§f" + amount);
            player.sendMessage("§7Цена: §6" + money(price) + " §7за шт.");
            player.sendMessage("§7Подтверди командой: §f/" + label + " confirm");
            return true;
        }

        createListing(player, pending);
        return true;
    }

    private boolean handleConfirm(Player player) {
        PendingListing pending = plugin.getNationMarketConfirmService().consume(
                player.getUniqueId(),
                plugin.getGameSyncConfig().getNationMarketConfirmTimeoutMs()
        );
        if (pending == null) {
            player.sendMessage("§cНет активного подтверждения или время истекло.");
            return true;
        }
        createListing(player, pending);
        return true;
    }

    private void createListing(Player player, PendingListing pending) {
        if (!plugin.getNationMarketInventoryService().removeSimilar(player, pending.sample(), pending.amount())) {
            player.sendMessage("§cНе удалось изъять предметы. Возможно, инвентарь изменился.");
            return;
        }

        String base64;
        try {
            base64 = plugin.getItemStackSnapshotService().serializeSingle(pending.sample());
        } catch (Exception exception) {
            plugin.getNationMarketInventoryService().giveOrDrop(player, pending.sample(), pending.amount());
            player.sendMessage("§cНе удалось сохранить предмет. Предметы возвращены.");
            return;
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("item_type", pending.sample().getType().name());
        metadata.put("created_from", "voidrp_gamesync");

        String displayName = displayName(pending.sample());
        NationMarketCreateRequest request = new NationMarketCreateRequest(
                pending.nation().slug(),
                player.getName(),
                pending.role(),
                pending.sample().getType().name(),
                displayName,
                base64,
                pending.amount(),
                pending.pricePerItem(),
                pending.marketPrice(),
                metadata
        );

        player.sendMessage("§7Выставляем лот на рынок...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationMarketListing listing = plugin.getBackendClient().createNationMarketListing(request);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§aЛот выставлен на рынок государств.");
                    player.sendMessage("§7ID: §f" + listing.id);
                    player.sendMessage("§7Остаток: §f" + listing.remaining_amount + " §7Цена сейчас: §6" + money(listing.current_unit_price));
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getNationMarketInventoryService().giveOrDrop(player, pending.sample(), pending.amount());
                    player.sendMessage("§cBackend не принял лот. Предметы возвращены.");
                    player.sendMessage("§8Причина: " + exception.getMessage());
                });
            }
        });
    }

    private boolean handleCancel(Player player, String[] args) {
        if (!player.hasPermission("voidrp.nmarket.sell")) {
            player.sendMessage("§cНет прав на управление лотами.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§eИспользование: /nmarket cancel <id>");
            return true;
        }
        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }
        String role = nation.roleFor(player.getName());
        if (role == null || (!role.equalsIgnoreCase("leader") && !role.equalsIgnoreCase("officer"))) {
            player.sendMessage("§cОтменять лоты могут только глава и офицеры государства.");
            return true;
        }
        String id = args[1];
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§cНеверный формат ID лота.");
            return true;
        }
        player.sendMessage("§7Отменяем лот...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationMarketCancelResponse response = plugin.getBackendClient().cancelNationMarketListing(
                        id,
                        new NationMarketCancelRequest(player.getName())
                );
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ItemStack item = plugin.getItemStackSnapshotService().deserialize(response.item_stack_base64, 1);
                    plugin.getNationMarketInventoryService().giveOrDrop(player, item, response.returned_amount);
                    player.sendMessage("§aЛот отменён. Возвращено предметов: §f" + response.returned_amount);
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось отменить лот.");
                    player.sendMessage("§8Причина: " + exception.getMessage());
                });
            }
        });
        return true;
    }

    private boolean handleListings(Player player) {
        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }
        player.sendMessage("§7Загружаем лоты государства...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationMarketListingListResponse response = plugin.getBackendClient().listNationMarketListings(nation.slug(), true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§6=== Лоты государства " + nation.title() + " ===");
                    if (response == null || response.items == null || response.items.isEmpty()) {
                        player.sendMessage("§eЛотов пока нет.");
                        return;
                    }
                    int limit = Math.min(10, response.items.size());
                    for (int i = 0; i < limit; i++) {
                        NationMarketListing item = response.items.get(i);
                        player.sendMessage("§8- §f" + shortId(item.id)
                                + " §7" + item.material
                                + " §7остаток §f" + item.remaining_amount + "/" + item.total_amount
                                + " §7цена §6" + money(item.current_unit_price)
                                + " §7статус §f" + item.status);
                    }
                    player.sendMessage("§7Отмена: §f/nmarket cancel <полный-id>");
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cНе удалось получить лоты: §f" + exception.getMessage()));
            }
        });
        return true;
    }

    private PriceGuardResult checkPrice(String material, double price) {
        MarketPriceItem market = plugin.getEconomyMarketCache().get(material);
        if (market == null || market.current_buy_price <= 0D) {
            return new PriceGuardResult(false, true, 0D, "По этому предмету ещё нет рыночных данных. Проверь цену вручную.");
        }

        double marketPrice = market.current_buy_price;
        double serverSell = Math.max(0D, market.current_sell_price);
        double minAllowed = round2(serverSell * plugin.getGameSyncConfig().getNationMarketMinAboveServerSellMultiplier());
        if (serverSell > 0D && price < minAllowed) {
            return new PriceGuardResult(true, false, marketPrice,
                    "Цена слишком низкая. Минимум против перепродажи скупщику: " + money(minAllowed));
        }

        double hardMax = round2(marketPrice * plugin.getGameSyncConfig().getNationMarketHardMaxMarketMultiplier());
        if (marketPrice > 0D && price > hardMax) {
            return new PriceGuardResult(true, false, marketPrice,
                    "Цена слишком завышена. Максимум для первого этапа: " + money(hardMax));
        }

        if (price < marketPrice * plugin.getGameSyncConfig().getNationMarketWarnBelowMarketMultiplier()) {
            return new PriceGuardResult(false, true, marketPrice,
                    "Цена ниже рынка. Рынок: " + money(marketPrice) + ", твоя цена: " + money(price));
        }
        if (price > marketPrice * plugin.getGameSyncConfig().getNationMarketWarnAboveMarketMultiplier()) {
            return new PriceGuardResult(false, true, marketPrice,
                    "Цена сильно выше рынка. Рынок: " + money(marketPrice) + ", твоя цена: " + money(price));
        }
        return new PriceGuardResult(false, false, marketPrice, "OK");
    }

    private void sendHelp(Player player, String label) {
        player.sendMessage("§6/" + label + " §7— открыть рынок");
        player.sendMessage("§6/" + label + " sell <кол-во|all> <цена> §7— выставить предмет из руки");
        player.sendMessage("§6/" + label + " confirm §7— подтвердить подозрительную цену");
        player.sendMessage("§6/" + label + " listings §7— лоты своего государства");
        player.sendMessage("§6/" + label + " cancel <id> §7— снять лот и вернуть остаток");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("sell", "confirm", "listings", "cancel", "open"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sell")) {
            return filter(List.of("all", "16", "32", "64", "100"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> source, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return source.stream().filter(item -> item.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private String displayName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        return item.getType().name();
    }

    private String shortId(String value) { return value == null || value.length() < 8 ? "?" : value.substring(0, 8); }
    private double round2(double value) { return Math.round(value * 100.0D) / 100.0D; }
    private String money(double value) { return String.format(Locale.US, "%.2f", round2(value)); }

    private record PriceGuardResult(boolean hardBlocked, boolean needsConfirm, double marketPrice, String message) {}
}
