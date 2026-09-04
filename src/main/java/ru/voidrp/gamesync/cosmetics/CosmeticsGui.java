package ru.voidrp.gamesync.cosmetics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * In-game cosmetics menu ({@code /cosmetics}): a chest GUI listing the player's owned Figura
 * cosmetics. Clicking an item toggles it on/off via the backend (per-slot); the bottom barrier
 * takes everything off. Lets players change their look without opening the WebGUI browser.
 */
public final class CosmeticsGui implements CommandExecutor, Listener {

    private static final String TITLE = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "✦ Косметика";
    private static final int UNEQUIP_ALL_SLOT = 49;

    private final VoidRpGameSyncPlugin plugin;
    // per-player mapping of inventory slot -> cosmetic slug for the currently open GUI
    private final Map<UUID, Map<Integer, String>> openSlots = new HashMap<>();

    public CosmeticsGui(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        open(p);
        return true;
    }

    /** Fetch owned cosmetics off-thread, then build + open the inventory on the main thread. */
    public void open(Player player) {
        final String nick = player.getName();
        player.sendMessage("§7Открываем косметику…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String[]> owned;
            try {
                owned = plugin.getBackendClient().getOwnedCosmetics(nick);
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage("§cОшибка загрузки косметики: §f" + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> build(player, owned));
        });
    }

    private void build(Player player, List<String[]> owned) {
        if (!player.isOnline()) return;
        int rows = Math.max(2, Math.min(6, (owned.size() / 9) + 2));
        Inventory inv = Bukkit.createInventory(player, rows * 9, TITLE);

        Map<Integer, String> slotMap = new HashMap<>();
        int i = 0;
        for (String[] c : owned) {
            if (i >= (rows - 1) * 9) break;          // leave the last row for controls
            String slug = c[0], name = c[1], slot = c[2];
            boolean equipped = "1".equals(c[3]);
            inv.setItem(i, item(name, slot, equipped));
            slotMap.put(i, slug);
            i++;
        }
        if (owned.isEmpty()) {
            inv.setItem(4, named(Material.BARRIER, "§cУ вас пока нет косметики",
                    List.of("§7Купить можно в WebGUI → Косметика")));
        }
        inv.setItem(rows * 9 - 9 + 4, named(Material.BARRIER, "§cСнять всё", List.of("§7Убрать все надетые косметики")));
        // remember which real slot is the "unequip all" for this size
        slotMap.put(rows * 9 - 9 + 4, "\0ALL");

        openSlots.put(player.getUniqueId(), slotMap);
        player.openInventory(inv);
    }

    private ItemStack item(String name, String slot, boolean equipped) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName((equipped ? "§a✔ " : "§f") + name);
        List<String> lore = new ArrayList<>();
        lore.add("§7Слот: §e" + slotRu(slot));
        lore.add(" ");
        lore.add(equipped ? "§c▶ Нажмите, чтобы снять" : "§a▶ Нажмите, чтобы надеть");
        m.setLore(lore);
        if (equipped) {
            m.addEnchant(Enchantment.UNBREAKING, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        it.setItemMeta(m);
        return it;
    }

    private static String slotRu(String slot) {
        return switch (slot) {
            case "head" -> "Голова";
            case "body" -> "Тело";
            case "wings" -> "Крылья";
            case "accessory" -> "Аксессуар";
            default -> "Весь";
        };
    }

    private ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null) m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Map<Integer, String> slotMap = openSlots.get(p.getUniqueId());
        if (slotMap == null) return;
        // our GUI is open (tracked) — cancel any interaction with it
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(e.getView().getTopInventory())) return;

        String slug = slotMap.get(e.getRawSlot());
        if (slug == null) return;
        final String nick = p.getName();
        if ("\0ALL".equals(slug)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try { plugin.getBackendClient().unequipAllCosmetics(nick); } catch (Exception ex) { warn(p, ex); return; }
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
            });
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getBackendClient().toggleCosmetic(nick, slug); } catch (Exception ex) { warn(p, ex); return; }
            Bukkit.getScheduler().runTask(plugin, () -> open(p));   // re-fetch + reopen with fresh state
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (TITLE.equals(e.getView().getTitle())) e.setCancelled(true);   // no dragging items into the menu
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (TITLE.equals(e.getView().getTitle())) openSlots.remove(e.getPlayer().getUniqueId());
    }

    private void warn(Player p, Exception ex) {
        Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage("§cОшибка: §f" + ex.getMessage()));
    }
}
