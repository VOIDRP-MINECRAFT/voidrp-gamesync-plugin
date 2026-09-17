package ru.voidrp.gamesync.service.trader;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** The trader's body at spawn: a Citizens player NPC when Citizens is present, a vanilla trader otherwise. */
public interface TraderNpc {

    /** Spawn (or respawn) at the location with the look of the visit kind. */
    void spawn(Location location, String kind);

    /** The text under the name, e.g. the time left. No-op when unsupported. */
    void setStatusLine(String text);

    /** The live entity, or null when not spawned. */
    Entity entity();

    void despawn();

    static String displayName(String kind) {
        return switch (kind) {
            case "elite" -> "§d§l✦ Элитный скупщик ✦";
            case "weekend" -> "§6§lСкупщик выходного дня";
            default -> "§6§lСкупщик";
        };
    }
}
