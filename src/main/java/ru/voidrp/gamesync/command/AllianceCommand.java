package ru.voidrp.gamesync.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.GameAllianceApplyRequest;
import ru.voidrp.gamesync.model.GameAllianceKickRequest;
import ru.voidrp.gamesync.model.GameAllianceLeaveRequest;
import ru.voidrp.gamesync.model.GameAllianceProposal;
import ru.voidrp.gamesync.model.GameAllianceProposalListResponse;
import ru.voidrp.gamesync.model.GameAllianceVoteRequest;

public final class AllianceCommand implements CommandExecutor, TabCompleter {

    private final VoidRpGameSyncPlugin plugin;
    // Per-player ordered proposal IDs from last /alliance proposals run
    private final Map<UUID, List<String>> lastProposals = new HashMap<>();

    public AllianceCommand(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда только для игроков.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "apply"     -> handleApply(player, args);
            case "leave"     -> handleLeave(player);
            case "proposals",
                 "list"      -> handleProposals(player);
            case "vote"      -> handleVote(player, args);
            case "kick"      -> handleKick(player, args);
            default          -> sendHelp(player);
        }
        return true;
    }

    // ── Subcommands ───────────────────────────────────────────────────────────

    private void handleApply(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cИспользование: /alliance apply <slug_альянса>");
            return;
        }
        String slug = args[1];
        player.sendMessage("§7Отправляем заявку в альянс §e" + slug + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resp = plugin.getBackendClient().applyToAlliance(new GameAllianceApplyRequest(player.getName(), slug));
                String msg = resp.message != null ? resp.message : "Заявка подана.";
                sync(player, () -> player.sendMessage("§a[Альянс] §f" + msg));
            } catch (IOException | InterruptedException e) {
                sync(player, () -> {
                    player.sendMessage("§c[Альянс] §f" + extractDetail(e.getMessage()));
                    if (plugin.getGameSyncConfig().isVerboseSync()) {
                        player.sendMessage("§8" + e.getMessage());
                    }
                });
            }
        });
    }

    private void handleLeave(Player player) {
        player.sendMessage("§7Покидаем альянс...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resp = plugin.getBackendClient().leaveAlliance(new GameAllianceLeaveRequest(player.getName()));
                String msg = resp.message != null ? resp.message : "Вы вышли из альянса.";
                sync(player, () -> player.sendMessage("§a[Альянс] §f" + msg));
            } catch (IOException | InterruptedException e) {
                sync(player, () -> player.sendMessage("§c[Альянс] §f" + extractDetail(e.getMessage())));
            }
        });
    }

    private void handleProposals(Player player) {
        player.sendMessage("§7Загружаем предложения альянса...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                GameAllianceProposalListResponse resp = plugin.getBackendClient().getMyAllianceProposals(player.getName());
                sync(player, () -> {
                    if (resp.proposals == null || resp.proposals.isEmpty()) {
                        player.sendMessage("§e[Альянс] §7Нет открытых предложений в «§f" + resp.alliance_title + "§7».");
                        lastProposals.remove(player.getUniqueId());
                        return;
                    }
                    List<String> ids = new ArrayList<>();
                    player.sendMessage("§6§l[" + resp.alliance_title + "] §6Открытые предложения:");
                    for (int i = 0; i < resp.proposals.size(); i++) {
                        GameAllianceProposal p = resp.proposals.get(i);
                        ids.add(p.id);
                        String myVote = p.my_vote != null ? " §7(ваш: §e" + voteLabel(p.my_vote) + "§7)" : "";
                        player.sendMessage("§e#" + (i + 1) + " §f" + p.title + " §8[" + typeLabel(p.proposal_type) + "]");
                        player.sendMessage("   §aДа: " + p.yes + "  §cПротив: " + p.no + "  §4Вето: " + p.veto + myVote);
                        if (p.description != null && !p.description.isBlank()) {
                            player.sendMessage("   §7" + p.description);
                        }
                    }
                    player.sendMessage("§7Голосуйте: §e/alliance vote <№> <yes|no|veto> [комментарий]");
                    lastProposals.put(player.getUniqueId(), ids);
                });
            } catch (IOException | InterruptedException e) {
                sync(player, () -> player.sendMessage("§c[Альянс] §f" + extractDetail(e.getMessage())));
            }
        });
    }

    private void handleVote(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage("§cИспользование: /alliance vote <№> <yes|no|veto> [комментарий]");
            player.sendMessage("§7Сначала запустите §e/alliance proposals §7чтобы увидеть список.");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cНеверный номер. Запустите §e/alliance proposals§c, затем используйте номер из списка.");
            return;
        }
        List<String> ids = lastProposals.get(player.getUniqueId());
        if (ids == null || index < 1 || index > ids.size()) {
            player.sendMessage("§cПредложение №" + index + " не найдено. Сначала запустите §e/alliance proposals§c.");
            return;
        }
        String proposalId = ids.get(index - 1);
        String vote = args[2].toLowerCase();
        if (!vote.equals("yes") && !vote.equals("no") && !vote.equals("veto")) {
            player.sendMessage("§cДопустимые варианты: §eyes§c, §eno§c, §eveto§c.");
            return;
        }
        String comment = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : null;

        player.sendMessage("§7Отправляем голос §e" + voteLabel(vote) + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resp = plugin.getBackendClient().voteAllianceProposal(proposalId, new GameAllianceVoteRequest(player.getName(), vote, comment));
                String msg = resp.message != null ? resp.message : "Голос принят.";
                String statusSuffix = resp.status != null ? " Статус предложения: " + statusLabel(resp.status) + "." : "";
                sync(player, () -> player.sendMessage("§a[Альянс] §f" + msg + statusSuffix));
            } catch (IOException | InterruptedException e) {
                sync(player, () -> player.sendMessage("§c[Альянс] §f" + extractDetail(e.getMessage())));
            }
        });
    }

    private void handleKick(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cИспользование: /alliance kick <slug_государства>");
            return;
        }
        String targetSlug = args[1];
        player.sendMessage("§7Создаём предложение об исключении §e" + targetSlug + "§7...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var resp = plugin.getBackendClient().proposeAllianceKick(new GameAllianceKickRequest(player.getName(), targetSlug));
                String msg = resp.message != null ? resp.message : "Предложение создано.";
                sync(player, () -> player.sendMessage("§a[Альянс] §f" + msg));
            } catch (IOException | InterruptedException e) {
                sync(player, () -> player.sendMessage("§c[Альянс] §f" + extractDetail(e.getMessage())));
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendHelp(Player player) {
        player.sendMessage("§6§lАльянс — команды:");
        player.sendMessage("§e/alliance apply <slug> §7— подать заявку на вступление");
        player.sendMessage("§e/alliance leave §7— покинуть текущий альянс");
        player.sendMessage("§e/alliance proposals §7— открытые предложения на голосование");
        player.sendMessage("§e/alliance vote <№> <yes|no|veto> [комментарий] §7— проголосовать");
        player.sendMessage("§e/alliance kick <slug> §7— предложить исключить государство");
    }

    private void sync(Player player, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private String typeLabel(String type) {
        if (type == null) return "?";
        return switch (type) {
            case "add_member"       -> "Принятие";
            case "remove_member"    -> "Исключение";
            case "set_policy"       -> "Политика";
            case "treasury_transfer"-> "Перевод";
            default                 -> type;
        };
    }

    private String voteLabel(String vote) {
        if (vote == null) return "?";
        return switch (vote.toLowerCase()) {
            case "yes"  -> "За";
            case "no"   -> "Против";
            case "veto" -> "Вето";
            default     -> vote;
        };
    }

    private String statusLabel(String status) {
        if (status == null) return "?";
        return switch (status.toLowerCase()) {
            case "open"     -> "открыто";
            case "approved" -> "одобрено";
            case "rejected" -> "отклонено";
            case "executed" -> "исполнено";
            case "expired"  -> "истекло";
            default         -> status;
        };
    }

    private String extractDetail(String errorMessage) {
        if (errorMessage == null) return "Неизвестная ошибка.";
        int di = errorMessage.indexOf("\"detail\":");
        if (di >= 0) {
            String after = errorMessage.substring(di + 9).trim();
            if (after.startsWith("\"")) {
                int end = after.indexOf('"', 1);
                if (end > 0) return after.substring(1, end);
            }
        }
        int ci = errorMessage.lastIndexOf(": ");
        if (ci >= 0 && ci < errorMessage.length() - 2) {
            String tail = errorMessage.substring(ci + 2).trim();
            if (!tail.isEmpty() && !tail.startsWith("{")) return tail;
        }
        return "Ошибка сервера. Попробуйте снова.";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("apply", "leave", "proposals", "vote", "kick").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("vote")) {
            return List.of("yes", "no", "veto").stream()
                .filter(s -> s.startsWith(args[2].toLowerCase()))
                .toList();
        }
        return List.of();
    }
}
