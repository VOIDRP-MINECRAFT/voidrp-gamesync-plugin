package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.model.NationResearchEffects;
import ru.voidrp.gamesync.model.NationResearchEffectsResponse;
import ru.voidrp.gamesync.model.NationResearchInterestResponse;
import ru.voidrp.gamesync.model.NationSeasonAwardResponse;
import ru.voidrp.gamesync.model.NationSeasonWinner;

/**
 * Periodically pulls resolved nation-research effects from the backend and
 * applies the in-world ones: Haste for citizens near their capital
 * ({@code capital_haste_level}). {@code gather_bonus_percent} is exposed via
 * {@link #getEffect} for {@code GatherBonusListener} to consume.
 */
public final class NationResearchEffectService {

    private static final double CAPITAL_HASTE_RADIUS = 48.0;
    private static final int HASTE_DURATION_TICKS = 140; // 7s, longer than the 5s apply cycle

    private final VoidRpGameSyncPlugin plugin;
    private final ScheduledExecutorService fetcher;
    private BukkitTask applyTask;

    // nation_slug (lower) → (effect_key → value)
    private volatile Map<String, Map<String, Double>> effectsBySlug = Collections.emptyMap();

    public NationResearchEffectService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        this.fetcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nation-research-effects");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        fetcher.scheduleAtFixedRate(this::refresh, 5, 60, TimeUnit.SECONDS);
        // Backend is idempotent per 7-day period, so a frequent tick is safe:
        // only nations that are actually due get paid / awarded.
        fetcher.scheduleAtFixedRate(this::applyInterest, 2, 180, TimeUnit.MINUTES);
        // Season rewards pay real prizes + broadcast, so they are opt-in (default off).
        if (plugin.getGameSyncConfig().isSeasonAutoRewardsEnabled()) {
            fetcher.scheduleAtFixedRate(this::awardSeasonTop, 4, 180, TimeUnit.MINUTES);
        }
        applyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::applyCapitalHaste, 100L, 100L);
    }

    public void stop() {
        fetcher.shutdownNow();
        if (applyTask != null) applyTask.cancel();
    }

    /** Resolved effect value for a nation (0 when absent). */
    public double getEffect(String nationSlug, String effectKey) {
        if (nationSlug == null) return 0.0;
        Map<String, Double> effects = effectsBySlug.get(nationSlug.toLowerCase(java.util.Locale.ROOT));
        if (effects == null) return 0.0;
        Double value = effects.get(effectKey);
        return value != null ? value : 0.0;
    }

    private void refresh() {
        try {
            NationResearchEffectsResponse response = plugin.getBackendClient().fetchNationResearchEffects();
            Map<String, Map<String, Double>> updated = new HashMap<>();
            if (response != null && response.nations != null) {
                for (NationResearchEffects entry : response.nations) {
                    if (entry.nation_slug == null || entry.effects == null) continue;
                    updated.put(entry.nation_slug.toLowerCase(java.util.Locale.ROOT), entry.effects);
                }
            }
            effectsBySlug = updated;
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[NationResearch] effects refresh failed: " + e.getMessage());
        }
    }

    private void applyInterest() {
        try {
            NationResearchInterestResponse res = plugin.getBackendClient().applyNationResearchInterest();
            if (res != null && res.paid_nations > 0) {
                plugin.getLogger().info("[NationResearch] Центробанк начислил проценты "
                        + res.paid_nations + " государствам (итого " + res.total_paid + ").");
            }
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[NationResearch] interest tick failed: " + e.getMessage());
        }
    }

    private void awardSeasonTop() {
        try {
            NationSeasonAwardResponse res = plugin.getBackendClient().awardTopNations();
            if (res == null || res.awarded == null || res.awarded.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.broadcastMessage("§6§l✦ Итоги сезона государств ✦");
                for (NationSeasonWinner w : res.awarded) {
                    String medal = switch (w.rank) {
                        case 1 -> "§e🥇";
                        case 2 -> "§7🥈";
                        case 3 -> "§c🥉";
                        default -> "§f#" + w.rank;
                    };
                    Bukkit.broadcastMessage(medal + " §f" + w.nation_title
                            + " §7— §a+" + String.format(java.util.Locale.US, "%,.0f", w.prize) + " §7в казну");
                }
            });
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("[NationSeason] season reward tick failed: " + e.getMessage());
        }
    }

    private void applyCapitalHaste() {
        if (effectsBySlug.isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            NationDefinition nation = plugin.getNationRegistry().findByPlayer(player.getName());
            if (nation == null) continue;

            int hasteLevel = (int) Math.round(getEffect(nation.slug(), "capital_haste_level"));
            if (hasteLevel <= 0) continue;

            Integer cx = nation.capitalX();
            Integer cz = nation.capitalZ();
            String world = nation.capitalWorld();
            if (cx == null || cz == null || world == null) continue;
            if (!player.getWorld().getName().equals(world)) continue;

            double dx = player.getLocation().getX() - (cx + 0.5);
            double dz = player.getLocation().getZ() - (cz + 0.5);
            if (dx * dx + dz * dz > CAPITAL_HASTE_RADIUS * CAPITAL_HASTE_RADIUS) continue;

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.HASTE, HASTE_DURATION_TICKS, hasteLevel - 1, true, false, true));
        }
    }
}
