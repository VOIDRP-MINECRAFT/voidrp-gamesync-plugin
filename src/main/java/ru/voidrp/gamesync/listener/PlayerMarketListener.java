package ru.voidrp.gamesync.listener;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.command.PlayerMarketCommand;
import ru.voidrp.gamesync.service.PlayerMarketService.PendingAction;
import ru.voidrp.gamesync.service.PlayerMarketService.PendingActionType;

public final class PlayerMarketListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;
    private final PlayerMarketCommand command;

    public PlayerMarketListener(VoidRpGameSyncPlugin plugin, PlayerMarketCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getGameSyncConfig().isPlayerMarketEnabled()) {
            plugin.getPlayerMarketService().handlePlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PendingAction action = plugin.getPlayerMarketService().peekPendingAction(uuid);
        if (action == null) return;

        String message = event.getMessage().trim();
        Player player = event.getPlayer();

        // ── GUI flows: intercept any chat message ──────────────────────────
        if (action.type == PendingActionType.GUI_SELL) {
            event.setCancelled(true);
            plugin.getPlayerMarketService().clearPendingAction(uuid);
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getPlayerMarketService().handleGuiSellInput(player, action, message));
            return;
        }

        if (action.type == PendingActionType.GUI_BUY) {
            event.setCancelled(true);
            // clearPendingAction is called inside handleGuiBuyInput
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.getPlayerMarketService().handleGuiBuyInput(player, message));
            return;
        }

        // ── Normal confirm flow (SELL/BUY): only trigger on explicit confirm ──
        if (!message.equalsIgnoreCase("да") && !message.equalsIgnoreCase("yes")
                && !message.equalsIgnoreCase("confirm")) {
            return;
        }

        event.setCancelled(true);
        PendingAction confirmed = plugin.getPlayerMarketService().consumePendingAction(uuid);
        if (confirmed == null) {
            player.sendMessage("§cПодтверждение истекло.");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> command.executePendingAction(player, confirmed));
    }
}
