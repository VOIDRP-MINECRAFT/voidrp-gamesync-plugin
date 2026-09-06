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
    private static final int PER_PAGE = 45;   // slots 0..44; the last row (45..53) holds controls

    private final VoidRpGameSyncPlugin plugin;
    // per-player mapping of inventory slot -> cosmetic slug (or a control marker) for the open GUI
    private final Map<UUID, Map<Integer, String>> openSlots = new HashMap<>();
    private final Map<UUID, Integer> page = new HashMap<>();

    public CosmeticsGui(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cТолько для игроков.");
            return true;
        }
        open(p, page.getOrDefault(p.getUniqueId(), 0));
        return true;
    }

    /** Fetch owned cosmetics off-thread, then build + open the inventory (given page) on the main thread. */
    public void open(Player player, int wantPage) {
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
            Bukkit.getScheduler().runTask(plugin, () -> build(player, owned, wantPage));
        });
    }

    private void build(Player player, List<String[]> owned, int wantPage) {
        if (!player.isOnline()) return;
        int totalPages = Math.max(1, (owned.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(wantPage, totalPages - 1));
        page.put(player.getUniqueId(), pg);

        // dynamic size for a single page, full 54 when paginated
        int rows = totalPages > 1 ? 6 : Math.max(2, Math.min(6, (owned.size() / 9) + 2));
        Inventory inv = Bukkit.createInventory(player, rows * 9, TITLE);
        int ctrlRow = rows * 9 - 9;

        Map<Integer, String> slotMap = new HashMap<>();
        int start = pg * PER_PAGE;
        int end = Math.min(start + Math.min(PER_PAGE, ctrlRow), owned.size());
        for (int i = start, s = 0; i < end && s < ctrlRow; i++, s++) {
            String[] c = owned.get(i);
            inv.setItem(s, item(c[1], c[2], "1".equals(c[3])));
            slotMap.put(s, c[0]);
        }
        if (owned.isEmpty()) {
            inv.setItem(4, named(Material.BARRIER, "§cУ вас пока нет косметики",
                    List.of("§7Купить можно в WebGUI → Косметика")));
        }
        // controls row
        inv.setItem(ctrlRow + 4, named(Material.BARRIER, "§cСнять всё", List.of("§7Убрать все надетые косметики")));
        slotMap.put(ctrlRow + 4, "\0ALL");
        if (pg > 0) {
            inv.setItem(ctrlRow, named(Material.ARROW, "§e← Назад", List.of("§7Стр. " + pg + "/" + totalPages)));
            slotMap.put(ctrlRow, "\0PREV");
        }
        if (pg < totalPages - 1) {
            inv.setItem(ctrlRow + 8, named(Material.ARROW, "§eВперёд →", List.of("§7Стр. " + (pg + 2) + "/" + totalPages)));
            slotMap.put(ctrlRow + 8, "\0NEXT");
        }

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
        int cur = page.getOrDefault(p.getUniqueId(), 0);
        if ("\0PREV".equals(slug)) { open(p, cur - 1); return; }
        if ("\0NEXT".equals(slug)) { open(p, cur + 1); return; }
        if ("\0ALL".equals(slug)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try { plugin.getBackendClient().unequipAllCosmetics(nick); } catch (Exception ex) { warn(p, ex); return; }
                Bukkit.getScheduler().runTask(plugin, () -> open(p, cur));
            });
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getBackendClient().toggleCosmetic(nick, slug); } catch (Exception ex) { warn(p, ex); return; }
            Bukkit.getScheduler().runTask(plugin, () -> open(p, cur));   // re-fetch + reopen same page
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
