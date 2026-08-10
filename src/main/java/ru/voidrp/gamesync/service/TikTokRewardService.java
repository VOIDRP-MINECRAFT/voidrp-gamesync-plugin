package ru.voidrp.gamesync.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.TikTokCampaignResponse;
import ru.voidrp.gamesync.model.TikTokPendingRewardsResponse;
import ru.voidrp.gamesync.model.TikTokPendingRewardsResponse.TikTokPendingReward;

/**
 * TikTok click-reward system.
 *
 * <ul>
 *   <li>{@link #announce(String, CommandSender)} — creates a backend campaign and
 *       broadcasts a clickable, per-player link to everyone online.</li>
 *   <li>The scheduled poll pulls pending rewards (players who opened their link)
 *       and hands each a uniformly-random reward from config, then acks it.</li>
 * </ul>
 */
public final class TikTokRewardService {

    private final VoidRpGameSyncPlugin plugin;
    private final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public TikTokRewardService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────────

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("tiktok.enabled", true)) {
            return;
        }
        cachedPool = loadPool(); // (re)load reward pool from file/config each (re)start
        long period = Math.max(20L, plugin.getConfig().getLong("tiktok.poll-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 80L, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        inFlight.clear();
    }

    // ── announce (/vrgs tiktok <url>) ─────────────────────────────────────────────

    public void announce(String videoUrl, CommandSender feedback) {
        if (videoUrl == null || !(videoUrl.startsWith("http://") || videoUrl.startsWith("https://"))) {
            feedback.sendMessage("§cУкажите корректную ссылку: /vrgs tiktok <https://...>");
            return;
        }
        feedback.sendMessage("§7Создаём кампанию и рассылаем анонс...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                TikTokCampaignResponse campaign = plugin.getBackendClient().createTikTokCampaign(videoUrl);
                if (campaign == null || campaign.click_base == null || campaign.campaign_id == null) {
                    Bukkit.getScheduler().runTask(plugin, () -> feedback.sendMessage("§cBackend вернул пустой ответ."));
                    return;
                }
                String secret = plugin.getGameSyncConfig().getGameAuthSecret();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int sent = 0;
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        broadcastTo(player, campaign, secret);
                        sent++;
                    }
                    feedback.sendMessage("§aАнонс разослан §f" + sent + " §aигрокам. Campaign: §7" + campaign.campaign_id);
                    feedback.sendMessage("§7Игроки получат случайную награду за переход по своей ссылке.");
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        feedback.sendMessage("§cНе удалось создать кампанию: §f" + ex.getMessage()));
            }
        });
    }

    /** Builds the per-player message with a personal, signed tracking link. */
    private void broadcastTo(Player player, TikTokCampaignResponse campaign, String secret) {
        String uuid = player.getUniqueId().toString();
        String sig = sign(secret, campaign.campaign_id, uuid);
        String url = campaign.click_base + "/" + uuid + "/" + sig + "?n=" + urlEncode(player.getName());

        Component button = Component.text("[ ▶ Смотреть и забрать награду ]")
                .color(NamedTextColor.LIGHT_PURPLE)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Перейди по ссылке — и получишь случайную награду на сервере!")
                                .color(NamedTextColor.GRAY)));

        Component message = Component.text("")
                .append(Component.text("\n🎬 ").color(NamedTextColor.WHITE))
                .append(Component.text("Вышел новый ролик VoidRP в TikTok!").color(NamedTextColor.AQUA))
                .append(Component.newline())
                .append(Component.text("   ").append(button))
                .append(Component.newline())
                .append(Component.text("🎁 За переход — ").color(NamedTextColor.GRAY))
                .append(Component.text("случайная награда").color(NamedTextColor.GOLD))
                .append(Component.text("! Лайк и репост помогают серверу расти.").color(NamedTextColor.GRAY))
                .append(Component.newline());

        player.sendMessage(message);
    }

    // ── reward delivery poll ─────────────────────────────────────────────────────

    private void poll() {
        try {
            TikTokPendingRewardsResponse resp = plugin.getBackendClient().pollTikTokRewards();
            if (resp == null || resp.rewards == null || resp.rewards.isEmpty()) {
                return;
            }
            for (TikTokPendingReward reward : resp.rewards) {
                if (reward == null || reward.reward_id == null || reward.minecraft_uuid == null) {
                    continue;
                }
                if (!inFlight.add(reward.reward_id)) {
                    continue; // already being processed
                }
                deliver(reward);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[TikTok] poll failed: " + ex.getMessage());
        }
    }

    private void deliver(TikTokPendingReward reward) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player;
            try {
                player = Bukkit.getPlayer(UUID.fromString(reward.minecraft_uuid));
            } catch (IllegalArgumentException ex) {
                player = null;
            }
            if (player == null || !player.isOnline()) {
                // Player offline — leave the reward pending; retry when they return.
                inFlight.remove(reward.reward_id);
                return;
            }

            Reward roll = rollReward();
            if (roll == null) {
                inFlight.remove(reward.reward_id);
                plugin.getLogger().warning("[TikTok] no rewards configured — skipping delivery");
                return;
            }

            String give = "minecraft:give " + player.getName() + " " + roll.item + " " + roll.amount;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), give);
            player.sendMessage("§a🎁 Спасибо за переход в TikTok! Награда: §6" + roll.displayName + " §7×" + roll.amount);

            // Ack off the main thread so we don't block the tick.
            final String id = reward.reward_id;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getBackendClient().ackTikTokRewards(List.of(id));
                } catch (Exception ex) {
                    plugin.getLogger().warning("[TikTok] ack failed for " + id + ": " + ex.getMessage());
                } finally {
                    // Keep in inFlight on success (delivered server-side); on failure
                    // remove so a later poll retries.
                    inFlight.remove(id);
                }
            });
        });
    }

    // ── reward pool ──────────────────────────────────────────────────────────────

    /** Rolled result handed to the player. */
    private record Reward(String item, int amount, String displayName) {}

    /** Pool entry (definition) — amount is rolled per delivery. */
    private record RewardDef(String item, int min, int max, String name) {}

    private volatile List<RewardDef> cachedPool;

    /** Uniformly-random reward (equal chance per entry) + random amount in [min,max]. */
    private Reward rollReward() {
        List<RewardDef> pool = cachedPool;
        if (pool == null) {
            pool = loadPool();
            cachedPool = pool;
        }
        if (pool.isEmpty()) {
            return null;
        }
        RewardDef def = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        int amount = def.min >= def.max ? def.min : ThreadLocalRandom.current().nextInt(def.min, def.max + 1);
        return new Reward(def.item, Math.max(1, amount), def.name);
    }

    /**
     * Loads the reward pool once and caches it. Sources, combined:
     *   1) external file <dataFolder>/tiktok_rewards.yml (big mod-item pool)
     *   2) inline config.yml tiktok.rewards
     *   3) built-in safe defaults (only if 1 and 2 are empty)
     */
    private List<RewardDef> loadPool() {
        List<RewardDef> pool = new ArrayList<>();

        java.io.File external = new java.io.File(plugin.getDataFolder(), "tiktok_rewards.yml");
        if (external.exists()) {
            org.bukkit.configuration.file.YamlConfiguration yml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(external);
            addDefs(yml.getMapList("rewards"), pool);
        }

        addDefs(plugin.getConfig().getMapList("tiktok.rewards"), pool);

        if (pool.isEmpty()) {
            pool.add(new RewardDef("minecraft:diamond", 1, 4, "Алмазы"));
            pool.add(new RewardDef("minecraft:golden_apple", 1, 3, "Золотые яблоки"));
            pool.add(new RewardDef("minecraft:iron_ingot", 4, 16, "Железо"));
            pool.add(new RewardDef("minecraft:experience_bottle", 4, 16, "Бутыли опыта"));
            pool.add(new RewardDef("minecraft:emerald", 1, 6, "Изумруды"));
        }
        plugin.getLogger().info("[TikTok] reward pool loaded: " + pool.size() + " entries");
        return pool;
    }

    private void addDefs(List<Map<?, ?>> raw, List<RewardDef> pool) {
        if (raw == null) {
            return;
        }
        for (Map<?, ?> entry : raw) {
            Object item = entry.get("item");
            if (item == null) {
                continue;
            }
            int min = toInt(entry.get("min"), 1);
            int max = toInt(entry.get("max"), min);
            if (max < min) {
                max = min;
            }
            Object name = entry.get("name");
            String displayName = name != null ? name.toString() : item.toString();
            pool.add(new RewardDef(item.toString(), Math.max(1, min), Math.max(1, max), displayName));
        }
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** HMAC-SHA256(secret, "campaignId:uuid") hex, first 16 chars. Matches backend. */
    private String sign(String secret, String campaignId, String uuid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((campaignId + ":" + uuid).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
