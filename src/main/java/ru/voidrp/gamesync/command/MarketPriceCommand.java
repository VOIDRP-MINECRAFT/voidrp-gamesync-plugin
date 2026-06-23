package ru.voidrp.gamesync.command;

import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.MarketPriceItem;

public final class MarketPriceCommand implements CommandExecutor, TabCompleter {
    private final VoidRpGameSyncPlugin plugin;

    public MarketPriceCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String material;
        if (args.length >= 1) {
            material = args[0].trim().toUpperCase(Locale.ROOT);
        } else if (sender instanceof Player player) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                player.sendMessage("§eВозьми предмет в руку или напиши: /" + label + " <предмет>");
                return true;
            }
            material = hand.getType().name();
        } else {
            sender.sendMessage("§eИспользование: /" + label + " <material>");
            return true;
        }

        MarketPriceItem item = plugin.getEconomyMarketCache().get(material);
        if (item == null) {
            sender.sendMessage("§eПо предмету §f" + material + " §eпока нет рыночных данных.");
            sender.sendMessage("§7После первых сделок backend создаст рыночную запись автоматически.");
            return true;
        }

        sender.sendMessage("§6=== Рыночная цена: §f" + material + " §6===");
        sender.sendMessage("§7Покупка: §a" + money(item.current_buy_price));
        sender.sendMessage("§7Скупка: §e" + money(item.current_sell_price));
        sender.sendMessage("§7Множитель покупки: §f" + money(item.buy_multiplier) + "x");
        sender.sendMessage("§7Спрос/предложение: §f" + money(item.demand_score) + "§8/§f" + money(item.supply_score));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String query = args[0].toLowerCase(Locale.ROOT);
            return plugin.getEconomyMarketCache().all().stream()
                    .map(item -> item.material)
                    .filter(value -> value != null && value.toLowerCase(Locale.ROOT).startsWith(query))
                    .sorted()
                    .limit(30)
                    .toList();
        }
        return List.of();
    }

    private String money(double value) {
        return String.format(Locale.US, "%.2f", Math.round(value * 100.0D) / 100.0D);
    }
}
