package ru.voidrp.gamesync.listener;

import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.NationDefinition;

/**
 * Grants citizens of nations that researched "Лесопилки и шахты"
 * ({@code gather_bonus_percent}) a chance at extra ore/wood drops.
 */
public final class GatherBonusListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public GatherBonusListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isDropItems()) return; // silk-touch / no-drop breaks
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        if (!isGatherable(block.getType())) return;

        NationDefinition nation = plugin.getNationRegistry().findByPlayer(event.getPlayer().getName());
        if (nation == null) return;

        double bonus = plugin.getNationResearchEffectService().getEffect(nation.slug(), "gather_bonus_percent");
        if (bonus <= 0) return;
        if (ThreadLocalRandom.current().nextDouble() * 100.0 >= bonus) return;

        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        for (ItemStack drop : block.getDrops(tool, event.getPlayer())) {
            if (drop != null && drop.getType() != Material.AIR) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop.clone());
            }
        }
    }

    private boolean isGatherable(Material type) {
        if (type == Material.ANCIENT_DEBRIS) return true;
        if (Tag.LOGS.isTagged(type)) return true;
        return type.name().endsWith("_ORE");
    }
}
