package ru.voidrp.gamesync.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;

public final class WebGuiBridgeService {

    private static final String OPEN_WEB_CHANNEL  = "webgui:open_web";
    private static final String MAIN_MENU_CHANNEL = "webgui:set_main_menu";
    private static final int PROTOCOL_VERSION = 1;
    private static final int MODE_GUI = 0;
    private static final int MODE_HUD = 1;
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final VoidRpGameSyncPlugin plugin;
    private byte[] tokenSecret = null;
    private int tokenTtlSeconds = 900;
    private String queryParamName = "webgui_token";

    public WebGuiBridgeService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        registerChannel(OPEN_WEB_CHANNEL);
        registerChannel(MAIN_MENU_CHANNEL);
        loadWebviewConfig();
    }

    private void registerChannel(String channel) {
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, channel);
        } catch (Exception e) {
            // Channel already registered by NeoForge mod — not an error
        }
    }

    public void loadWebviewConfig() {
        File configFile = new File(plugin.getServer().getWorldContainer(), "config/webgui/server.json");
        if (!configFile.exists()) {
            plugin.getLogger().warning("webgui server.json not found at " + configFile.getAbsolutePath() + " — tokens disabled");
            tokenSecret = null;
            return;
        }
        try {
            String json = Files.readString(configFile.toPath());
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("tokenSecretBase64") && !obj.get("tokenSecretBase64").getAsString().isBlank()) {
                tokenSecret = Base64.getDecoder().decode(obj.get("tokenSecretBase64").getAsString().trim());
            }
            if (obj.has("tokenTtlSeconds")) {
                tokenTtlSeconds = Math.max(60, obj.get("tokenTtlSeconds").getAsInt());
            }
            if (obj.has("queryParamName") && !obj.get("queryParamName").getAsString().isBlank()) {
                queryParamName = obj.get("queryParamName").getAsString().trim();
            }
            plugin.getLogger().info("Loaded webgui token config (ttl=" + tokenTtlSeconds + "s)");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load webgui server.json: " + e.getMessage());
        }
    }

    public void openGui(Player player, String url) {
        if (!plugin.getGameSyncConfig().isWebGuiEnabled()) return;
        sendWebPacket(player, signUrl(player, url), MODE_GUI);
    }

    public void openHud(Player player, String url) {
        if (!plugin.getGameSyncConfig().isWebGuiEnabled()) return;
        sendWebPacket(player, signUrl(player, url), MODE_HUD);
    }

    public void sendMainMenuUrl(Player player, String url) {
        if (!plugin.getGameSyncConfig().isWebGuiEnabled()) return;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeString(buf, url == null ? "" : url);
            player.sendPluginMessage(plugin, MAIN_MENU_CHANNEL, buf.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send main menu URL: " + e.getMessage());
        }
    }

    private void sendWebPacket(Player player, String url, int mode) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            writeVarInt(buf, PROTOCOL_VERSION);
            writeVarInt(buf, mode);
            writeString(buf, url);
            player.sendPluginMessage(plugin, OPEN_WEB_CHANNEL, buf.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send WebGUI packet: " + e.getMessage());
        }
    }

    private String signUrl(Player player, String url) {
        if (tokenSecret == null || tokenSecret.length == 0) return url == null ? "" : url;
        long exp = Instant.now().getEpochSecond() + tokenTtlSeconds;
        String payload = "1|" + player.getName() + "|" + exp;
        byte[] sig = hmac(tokenSecret, payload.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String token = enc.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + enc.encodeToString(sig);
        String base = url == null ? "" : url;
        return base + (base.contains("?") ? "&" : "?") + queryParamName + "=" + token;
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) { out.write(value); return; }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    // Convenience methods
    public void openMarket(Player player)       { openGui(player, plugin.getGameSyncConfig().getWebGuiMarketUrl()); }
    public void openNationMarket(Player player) { openGui(player, plugin.getGameSyncConfig().getWebGuiNationMarketUrl()); }
    public void openTreasury(Player player)     { openGui(player, plugin.getGameSyncConfig().getWebGuiTreasuryUrl()); }
    public void openAlliance(Player player)     { openGui(player, plugin.getGameSyncConfig().getWebGuiAllianceUrl()); }
    public void openBattlepass(Player player)   { openGui(player, plugin.getGameSyncConfig().getWebGuiBattlepassUrl()); }
    public void openQuests(Player player)       { openGui(player, plugin.getGameSyncConfig().getWebGuiQuestsUrl()); }
}
