package ru.voidrp.gamesync.service.trader;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;

/**
 * The trader as a Citizens player NPC with its own skin and outfit. Lives in an in-memory registry,
 * so it is never written to Citizens' saves.yml and disappears with the server.
 */
public final class CitizensTraderNpc implements TraderNpc, Listener {

    // Signed textures from MineSkin: a fur-trimmed merchant and a hooded wanderer for the elite visit.
    private static final String MERCHANT_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTcyNDU5NDE5OTY0OSwKICAicHJvZmlsZUlkIiA6ICJlODhjMjBiOTUyMTA0NTA0OThkMDU4OTA5ODVhOTQ2OSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTY2huZWxsZXJUYWc0MjciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGIwY2M4OTM2YzI0NTFiZGI3ZmZjZDM5Y2U5NDJlMWQ3YTQzNTg2ZTI3NDJiNjU5NDc0MTg0YWI2MTMzZmE0IgogICAgfQogIH0KfQ==";
    private static final String MERCHANT_SIGNATURE = "MihqI9J/KwDJzgRiPy480YKAdhMykN0Gd0t5c1UV3xw5GpFmgjQM3Lbg+2b0qFI3UdM5PDw7GbzKMzRAvLPJDZH5/xn9/GxfNJpWZ8aZlpktoOf7hD9Hk5XM6zLGONWJpuqcOy2pYWUCF77e5iIQHfj0oHv3cepKzCKEKyA1X6RNhuV+uP0eO4TJ6V7NuR0yxx2jryKEL8wgfimn5ckr9iCvYgHWTq6RmPQ9rr3TmLDvvotRTnZDMuKdw9SqxvXozsSKrw/WVv8hrM/ftO/PP3ne5IlxxiaULrFWwmW5k2e+ZDeju169hlcuwwpDluO+UNeim/d4KCse5RHtWRsz1zRQRQU5WJ7VroPF2IXGUjfiwCF+2iZOnnso6SITEnPW64AdPjb48/j2qLj6WUEVVSkPJyprJ+xDuUVqbt22YsWm5UbIXsv7pAVm4Tj31S8YoUPdiVATNsgu1/DF2w/YCKXv07yEwtXpWD1AFkgFwWQKo4uXb5LJ+SQLVzHwV3DfReyDYAUEGyXJ7olsxBHv5/LBqYkYlhxNtDH5xlhpPWa+uADz1irtrM0VofAsDcnXcP0l8XwtCLi/Ug8RJP7NQ5fvY3zPVpKPswaqASdRAig+KDzk8IIH32rbqt2BFH5bWEnahyRVoAVkovbgCwt8gjsz6OS61TbaLz4v2p0bSH4=";
    private static final String HOODED_VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTc0ODU0OTYzNTI2NSwKICAicHJvZmlsZUlkIiA6ICIwM2FlZmNjYWE2NzE0ZDFhOThhY2Q2N2QxNjA0NzQyZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUYXpyaXh4IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzcxMzYyZDE4OWM4NmMzOTc3NDBhYjgzODEzNGVjOWFiOTRlOWViNmZiYjI1YTU5OGZhMmU5ZmY3YTY3YzZlN2IiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    private static final String HOODED_SIGNATURE = "LVUR/VdDLTxfSkdnqeWpPK7t8SibC6O/nLlnFRXp+GDtCZdBlNRKvyWd6YWXq8s+tSlkUj0M4rqinFI+9uGOWghLAHymMe01QC7/dCp/+9XAZKDgXD9SK9GpGuKfUkfVrtWQMT1aNpd09r+LdvWrfDPMZH/PgFwbjYYMzc1hVNGE3OxfrDoy54U+tfjJgu1mkxxawhqMj4OZL5+3Q8guy612WjYxk8gsIvu0AVC9eH2fdV1cItDTUZdYgQ9qP30Q4oH98S8a+fMgx/BJt85b+gILg5P7lu83NWGdMs+/dc7j4tZQhjDPx/DnYhRq0ljC9fUjG7h2Gb7ybhIRr2GPYIPfnN2Y/7WMa+rdPpGplkpG5xh72wq1yN1zCBIKx5WtQgRcH1gUD/+rExPAUuR5scWtTUU1bTLdkSf8j5mi8Z0z7Spd0cO3SgUWo248KmMDiwh4h+6C0F1X+aHTHEWkO2evYebje6R62lv6vwIpayHRi8tHWKcGoVsRP78ONBGFr536Znyn5qPltq6nS6W3BOqp8CLDSWBwZBnmnwUSYXSQhmHrDNleq5mAyO4Cm3yj/hfKTqHonYCK7zS3lQzGYlm7Bqe7m/iHYzTw4iPrWceRUMmGePzw0FgNX3Xso9cbWyJ0AY3rG4xUHY44t0cfyDTAN8d9a70l8OwX2lqUo/o=";

    private final NPCRegistry registry;
    private final Consumer<Player> onRightClick;
    private NPC npc;

    public CitizensTraderNpc(Plugin plugin, Consumer<Player> onRightClick) {
        this.onRightClick = onRightClick;
        NPCRegistry existing = CitizensAPI.getNamedNPCRegistry("voidrp_trader");
        this.registry = existing != null ? existing : CitizensAPI.createInMemoryNPCRegistry("voidrp_trader");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void spawn(Location location, String kind) {
        despawn();
        boolean elite = "elite".equals(kind);
        npc = registry.createNPC(EntityType.PLAYER, TraderNpc.displayName(kind));
        if (elite) {
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent("voidrp_trader_elite", HOODED_SIGNATURE, HOODED_VALUE);
        } else {
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent("voidrp_trader", MERCHANT_SIGNATURE, MERCHANT_VALUE);
        }
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(true);
        look.setRange(10);
        look.setRealisticLooking(true);

        Equipment eq = npc.getOrAddTrait(Equipment.class);
        equip(eq, Equipment.EquipmentSlot.CHESTPLATE, elite ? "sophisticatedbackpacks:netherite_backpack" : "sophisticatedbackpacks:gold_backpack");
        equip(eq, Equipment.EquipmentSlot.HAND, elite ? "minecraft:nether_star" : "minecraft:gold_ingot");
        equip(eq, Equipment.EquipmentSlot.OFF_HAND, elite ? "minecraft:soul_lantern" : "minecraft:lantern");

        HologramTrait holo = npc.getOrAddTrait(HologramTrait.class);
        holo.clear();
        holo.addLine("§7ПКМ — торговать");
        holo.addLine("§e…");
        npc.spawn(location);
    }

    private static void equip(Equipment eq, Equipment.EquipmentSlot slot, String key) {
        Material material = Material.matchMaterial(key);
        if (material != null && material != Material.AIR) eq.set(slot, new ItemStack(material));
    }

    @Override
    public void setStatusLine(String text) {
        if (npc == null || !npc.isSpawned()) return;
        HologramTrait holo = npc.getOrAddTrait(HologramTrait.class);
        holo.setLine(1, text);
    }

    @Override
    public Entity entity() {
        return npc != null && npc.isSpawned() ? npc.getEntity() : null;
    }

    @Override
    public void despawn() {
        if (npc != null) {
            npc.destroy();
            npc = null;
        }
        registry.deregisterAll();
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (event.getNPC().getOwningRegistry() != registry) return;
        event.setCancelled(true);
        onRightClick.accept(event.getClicker());
    }
}
