package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Contextual gameplay tips for new players, delivered as HUD notifications (type {@code tip},
 * which players can mute in WebGUI settings) plus one chat line for clients without WebGUI.
 *
 * <p>Every tip fires once per player, at most one per {@code tips.cooldown-minutes}, and only
 * while the player's total playtime is under {@code tips.max-playtime-hours} — veterans never
 * get a burst of beginner advice the day this ships. Texts can be overridden in config.yml
 * under {@code tips.texts.<key>.title/body}.
 */
public final class TipService implements Listener {

    /** A tip: default texts, where its button leads (WebGUI page key or null), icon/accent. */
    private record Tip(String key, String title, String body, String page, String label, String icon, String accent) { }

    private static final Map<String, Tip> TIPS = new LinkedHashMap<>();

    private static void tip(String key, String title, String body, String page, String label, String icon, String accent) {
        TIPS.put(key, new Tip(key, title, body, page, label, icon, accent));
    }

    static {
        tip("menu", "Меню сервера — F6",
                "Рынок, нации, квесты, батл пасс и настройки собраны в меню на F6.", "menu", "Открыть меню", "grid", "#8b7bff");
        tip("battlepass", "Батл пасс уже качается",
                "Добыча, крафт и боссы дают опыт пасса. Награды за уровни забирай в /bp.", "battlepass", "Забрать награды", "battlepass", "#f59e0b");
        tip("night", "Наступает ночь",
                "В темноте мобы появляются прямо рядом. Поставь факелы вокруг базы и не уходи далеко без света.", null, null, "flame", "#fb923c");
        tip("caves", "Глубоко и темно",
                "В пещерах без света ничего не видно. Держи факелы под рукой и отмечай дорогу назад.", null, null, "pickaxe", "#94a3b8");
        tip("team", "Играешь с друзьями?",
                "Создай команду: /ftbteams party create название, затем /ftbteams party invite ник. Приват будет общий.", "welcome", "Как это сделать", "users", "#34d399");
        tip("claim", "Защити свою базу",
                "Открой карту на M и выдели чанки левой кнопкой мыши — чужие не смогут ломать блоки и открывать сундуки.", "welcome", "Подробнее", "shield", "#60a5fa");
        tip("death", "Ты погиб",
                "Вещи ищи на месте смерти. Сделай /sethome у базы, чтобы быстро возвращаться командой /home.", null, null, "skull", "#f87171");
        tip("quests", "Ежедневные квесты",
                "Каждый день новые задания: деньги и много опыта батл пасса.", "quests", "Открыть квесты", "quest", "#a78bfa");
        tip("market", "Есть деньги — есть возможности",
                "На рынке можно купить нужное и продать лишние ресурсы другим игрокам.", "market", "Открыть рынок", "market", "#fbbf24");
        tip("roadmap", "Не знаешь, что делать дальше?",
                "Путеводитель показывает, на каком ты этапе и какой предмет получить следующим — от первых инструментов до финала. Команда /путь.", "roadmap", "Открыть путеводитель", "map", "#34d399");
        tip("epoch", "Открыта новая эпоха",
                "Эпохи показывают твой прогресс в топах сервера и открывают следующие зоны батл пасса. Следующая цель — в путеводителе.", "roadmap", "Что дальше", "trophy", "#f59e0b");
        tip("nation", "Вступи в нацию",
                "У наций общая казна, технологии и союзы. Найди нацию на сайте или создай свою.", "nmarket", "Нации", "globe", "#22d3ee");
    }

    private static final Pattern CHUNK_ENTRY = Pattern.compile("\\{\\s*x:\\s*-?\\d+\\s*,\\s*z:\\s*-?\\d+");
    private static final Pattern RANK_MEMBER = Pattern.compile("([0-9a-fA-F-]{36}):\\s*\"\\w+\"");
    private static final Pattern TEAM_ID = Pattern.compile("id:\\s*\"([0-9a-fA-F-]{36})\"");

    private final VoidRpGameSyncPlugin plugin;
    private final Map<UUID, Long> lastTipAt = new ConcurrentHashMap<>();
    // FTB file scans are cheap but not free: remember "checked, not yet" for a few minutes.
    private final Map<String, Long> ftbCheckedAt = new ConcurrentHashMap<>();

