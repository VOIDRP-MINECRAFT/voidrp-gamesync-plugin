package ru.voidrp.gamesync.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.model.NationResearchNodeState;
import ru.voidrp.gamesync.model.NationResearchOverviewResponse;
import ru.voidrp.gamesync.model.NationResearchPurchaseResponse;
import ru.voidrp.gamesync.service.BackendApiException;

/** {@code /nres} — view and buy nation research (tech tree). */
public final class NationResearchCommand implements CommandExecutor, TabCompleter {

    private static final List<String> NODE_KEYS = List.of(
        "market_guilds", "capital_workshops", "labor_exchange",
        "academy", "gathering_industry", "central_bank"
    );

    private final VoidRpGameSyncPlugin plugin;

    public NationResearchCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
        if (nation == null) {
            player.sendMessage("§cТы не состоишь в государстве.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("buy")) {
            if (args.length < 2) {
                player.sendMessage("§cИспользование: §f/" + label + " buy <исследование>");
                return true;
            }
            buy(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }

        // With WebGUI available, open the premium tech-tree page instead of chat.
        if (plugin.getGameSyncConfig().isWebGuiEnabled()) {
            plugin.getWebGuiBridgeService().openResearch(player);
            return true;
        }

        showOverview(player);
        return true;
    }

    private void showOverview(Player player) {
        player.sendMessage("§7Загружаем технологическое древо...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationResearchOverviewResponse overview =
                        plugin.getBackendClient().getNationResearchOverview(player.getName());
                Bukkit.getScheduler().runTask(plugin, () -> renderOverview(player, overview));
            } catch (BackendApiException api) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§c" + api.getMessage()));
            } catch (IOException | InterruptedException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось загрузить исследования.");
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void renderOverview(Player player, NationResearchOverviewResponse overview) {
        player.sendMessage("§6=== Технологии государства: §f" + overview.nation_title + " §6===");
        player.sendMessage("§7Казна: §a" + money(overview.treasury_balance));
        if (overview.nodes != null) {
            for (NationResearchNodeState node : overview.nodes) {
                player.sendMessage("§e" + node.icon + " " + node.title + " §7[" + node.level + "/" + node.max_level + "]");
                player.sendMessage("  §8" + node.description);
                if (node.level >= node.max_level) {
                    player.sendMessage("  §a✔ Максимальный уровень §7(" + formatEffect(node.effect_unit, node.current_effect) + ")");
                } else if (node.locked) {
                    player.sendMessage("  §c🔒 " + (node.lock_reason != null ? node.lock_reason : "Заблокировано"));
                } else {
                    String costColor = node.can_afford ? "§a" : "§c";
                    String next = node.next_effect != null ? formatEffect(node.effect_unit, node.next_effect) : "—";
                    String cost = node.next_cost != null ? money(node.next_cost) : "—";
                    player.sendMessage("  §7След. ур.: §f" + next + " §7за " + costColor + cost
                            + " §7— §f/nres buy " + node.key);
                }
            }
        }
        player.sendMessage("§8Покупать может глава или офицер государства.");
    }

    private void buy(Player player, String researchKey) {
        player.sendMessage("§7Вкладываем средства государства...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                NationResearchPurchaseResponse res =
                        plugin.getBackendClient().purchaseNationResearch(player.getName(), researchKey);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a" + res.message);
                    player.sendMessage("§7Списано из казны: §c-" + money(res.spent)
                            + " §7| Остаток: §a" + money(res.treasury_balance));
                });
            } catch (BackendApiException api) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§c" + api.getMessage()));
            } catch (IOException | InterruptedException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§cНе удалось выполнить покупку исследования.");
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8Причина: " + e.getMessage());
                    }
                });
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("list", "buy")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            List<String> out = new ArrayList<>();
            for (String key : NODE_KEYS) {
                if (key.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(key);
            }
            return out;
        }
        return List.of();
    }

    private String formatEffect(String unit, double value) {
        if (unit == null) return trim(value);
        return switch (unit) {
            case "percent" -> "+" + trim(value) + "%";
            case "level" -> "ур. " + (int) value;
            case "count" -> "+" + (int) value;
            default -> trim(value);
        };
    }

    private String trim(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return String.valueOf(Math.round(value * 100.0D) / 100.0D);
    }

    private String money(double value) {
        return String.format(Locale.US, "%,.2f", value);
    }
}
