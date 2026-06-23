package ru.voidrp.gamesync.command;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.service.PlayerMarketService;
import ru.voidrp.gamesync.service.PlayerMarketService.PendingAction;

public final class PlayerMarketCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;

    public PlayerMarketCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }
        if (!player.hasPermission("voidrp.pm.use")) {
            player.sendMessage("§cУ вас нет прав на использование рынка игроков.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "sell" -> handleSell(player, label, args);
            case "buy" -> handleBuy(player, label, args);
            case "confirm", "да" -> handleConfirm(player);
            case "cancel", "отмена" -> handleCancel(player, label, args);
            case "pickup", "забрать", "pending" -> {
                plugin.getPlayerMarketService().handlePickupCommand(player);
                yield true;
            }
            case "orders" -> {
                if (plugin.getGameSyncConfig().isWebGuiEnabled()) {
                    plugin.getWebGuiBridgeService().openMarket(player);
                } else {
                    plugin.getPlayerMarketGuiService().open(player);
                }
                yield true;
            }
            default -> {
                sendHelp(player, label);
                yield true;
            }
        };
    }

    private boolean handleSell(Player player, String label, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§eИспользование: /" + label + " sell <количество|all> <цена-за-шт>");
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage("§cВозьми предмет в руку.");
            return true;
        }

        double unitPrice;
        try {
            unitPrice = Double.parseDouble(args[2].replace(',', '.'));
        } catch (NumberFormatException ex) {
            player.sendMessage("§cЦена должна быть числом.");
            return true;
        }
        if (unitPrice <= 0D) {
            player.sendMessage("§cЦена должна быть больше 0.");
            return true;
        }

        if (args[1].equalsIgnoreCase("all")) {
            plugin.getPlayerMarketService().handleSellCommandAll(player, unitPrice);
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage("§cКоличество должно быть числом или all.");
            return true;
        }

        plugin.getPlayerMarketService().handleSellCommand(player, amount, unitPrice);
        return true;
    }

    private boolean handleBuy(Player player, String label, String[] args) {
        if (args.length < 4) {
            player.sendMessage("§eИспользование: /" + label + " buy <item_key> <количество> <цена-за-шт>");
            player.sendMessage("§7Пример: §f/" + label + " buy minecraft:diamond 64 500");
            return true;
        }

        String itemKey = args[1].toLowerCase(Locale.ROOT);

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            player.sendMessage("§cКоличество должно быть числом.");
            return true;
        }

        double unitPrice;
        try {
            unitPrice = Double.parseDouble(args[3].replace(',', '.'));
        } catch (NumberFormatException ex) {
            player.sendMessage("§cЦена должна быть числом.");
            return true;
        }

        plugin.getPlayerMarketService().handleBuyCommand(player, itemKey, amount, unitPrice);
        return true;
    }

    private boolean handleConfirm(Player player) {
        UUID uuid = player.getUniqueId();
        if (!plugin.getPlayerMarketService().hasPendingAction(uuid)) {
            player.sendMessage("§cНет активного подтверждения или время истекло (60 сек).");
            return true;
        }
        PendingAction action = plugin.getPlayerMarketService().consumePendingAction(uuid);
        if (action == null) {
            player.sendMessage("§cПодтверждение истекло.");
            return true;
        }
        executePendingAction(player, action);
        return true;
    }

    private boolean handleCancel(Player player, String label, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§eИспользование: /" + label + " cancel sell <orderId>");
            player.sendMessage("§eИспользование: /" + label + " cancel buy <orderId>");
            return true;
        }

        String type = args[1].toLowerCase(Locale.ROOT);
        String orderId = args[2];

        try {
            UUID.fromString(orderId);
        } catch (IllegalArgumentException ex) {
            player.sendMessage("§cНеверный формат ID ордера (ожидается UUID).");
            return true;
        }

        switch (type) {
            case "sell" -> plugin.getPlayerMarketService().handleCancelSellCommand(player, orderId);
            case "buy" -> plugin.getPlayerMarketService().handleCancelBuyCommand(player, orderId);
            default -> {
                player.sendMessage("§cТип ордера: sell или buy.");
                return true;
            }
        }
        return true;
    }

    public void executePendingAction(Player player, PendingAction action) {
        switch (action.type) {
            case SELL -> plugin.getPlayerMarketService().executeSell(player, action);
            case BUY -> plugin.getPlayerMarketService().executeBuy(player, action);
        }
    }

    private void sendHelp(Player player, String label) {
        player.sendMessage("§8§m                                        ");
        player.sendMessage("§6§l  Рынок игроков §8(/pm)");
        player.sendMessage("§8§m                                        ");
        player.sendMessage("§e  /" + label + " sell <кол-во|all> <цена>");
        player.sendMessage("§7    Выставить предмет §8(из руки)§7 на продажу");
        player.sendMessage("§e  /" + label + " buy <item_key> <кол-во> <цена>");
        player.sendMessage("§7    Создать заявку на покупку предмета");
        player.sendMessage("§7    §8Пример: §f/" + label + " buy minecraft:diamond 64 500");
        player.sendMessage("§e  /" + label + " confirm §7(или §fда§7)");
        player.sendMessage("§7    Подтвердить ожидающее действие");
        player.sendMessage("§e  /" + label + " pickup §7— Забрать незабранные деньги/предметы");
        player.sendMessage("§e  /" + label + " orders §7— Открыть GUI ордеров");
        player.sendMessage("§e  /" + label + " cancel sell|buy <id> §7— Отменить ордер");
        player.sendMessage("§e  /shop §7— Открыть GUI рынка");
        player.sendMessage("§8§m                                        ");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("sell", "buy", "confirm", "cancel", "pickup", "orders"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("sell")) {
                return filter(List.of("all", "1", "16", "32", "64"), args[1]);
            }
            if (sub.equals("cancel")) {
                return filter(List.of("sell", "buy"), args[1]);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            return filter(List.of("1", "10", "100", "500", "1000", "5000", "10000"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buy")) {
            return filter(List.of("1", "16", "32", "64"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("buy")) {
            return filter(List.of("1", "10", "100", "500", "1000", "5000", "10000"), args[3]);
        }
        return List.of();
    }

    private List<String> filter(List<String> source, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return source.stream().filter(item -> item.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
