package ru.voidrp.gamesync.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Быстрый путь для открытия эпох: крафт на ванильном верстаке и вход на сервер.
 *
 * <p>Полноту обеспечивает не этот класс, а периодический осмотр инвентаря в
 * {@link ru.voidrp.gamesync.service.EpochService} — {@code CraftItemEvent} не
 * срабатывает на модовых мультиблоках, а именно в них делаются верхние предметы
 * пака. Здесь событие нужно только чтобы объявление появилось мгновенно.
 */
public final class TierTrackingListener implements Listener {

    private final VoidRpGameSyncPlugin plugin;

    public TierTrackingListener(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        var service = plugin.getEpochService();
        if (service == null) return;
        service.onCrafted(player, event.getRecipe().getResult());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var service = plugin.getEpochService();
        if (service == null) return;
        // Осмотр при входе: догоняет эпохи, добытые до включения системы или
        // пока игрок был офлайн (получил предмет из почты рынка, например).
        plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> service.scan(event.getPlayer()), 60L);
    }
}
