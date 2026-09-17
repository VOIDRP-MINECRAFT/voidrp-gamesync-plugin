package ru.voidrp.gamesync.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.google.gson.JsonObject;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.service.trader.TraderNpc;
import ru.voidrp.gamesync.service.trader.VanillaTraderNpc;

/**
 * The travelling trader at spawn.
 *
 * <p>The backend decides when the trader is here ({@code /game-sync/trader/tick}, polled every few
 * seconds). While a visit is active the NPC stands at the configured point; right-clicking it opens a
 * trade session on the backend and then the WebGUI trade page. The page cannot be opened any other
 * way, so players have to come to spawn. Trades are queued web actions ({@code trader_trade}) handled
 * in {@link WebActionPollService}, which re-checks that the player still stands next to the trader.
 */
public final class TraderService {

    private static final long TICK_PERIOD = 20L * 5;

    private final VoidRpGameSyncPlugin plugin;
    private final Map<UUID, Long> clickCooldown = new ConcurrentHashMap<>();
    private TraderNpc npc;

    private volatile String activeVisitId;
    private volatile String activeKind = "normal";
    private volatile Instant endsAt;
    private volatile Location spawn;
    private volatile double interactRadius = 8.0;
    private String spawnedVisitId;

    public TraderService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            npc = createNpc();
            npc.despawn();   // leftovers of the previous run
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 15, TICK_PERIOD);
        Bukkit.getScheduler().runTaskTimer(plugin, this::particles, 20L * 20, 20L);
    }

    public void stop() {
        if (npc != null) npc.despawn();
    }

    private TraderNpc createNpc() {
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            try {
                return new ru.voidrp.gamesync.service.trader.CitizensTraderNpc(plugin, this::openTrade);
            } catch (Throwable t) {
                plugin.getLogger().warning("[Trader] Citizens NPC unavailable, using a wandering trader: " + t);
            }
        }
        return new VanillaTraderNpc(plugin, this::openTrade);
    }

    // ── polling ────────────────────────────────────────────────────────────
    private void tick() {
        List<String> online = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject state;
            try {
                state = plugin.getBackendClient().traderTick(online);
            } catch (Exception e) {
                return;   // backend unreachable: keep the current state, retry next tick
            }
            Bukkit.getScheduler().runTask(plugin, () -> apply(state));
        });
    }

    private void apply(JsonObject state) {
        if (npc == null) return;
        if (state.has("spawn") && state.get("spawn").isJsonObject()) {
            JsonObject s = state.getAsJsonObject("spawn");
            World world = Bukkit.getWorld(s.get("world").getAsString());
            if (world != null) {
                Location loc = new Location(world, s.get("x").getAsDouble(), s.get("y").getAsDouble(), s.get("z").getAsDouble());
                loc.setYaw((float) s.get("yaw").getAsDouble());
                spawn = loc;
            }
        }
        if (state.has("interact_radius")) interactRadius = state.get("interact_radius").getAsDouble();

        boolean enabled = state.has("enabled") && state.get("enabled").getAsBoolean();
        JsonObject active = enabled && state.has("active") && state.get("active").isJsonObject()
            ? state.getAsJsonObject("active") : null;
        if (active == null) {
            if (spawnedVisitId != null) npc.despawn();
            spawnedVisitId = null;
            activeVisitId = null;
            endsAt = null;
            return;
        }
        activeVisitId = active.get("visit_id").getAsString();
        activeKind = active.get("kind").getAsString();
        try {
            endsAt = OffsetDateTime.parse(active.get("ends_at").getAsString()).toInstant();
        } catch (Exception ignored) {
            endsAt = null;
        }

        Location loc = spawn;
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        Entity body = npc.entity();
        if (body == null || !activeVisitId.equals(spawnedVisitId)) {
            npc.spawn(loc, activeKind);
            spawnedVisitId = activeVisitId;
        } else if (body.getWorld() != loc.getWorld() || body.getLocation().distanceSquared(loc) > 1.0) {
            body.teleport(loc);
        }
        npc.setStatusLine(statusLine());
    }

    private String statusLine() {
        Instant end = endsAt;
        if (end == null) return "§e…";
        long minutes = Math.max(0, Duration.between(Instant.now(), end).toMinutes());
        return minutes < 1 ? "§cУходит прямо сейчас" : "§eУйдёт через " + minutes + " мин";
    }

    private void particles() {
        Entity body = npc == null ? null : npc.entity();
        if (body == null) return;
        Location at = body.getLocation().add(0, 1.1, 0);
        boolean anyoneNear = false;
        for (Player p : body.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) < 32 * 32) { anyoneNear = true; break; }
        }
        if (!anyoneNear) return;
        if ("elite".equals(activeKind)) {
            body.getWorld().spawnParticle(Particle.PORTAL, at, 24, 0.4, 0.6, 0.4, 0.4);
            body.getWorld().spawnParticle(Particle.WITCH, at.clone().add(0, 0.9, 0), 4, 0.3, 0.2, 0.3, 0.0);
        } else {
            body.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at, 4, 0.5, 0.7, 0.5, 0.0);
            if ("weekend".equals(activeKind)) {
                body.getWorld().spawnParticle(Particle.WAX_ON, at.clone().add(0, 0.8, 0), 3, 0.4, 0.3, 0.4, 0.0);
            }
        }
    }

    // ── trading ────────────────────────────────────────────────────────────
    /** True when the player stands within the interaction radius of the trader point. */
    public boolean isNearTrader(Player player) {
        Location loc = spawn;
        if (loc == null || activeVisitId == null || player.getWorld() != loc.getWorld()) return false;
        return player.getLocation().distanceSquared(loc) <= interactRadius * interactRadius;
    }

    public double getInteractRadius() {
        return interactRadius;
    }

    private void openTrade(Player player) {
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(player.getUniqueId());
        if (last != null && now - last < 1500) return;
        clickCooldown.put(player.getUniqueId(), now);

        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject resp;
            try {
                resp = plugin.getBackendClient().traderOpen(name);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p != null) p.sendMessage("§cСкупщик сейчас не отвечает, попробуйте через минуту.");
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayerExact(name);
                if (p == null || !p.isOnline()) return;
                if (resp.has("ok") && resp.get("ok").getAsBoolean()) {
                    plugin.getWebGuiBridgeService().openGui(p, plugin.getGameSyncConfig().getWebGuiTraderUrl());
                } else {
                    String msg = resp.has("message") ? resp.get("message").getAsString() : "Скупщика сейчас нет.";
                    p.sendMessage("§e" + msg);
                }
            });
        });
    }
}
