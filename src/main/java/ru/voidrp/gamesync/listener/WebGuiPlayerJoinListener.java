package ru.voidrp.gamesync.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

public final class WebGuiPlayerJoinListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public WebGuiPlayerJoinListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getGameSyncConfig().isWebGuiEnabled()) return;
        var player = event.getPlayer();
        // Delay 3 seconds (60 ticks) so the client has time to fully load and
        // NeoForge mod channel registration completes before we send the packet.
        // If the NeoForge mod already sent mainMenuUrl via PlayerLoggedInEvent,
        // the client simply receives the same URL twice — harmless.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Cache-bust the menu URL: it carries no token (the menu page itself calls no API),
            // so CEF would cache it and keep serving a stale build. A per-join version param
            // forces a fresh load each session so menu buttons always run the latest code.
            String menuUrl = plugin.getGameSyncConfig().getWebGuiMenuUrl();
            menuUrl += (menuUrl.contains("?") ? "&" : "?") + "v=" + (System.currentTimeMillis() / 1000L);
            plugin.getWebGuiBridgeService().sendMainMenuUrl(player, menuUrl);
            // Open the HUD overlay from the plugin (not the WebGUI mod's autoHudOnJoin):
            // only the plugin signs the URL with a token, so a plugin-driven HUD avoids
            // the "session not confirmed" error a tokenless mod-driven HUD would cause.
            if (plugin.getGameSyncConfig().isWebGuiAutoHudOnJoin()) {
                // Cache-bust like the menu: CEF caches the HUD page, so a per-join version
                // param forces a fresh load and always shows the latest build.
                String hudUrl = plugin.getGameSyncConfig().getWebGuiHudUrl();
                hudUrl += (hudUrl.contains("?") ? "&" : "?") + "v=" + (System.currentTimeMillis() / 1000L);
                plugin.getWebGuiBridgeService().openHud(player, hudUrl);
            }
        }, 60L);
    }
}
