package ru.voidrp.gamesync.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;

public final class AlliancePvpListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public AlliancePvpListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        NationDefinition victimNation = plugin.getNationRegistry().findByPlayer(victim.getName());
        NationDefinition attackerNation = plugin.getNationRegistry().findByPlayer(attacker.getName());
        if (victimNation == null || attackerNation == null) return;
        if (victimNation.slug().equals(attackerNation.slug())) return;

        if (plugin.getAllianceCacheService().isSameAllianceWithPvpProtection(attackerNation.slug(), victimNation.slug())) {
            event.setCancelled(true);
            attacker.sendMessage("§c[Альянс] §7Нельзя атаковать союзников.");
        }
    }
}