    public TipService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("tips.enabled", true);
    }

    public void start() {
        if (!enabled()) {
            plugin.getLogger().info("[Tips] disabled in config");
            return;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 20L * 60, 20L * 30);
        plugin.getLogger().info("[Tips] " + TIPS.size() + " tips active");
    }

    // ── triggers ─────────────────────────────────────────────────────────────

    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                check(p);
            } catch (Exception e) {
                plugin.getLogger().warning("[Tips] check failed for " + p.getName() + ": " + e.getMessage());
            }
        }
    }

    private void check(Player p) {
        UUID id = p.getUniqueId();
        if (!eligible(id)) return;
        long minutes = playtimeMinutes(id);
        World w = p.getWorld();
        boolean overworld = w.getEnvironment() == World.Environment.NORMAL;

        if (minutes >= 5 && give(p, "menu")) return;
        if (minutes >= 10 && give(p, "battlepass")) return;
        if (minutes >= 15 && give(p, "roadmap")) return;
        if (overworld && isNight(w) && minutes >= 3 && give(p, "night")) return;
        if (overworld && p.getLocation().getY() < 30 && give(p, "caves")) return;
        if (minutes >= 20 && !shown(id, "team") && !inParty(id) && give(p, "team")) return;
        if (minutes >= 35 && !shown(id, "claim") && !hasClaims(id) && give(p, "claim")) return;
        if (minutes >= 60 && give(p, "quests")) return;
        if (!shown(id, "market") && balance(p) >= plugin.getConfig().getDouble("tips.market-balance", 5000) && give(p, "market")) return;
        if (minutes >= 120 && !shown(id, "nation") && plugin.getNationRegistry() != null
                && plugin.getNationRegistry().findByPlayer(p.getName()) == null) {
            give(p, "nation");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled()) return;
        Player p = event.getEntity();
        // After respawn, so the toast isn't hidden behind the death screen.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && eligible(p.getUniqueId())) give(p, "death", true);
        }, 20L * 8);
    }

    /** Called by EpochService on a player's first epoch unlock. */
    public void onEpochUnlocked(Player p) {
        if (!enabled() || !eligible(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) give(p, "epoch", true);
        }, 40L);
    }

    // ── delivery ─────────────────────────────────────────────────────────────

    private boolean give(Player p, String key) {
        return give(p, key, false);
    }

    /** Sends the tip once. {@code event=true} skips the cooldown (death/epoch are the moment). */
    private boolean give(Player p, String key, boolean event) {
        UUID id = p.getUniqueId();
        Tip tip = TIPS.get(key);
        if (tip == null || shown(id, key)) return false;
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("tips.cooldown-minutes", 10) * 60_000L;
        if (!event && now - lastTipAt.getOrDefault(id, 0L) < cooldown) return false;

        String title = plugin.getConfig().getString("tips.texts." + key + ".title", tip.title());
        String body = plugin.getConfig().getString("tips.texts." + key + ".body", tip.body());

        plugin.getDataStore().setTipShown(id, key);
        plugin.getDataStore().saveNow();
        lastTipAt.put(id, now);

        p.sendMessage("§d§l💡 §f" + title + " §7— " + body);

        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("minecraft_nickname", p.getName());
        payload.put("type", "tip");
        payload.put("title", title);
        payload.put("body", body);
        payload.put("icon", tip.icon());
        payload.put("accent", tip.accent());
        if (tip.page() != null) {
            payload.put("action_type", "route");
            payload.put("action_payload", tip.page());
            payload.put("action_label", tip.label());
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().pushNotification(payload);
            } catch (IOException | InterruptedException e) {
                // the chat line already reached the player
            }
        });
        plugin.getLogger().info("[Tips] " + key + " → " + p.getName());
        return true;
    }

    // ── conditions ───────────────────────────────────────────────────────────

    private boolean shown(UUID id, String key) {
        return plugin.getDataStore().isTipShown(id, key);
    }

    private boolean eligible(UUID id) {
        long maxHours = plugin.getConfig().getLong("tips.max-playtime-hours", 6);
        return playtimeMinutes(id) < maxHours * 60;
    }

    private long playtimeMinutes(UUID id) {
        return plugin.getDataStore().getStatCounter(id, "playtime_seconds") / 60;
    }

    private static boolean isNight(World w) {
        long t = w.getTime() % 24000;
        return t >= 13000 && t <= 23000;
    }

    private double balance(Player p) {
        try {
            return plugin.getEconomy() != null ? plugin.getEconomy().getBalance(p) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Path worldDir() {
        return Bukkit.getWorlds().get(0).getWorldFolder().toPath();
    }

    /** Party team id the player belongs to, or null. From <world>/ftbteams/party/*.snbt. */
    private String partyTeamOf(UUID id) {
        Path dir = worldDir().resolve("ftbteams").resolve("party");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".snbt"))::iterator) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                var m = RANK_MEMBER.matcher(text);
                while (m.find()) {
                    if (m.group(1).equalsIgnoreCase(id.toString())) {
                        var t = TEAM_ID.matcher(text);
                        return t.find() ? t.group(1) : f.getFileName().toString().replace(".snbt", "");
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * Unknown (I/O error, FTB not installed) counts as "yes" — better a missed tip than
     * telling someone who already has a team to make one.
     */
    private boolean inParty(UUID id) {
        if (recentlyChecked("party:" + id)) return true;
        Path dir = worldDir().resolve("ftbteams").resolve("party");
        if (!Files.isDirectory(dir)) return true;
        return partyTeamOf(id) != null;
    }

    private boolean hasClaims(UUID id) {
        if (recentlyChecked("claims:" + id)) return true;
        Path dir = worldDir().resolve("ftbchunks");
        if (!Files.isDirectory(dir)) return true;
        String party = partyTeamOf(id);
        for (String team : party != null ? List.of(party, id.toString()) : List.of(id.toString())) {
            Path f = dir.resolve(team + ".snbt");
            try {
                if (Files.exists(f) && CHUNK_ENTRY.matcher(Files.readString(f, StandardCharsets.UTF_8)).find()) return true;
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    /** True if this check ran in the last 5 minutes (and records this run otherwise). */
    private boolean recentlyChecked(String key) {
        long now = System.currentTimeMillis();
        Long last = ftbCheckedAt.get(key);
        if (last != null && now - last < 5 * 60_000L) return true;
        ftbCheckedAt.put(key, now);
        return false;
    }
}
