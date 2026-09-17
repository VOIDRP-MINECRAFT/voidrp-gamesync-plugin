package ru.voidrp.gamesync.service.trader;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Fallback body when Citizens is not installed: a wandering trader without AI and trades. */
public final class VanillaTraderNpc implements TraderNpc, Listener {

    private final NamespacedKey tagKey;
    private final Consumer<Player> onRightClick;
    private UUID id;

    public VanillaTraderNpc(Plugin plugin, Consumer<Player> onRightClick) {
        this.tagKey = new NamespacedKey(plugin, "trader_npc");
        this.onRightClick = onRightClick;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void spawn(Location location, String kind) {
        despawn();
        WanderingTrader npc = location.getWorld().spawn(location, WanderingTrader.class, t -> {
            t.setAI(false);
            t.setInvulnerable(true);
            t.setSilent(true);
            t.setGravity(false);
            t.setCollidable(false);
            t.setPersistent(false);
            t.setRemoveWhenFarAway(false);
            t.setDespawnDelay(0);
            t.setCustomNameVisible(true);
            t.setCustomName(TraderNpc.displayName(kind));
            t.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        id = npc.getUniqueId();
    }

    @Override
    public void setStatusLine(String text) {
        // A vanilla entity has a single name line; the time left is shown on the trade page.
    }

    @Override
    public Entity entity() {
        Entity e = id == null ? null : Bukkit.getEntity(id);
        return e != null && e.isValid() ? e : null;
    }

    @Override
    public void despawn() {
        id = null;
        for (World world : Bukkit.getWorlds()) {
            for (WanderingTrader t : world.getEntitiesByClass(WanderingTrader.class)) {
                if (isOurs(t)) t.remove();
            }
        }
    }

    private boolean isOurs(Entity e) {
        return e instanceof WanderingTrader && e.getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isOurs(event.getRightClicked())) return;
        event.setCancelled(true);   // never the vanilla trade window
        if (event.getHand() == EquipmentSlot.HAND) onRightClick.accept(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (isOurs(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity e : event.getChunk().getEntities()) {
            if (isOurs(e) && (id == null || !id.equals(e.getUniqueId()))) e.remove();
        }
    }
}
