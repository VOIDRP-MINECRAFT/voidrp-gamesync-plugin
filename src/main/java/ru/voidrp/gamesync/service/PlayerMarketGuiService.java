package ru.voidrp.gamesync.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderRead;
import ru.voidrp.gamesync.model.PlayerMarketSellOrderRead;

public final class PlayerMarketGuiService {

    public enum Tab { SELL, BUY }

    // ─── State ────────────────────────────────────────────────────────────────

    public static final class GuiState implements InventoryHolder {
        public Tab   tab;
        public int   page;
        public List<PlayerMarketSellOrderRead> sellOrders;
        public List<PlayerMarketBuyOrderRead>  buyOrders;
        public Inventory inventory;
        public final String ownerName;

        public GuiState(String ownerName, Tab tab) {
            this.ownerName  = ownerName;
            this.tab        = tab;
            this.page       = 0;
            this.sellOrders = new ArrayList<>();
            this.buyOrders  = new ArrayList<>();
        }

        @Override public Inventory getInventory() { return inventory; }

        public List<?> currentPageItems() {
            List<?> src = tab == Tab.SELL ? sellOrders : buyOrders;
            int from = page * ITEMS_PER_PAGE;
            int to   = Math.min(from + ITEMS_PER_PAGE, src.size());
            return from >= src.size() ? List.of() : src.subList(from, to);
        }

        public int maxPage() {
            int total = (tab == Tab.SELL ? sellOrders : buyOrders).size();
            return total == 0 ? 0 : (total - 1) / ITEMS_PER_PAGE;
        }
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private static final int SIZE           = 54;
    private static final int ITEMS_PER_PAGE = 36;  // slots 9-44

    // Top row:  [0-3] SELL tab | [4] divider | [5-8] BUY tab
    private static final int[] SELL_SLOTS = {0, 1, 2, 3};
    private static final int   DIVIDER    = 4;
    private static final int[] BUY_SLOTS  = {5, 6, 7, 8};

    // Bottom row: [45] prev | [46-48] fill | [49] create | [50-52] fill | [53] next
    // Page info at 47, create at 49
    private static final int SLOT_PREV   = 45;
    private static final int SLOT_PAGE   = 47;
    private static final int SLOT_CREATE = 49;
    private static final int SLOT_NEXT   = 53;

    // ─── Fields ───────────────────────────────────────────────────────────────

    private final VoidRpGameSyncPlugin plugin;
    private final Map<UUID, GuiState>  openGuis = new ConcurrentHashMap<>();

    public PlayerMarketGuiService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Open / load ──────────────────────────────────────────────────────────

    public void open(Player player) { openTab(player, Tab.SELL, 0); }

    public void openTab(Player player, Tab tab, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var sr = plugin.getBackendClient().listPlayerMarketSellOrders(200, 0);
                var br = plugin.getBackendClient().listPlayerMarketBuyOrders(200, 0);

                List<PlayerMarketSellOrderRead> sells = sr != null && sr.items != null ? sr.items : new ArrayList<>();
                List<PlayerMarketBuyOrderRead>  buys  = br != null && br.items != null ? br.items : new ArrayList<>();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    GuiState st = new GuiState(player.getName(), tab);
                    st.sellOrders = sells;
                    st.buyOrders  = buys;
                    st.page       = page;
                    buildAndOpen(player, st);
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage("§c[Рынок] Ошибка загрузки: §f" + ex.getMessage()));
            }
        });
    }

    private void buildAndOpen(Player player, GuiState st) {
        Inventory inv = Bukkit.createInventory(st, SIZE, buildTitle(st));
        st.inventory  = inv;
        fillNav(inv, st);
        fillItems(inv, st);
        openGuis.put(player.getUniqueId(), st);
        player.openInventory(inv);
        // Re-add after InventoryCloseEvent for the previously open inventory fires synchronously
        openGuis.put(player.getUniqueId(), st);
    }

    private String buildTitle(GuiState st) {
        int cnt = (st.tab == Tab.SELL ? st.sellOrders : st.buyOrders).size();
        return st.tab == Tab.SELL
                ? "§6§lRынок  ❙  §6Продают §7(" + cnt + ")"
                : "§b§lРынок  ❙  §bПокупают §7(" + cnt + ")";
    }

    // ─── Nav bar ──────────────────────────────────────────────────────────────

    private void fillNav(Inventory inv, GuiState st) {
        boolean isSell = st.tab == Tab.SELL;

        // ── SELL tab (4 slots) ────────────────────────────────────────────────
        if (isSell) {
            // Active — ORANGE_CONCRETE + enchant glow, very visible
            ItemStack active = glow(concrete(Material.ORANGE_CONCRETE,
                    "§6§l▶  ПРОДАЮТ ИГРОКИ  ◀",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§a● §7Активная вкладка",
                        "§8Ордеров выставлено: §f" + st.sellOrders.size(),
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§eЛКМ        §8→ §7купить §f1 шт.",
                        "§eПКМ        §8→ §7купить §f16 шт.",
                        "§eShift+ЛКМ §8→ §7купить §f64 шт.",
                        "§eShift+ПКМ §8→ §7купить §fВСЁ")));
            for (int s : SELL_SLOTS) inv.setItem(s, active);
        } else {
            // Inactive — GRAY_CONCRETE, muted
            ItemStack inactive = concrete(Material.GRAY_CONCRETE,
                    "§7ПРОДАЮТ ИГРОКИ",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§8Ордеров: §7" + st.sellOrders.size(),
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§7Нажми чтобы переключить"));
            for (int s : SELL_SLOTS) inv.setItem(s, inactive);
        }

        // ── Centre divider ────────────────────────────────────────────────────
        inv.setItem(DIVIDER, silent(Material.PURPLE_STAINED_GLASS_PANE, "§5§l  ✦  "));

        // ── BUY tab (4 slots) ─────────────────────────────────────────────────
        if (!isSell) {
            ItemStack active = glow(concrete(Material.CYAN_CONCRETE,
                    "§b§l▶  ПОКУПАЮТ ИГРОКИ  ◀",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§a● §7Активная вкладка",
                        "§8Заявок выставлено: §f" + st.buyOrders.size(),
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§eЛКМ        §8→ §7продать §f1 шт.",
                        "§eПКМ        §8→ §7продать §f16 шт.",
                        "§eShift+ЛКМ §8→ §7продать §f64 шт.",
                        "§eShift+ПКМ §8→ §7продать §fВСЁ")));
            for (int s : BUY_SLOTS) inv.setItem(s, active);
        } else {
            ItemStack inactive = concrete(Material.GRAY_CONCRETE,
                    "§7ПОКУПАЮТ ИГРОКИ",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§8Заявок: §7" + st.buyOrders.size(),
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§7Нажми чтобы переключить"));
            for (int s : BUY_SLOTS) inv.setItem(s, inactive);
        }

        // ── Bottom bar background ─────────────────────────────────────────────
        ItemStack fill = silent(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i <= 53; i++) inv.setItem(i, fill);

        // ── Pagination ────────────────────────────────────────────────────────
        int total    = (isSell ? st.sellOrders : st.buyOrders).size();
        int maxPage  = st.maxPage();
        int pageNum  = st.page + 1;
        int pageTotal = maxPage + 1;

        if (st.page > 0) {
            inv.setItem(SLOT_PREV, glow(item(Material.SPECTRAL_ARROW,
                    "§e§l◄ Предыдущая",
                    List.of("§7Страница §f" + (pageNum - 1) + " §7из §f" + pageTotal))));
        } else {
            inv.setItem(SLOT_PREV, silent(Material.GRAY_STAINED_GLASS_PANE, "§8◄ Нет страниц назад"));
        }

        if (st.page < maxPage) {
            inv.setItem(SLOT_NEXT, glow(item(Material.SPECTRAL_ARROW,
                    "§e§lСледующая ►",
                    List.of("§7Страница §f" + (pageNum + 1) + " §7из §f" + pageTotal))));
        } else {
            inv.setItem(SLOT_NEXT, silent(Material.GRAY_STAINED_GLASS_PANE, "§8Нет страниц вперёд ►"));
        }

        int onPage = Math.min(ITEMS_PER_PAGE, Math.max(0, total - st.page * ITEMS_PER_PAGE));
        inv.setItem(SLOT_PAGE, item(Material.BOOK,
                "§f§lСтраница §e" + pageNum + " §f/ §e" + pageTotal,
                List.of("§7Всего ордеров: §f" + total,
                        "§7На странице:   §f" + onPage)));

        // ── Create button ─────────────────────────────────────────────────────
        if (isSell) {
            inv.setItem(SLOT_CREATE, glow(item(Material.EMERALD,
                    "§a§l✦ Выставить на продажу",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§71. Возьми предмет §fв руку",
                        "§72. Закрой GUI",
                        "§73. В чат: §e<кол-во> <цена>",
                        "§8   Пример: §f32 150",
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§aКомиссия: §f2%  §a│  §aСрок: §f7 дней"))));
        } else {
            inv.setItem(SLOT_CREATE, glow(item(Material.DIAMOND,
                    "§b§l✦ Разместить заявку на покупку",
                    List.of(
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§71. Закрой GUI",
                        "§72. В чат: §bnamespace:id кол-во цена",
                        "§8   Пример: §fminecraft:diamond 64 100",
                        "§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔",
                        "§bДеньги резервируются сразу"))));
        }
    }

    // ─── Item grid (slots 9–44) ────────────────────────────────────────────────

    private void fillItems(Inventory inv, GuiState st) {
        for (int i = 9; i <= 44; i++) inv.setItem(i, null);

        List<?> page = st.currentPageItems();
        for (int i = 0; i < page.size() && i < ITEMS_PER_PAGE; i++) {
            Object order = page.get(i);
            ItemStack icon;
            if      (order instanceof PlayerMarketSellOrderRead sell) icon = iconForSell(sell, st.ownerName);
            else if (order instanceof PlayerMarketBuyOrderRead  buy)  icon = iconForBuy(buy,  st.ownerName);
            else continue;
            inv.setItem(9 + i, icon);
        }
    }

    // ─── Icons ────────────────────────────────────────────────────────────────

    private ItemStack iconForSell(PlayerMarketSellOrderRead o, String viewer) {
        ItemStack icon = resolveIcon(o.item_stack_base64, o.item_key);

        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        String name = o.display_name != null && !o.display_name.isBlank()
                ? o.display_name : readableKey(o.item_key);
        meta.setDisplayName("§f§l" + name);

        boolean own = same(viewer, o.seller_player_name);
        List<String> lore = new ArrayList<>();
        lore.add("§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔");
        appendItemDetails(lore, meta, o.item_key);
        lore.add("§7Продавец:  §e" + safe(o.seller_player_name));
        lore.add("§7ID предмета: §8" + safe(o.item_key));
        lore.add("§7Осталось:  §a" + o.remaining_amount + " §7/ §8" + o.total_amount + " шт.");
        lore.add("§7Цена за шт.: §6§l" + money(o.unit_price) + " ₽");
        lore.add("§7Итого:     §6" + money((double) o.remaining_amount * o.unit_price) + " ₽");
        lore.add("§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔");
        if (own) {
            lore.add("§c§l⚠ Это твой ордер");
            lore.add("§7Отмена: §f/pm cancel sell " + shortId(o.id));
        } else {
            lore.add("§e[ЛКМ]       §8→ §7купить §f1 шт.");
            lore.add("§e[ПКМ]       §8→ §7купить §f16 шт.");
            lore.add("§e[Shift+ЛКМ] §8→ §7купить §f64 шт.");
            lore.add("§e[Shift+ПКМ] §8→ §7купить §fВСЁ §8(" + o.remaining_amount + ")");
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack iconForBuy(PlayerMarketBuyOrderRead o, String viewer) {
        ItemStack icon = resolveIcon(o.item_display_base64, o.item_key);

        ItemMeta meta = icon.getItemMeta();
        if (meta == null) return icon;

        String name = o.display_name != null && !o.display_name.isBlank()
                ? o.display_name : readableKey(o.item_key);
        meta.setDisplayName("§b§l" + name);

        boolean own = same(viewer, o.buyer_player_name);
        List<String> lore = new ArrayList<>();
        lore.add("§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔");
        appendItemDetails(lore, meta, o.item_key);
        lore.add("§7Покупатель: §e" + safe(o.buyer_player_name));
        lore.add("§7ID предмета: §8" + safe(o.item_key));
        lore.add("§7Нужно ещё:  §a" + o.remaining_amount + " §7/ §8" + o.total_amount + " шт.");
        lore.add("§7Цена за шт.: §6§l" + money(o.unit_price) + " ₽");
        lore.add("§7Выплатит:   §6" + money((double) o.remaining_amount * o.unit_price) + " ₽");
        lore.add("§8▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔");
        if (own) {
            lore.add("§c§l⚠ Это твоя заявка");
            lore.add("§7Отмена: §f/pm cancel buy " + shortId(o.id));
        } else {
            lore.add("§7Если у тебя есть этот предмет:");
            lore.add("§e[ЛКМ]       §8→ §7продать §f1 шт.");
            lore.add("§e[ПКМ]       §8→ §7продать §f16 шт.");
            lore.add("§e[Shift+ЛКМ] §8→ §7продать §f64 шт.");
            lore.add("§e[Shift+ПКМ] §8→ §7продать §fВСЁ §8(" + o.remaining_amount + ")");
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.values());
        icon.setItemMeta(meta);
        return icon;
    }

    private void appendItemDetails(List<String> lore, ItemMeta meta, String itemKey) {
        if (meta instanceof PotionMeta pm) {
            PotionType type = pm.getBasePotionType();
            if (type != null) {
                String effectId = type.getKey().getKey().toLowerCase(Locale.ROOT);
                String effectName = POTION_NAMES.getOrDefault(effectId, effectId.replace('_', ' '));
                lore.add("§7Эффект: §d" + effectName);
            }
        } else if (meta instanceof EnchantmentStorageMeta esm && !esm.getStoredEnchants().isEmpty()) {
            esm.getStoredEnchants().forEach((ench, lvl) -> {
                String enchId = ench.getKey().getKey().toLowerCase(Locale.ROOT);
                String enchName = ENCHANT_NAMES.getOrDefault(enchId, enchId.replace('_', ' '));
                String roman = lvl > 0 && lvl < ROMAN.length ? ROMAN[lvl] : String.valueOf(lvl);
                lore.add("§7Зачарование: §5" + enchName + " §f" + roman);
            });
        } else if (itemKey != null) {
            // Parse details directly from pseudo-namespace key (fallback when base64 not available)
            int c = itemKey.indexOf(':');
            if (c > 0) {
                String prefix = itemKey.substring(0, c).toLowerCase(Locale.ROOT);
                String rest   = itemKey.substring(c + 1);
                if (prefix.equals("potion") || prefix.equals("splash_potion") || prefix.equals("lingering_potion")) {
                    String effectId = rest.split(":")[0].toLowerCase(Locale.ROOT);
                    String effectName = POTION_NAMES.getOrDefault(effectId, effectId.replace('_', ' '));
                    lore.add("§7Эффект: §d" + effectName);
                } else if (prefix.equals("enchanted_book")) {
                    int c2 = rest.indexOf(':');
                    if (c2 > 0) {
                        String enchId   = rest.substring(0, c2).toLowerCase(Locale.ROOT);
                        String enchName = ENCHANT_NAMES.getOrDefault(enchId, enchId.replace('_', ' '));
                        try {
                            int lvl = Integer.parseInt(rest.substring(c2 + 1));
                            String roman = lvl > 0 && lvl < ROMAN.length ? ROMAN[lvl] : String.valueOf(lvl);
                            lore.add("§7Зачарование: §5" + enchName + " §f" + roman);
                        } catch (NumberFormatException ignored) {
                            lore.add("§7Зачарование: §5" + enchName);
                        }
                    }
                }
            }
        }
    }

    /**
     * Three-level icon resolution:
     *   1. NBT / BukkitObjectInputStream base64 snapshot
     *   2. Pseudo-namespace keys (potion:*, enchanted_book:*, splash_potion:*, lingering_potion:*)
     *   3. Material.matchMaterial (vanilla + Mohist mod items)
     *   4. Namespace-coloured fallback block
     */
    private ItemStack resolveIcon(String base64, String itemKey) {
        // 1. Stored snapshot
        if (base64 != null && !base64.isBlank()) {
            try { return plugin.getItemStackSnapshotService().deserialize(base64, 1); }
            catch (Exception ignored) {}
        }

        if (itemKey != null) {
            int colonIdx = itemKey.indexOf(':');
            String prefix = colonIdx > 0 ? itemKey.substring(0, colonIdx).toLowerCase(Locale.ROOT) : "";
            String rest   = colonIdx > 0 ? itemKey.substring(colonIdx + 1) : "";

            // 2a. Potion pseudo-namespace: potion:night_vision, splash_potion:healing, etc.
            if (prefix.equals("potion") || prefix.equals("splash_potion") || prefix.equals("lingering_potion")) {
                Material mat = switch (prefix) {
                    case "splash_potion"   -> Material.SPLASH_POTION;
                    case "lingering_potion"-> Material.LINGERING_POTION;
                    default                -> Material.POTION;
                };
                ItemStack potion = new ItemStack(mat);
                try {
                    String effectName = rest.split(":")[0];
                    PotionType type = Registry.POTION.get(NamespacedKey.minecraft(effectName));
                    if (type != null) {
                        PotionMeta pm = (PotionMeta) potion.getItemMeta();
                        pm.setBasePotionType(type);
                        potion.setItemMeta(pm);
                    }
                } catch (Exception ignored) {}
                return potion;
            }

            // 2b. Enchanted book pseudo-namespace: enchanted_book:sharpness:5
            if (prefix.equals("enchanted_book")) {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                try {
                    String[] parts = rest.split(":", 2);
                    if (parts.length == 2) {
                        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                        int level = Integer.parseInt(parts[1]);
                        if (ench != null) {
                            EnchantmentStorageMeta esm = (EnchantmentStorageMeta) book.getItemMeta();
                            esm.addStoredEnchant(ench, level, true);
                            book.setItemMeta(esm);
                        }
                    }
                } catch (Exception ignored) {}
                return book;
            }

            // 3. Material lookup (works for minecraft:* and Mohist-registered mod materials)
            Material m = Material.matchMaterial(itemKey);
            if (m == null) m = Material.matchMaterial(
                    itemKey.toUpperCase(Locale.ROOT).replace(':', '_').replace('-', '_'));
            if (m != null && m != Material.AIR) return new ItemStack(m);
        }

        // 4. Namespace-based coloured block so you can at least tell mods apart
        if (itemKey != null && itemKey.contains(":")) {
            Material fb = nsFallback(itemKey.substring(0, itemKey.indexOf(':')));
            ItemStack fbi = new ItemStack(fb);
            ItemMeta fm = fbi.getItemMeta();
            if (fm != null) { fm.setDisplayName("§8" + itemKey); fm.addItemFlags(ItemFlag.values()); fbi.setItemMeta(fm); }
            return fbi;
        }

        return new ItemStack(Material.PAPER);
    }

    private Material nsFallback(String ns) {
        return switch (ns.toLowerCase(Locale.ROOT)) {
            case "avaritia"                    -> Material.NETHER_STAR;
            case "thermal", "thermal_foundation", "thermal_expansion" -> Material.REDSTONE_BLOCK;
            case "mekanism"                    -> Material.LAPIS_BLOCK;
            case "appliedenergistics2", "ae2"  -> Material.CYAN_CONCRETE;
            case "create"                      -> Material.IRON_BLOCK;
            case "botania"                     -> Material.PINK_PETALS;
            case "blue_skies"                  -> Material.LIGHT_BLUE_CONCRETE;
            case "aether"                      -> Material.LIGHT_BLUE_STAINED_GLASS;
            case "alexsmobs"                   -> Material.TROPICAL_FISH;
            case "twilightforest"              -> Material.ENCHANTING_TABLE;
            case "immersiveengineering"        -> Material.COPPER_BLOCK;
            default                            -> Material.PURPLE_CONCRETE;
        };
    }

    // ─── Click handling ───────────────────────────────────────────────────────

    public void handleClick(Player player, int slot, ClickType click) {
        GuiState st = openGuis.get(player.getUniqueId());
        if (st == null) return;

        for (int s : SELL_SLOTS) {
            if (slot == s) { if (st.tab != Tab.SELL) switchTab(player, st, Tab.SELL); return; }
        }
        for (int s : BUY_SLOTS) {
            if (slot == s) { if (st.tab != Tab.BUY) switchTab(player, st, Tab.BUY); return; }
        }

        if (slot == SLOT_PREV) {
            if (st.page > 0) { st.page--; refresh(player, st); }
            return;
        }
        if (slot == SLOT_NEXT) {
            if (st.page < st.maxPage()) { st.page++; refresh(player, st); }
            return;
        }
        if (slot == SLOT_CREATE) {
            player.closeInventory();
            if (st.tab == Tab.SELL) plugin.getPlayerMarketService().startGuiSellFlow(player);
            else                    plugin.getPlayerMarketService().startGuiBuyFlow(player);
            return;
        }

        if (slot >= 9 && slot <= 44) {
            int index = st.page * ITEMS_PER_PAGE + (slot - 9);
            if (st.tab == Tab.SELL) {
                if (index < st.sellOrders.size()) clickSell(player, st.sellOrders.get(index), click);
            } else {
                if (index < st.buyOrders.size())  clickBuy(player,  st.buyOrders.get(index),  click);
            }
        }
    }

    private void clickSell(Player player, PlayerMarketSellOrderRead o, ClickType click) {
        if (same(player.getName(), o.seller_player_name)) {
            player.sendMessage("§c[Рынок] Это твой ордер. Отмена: §f/pm cancel sell " + shortId(o.id));
            return;
        }
        int amt = switch (click) {
            case LEFT -> 1; case RIGHT -> 16;
            case SHIFT_LEFT -> 64; case SHIFT_RIGHT -> o.remaining_amount; default -> 0;
        };
        if (amt <= 0) return;
        amt = Math.min(amt, o.remaining_amount);
        if (plugin.getEconomy() == null) { player.sendMessage("§c[Рынок] Экономика недоступна."); return; }
        double total = round2((double) amt * o.unit_price);
        if (plugin.getEconomy().getBalance(player) < total) {
            player.sendMessage("§c[Рынок] Недостаточно монет. Нужно: §6" + money(total) + " ₽"); return;
        }
        player.closeInventory();
        plugin.getPlayerMarketService().executeBuyFromGui(player, o, amt);
    }

    private void clickBuy(Player player, PlayerMarketBuyOrderRead o, ClickType click) {
        if (same(player.getName(), o.buyer_player_name)) {
            player.sendMessage("§c[Рынок] Это твоя заявка. Отмена: §f/pm cancel buy " + shortId(o.id));
            return;
        }
        int amt = switch (click) {
            case LEFT -> 1; case RIGHT -> 16;
            case SHIFT_LEFT -> 64; case SHIFT_RIGHT -> o.remaining_amount; default -> 0;
        };
        if (amt <= 0) return;
        player.closeInventory();
        plugin.getPlayerMarketService().executeSellFromGui(player, o, Math.min(amt, o.remaining_amount));
    }

    // ─── Refresh helpers ──────────────────────────────────────────────────────

    private void switchTab(Player player, GuiState st, Tab newTab) {
        st.tab  = newTab;
        st.page = 0;
        Inventory inv = Bukkit.createInventory(st, SIZE, buildTitle(st));
        st.inventory  = inv;
        fillNav(inv, st);
        fillItems(inv, st);
        openGuis.put(player.getUniqueId(), st);
        player.openInventory(inv);
        // InventoryCloseEvent fires synchronously inside openInventory and removes the state —
        // re-add it so clicks in the new inventory are handled correctly.
        openGuis.put(player.getUniqueId(), st);
    }

    private void refresh(Player player, GuiState st) {
        fillNav(st.inventory, st);
        fillItems(st.inventory, st);
    }

    public void onClose(Player player) { openGuis.remove(player.getUniqueId()); }
    public boolean isPlayerMarketGui(InventoryHolder h) { return h instanceof GuiState; }
    public GuiState getState(Player player) { return openGuis.get(player.getUniqueId()); }

    public void closeAll() {
        for (UUID uuid : openGuis.keySet()) {
            org.bukkit.entity.Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) p.closeInventory();
        }
        openGuis.clear();
    }

    // ─── Item builders ────────────────────────────────────────────────────────

    /** Solid concrete block — highly visible, no transparency. */
    private ItemStack concrete(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m   = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(lore);
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
        }
        return it;
    }

    /** Regular item with lore. */
    private ItemStack item(Material mat, String name, List<String> lore) {
        return concrete(mat, name, lore);
    }

    /** Background filler — no visible name or lore. */
    private ItemStack silent(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m   = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.addItemFlags(ItemFlag.values());
            it.setItemMeta(m);
        }
        return it;
    }

    /** Adds enchant glow effect (purple shimmer) to make active elements stand out. */
    private ItemStack glow(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.addEnchant(Enchantment.UNBREAKING, 1, true);
            m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            it.setItemMeta(m);
        }
        return it;
    }

    // ─── String helpers ───────────────────────────────────────────────────────

    private static final Map<String, String> POTION_NAMES = Map.ofEntries(
        Map.entry("awkward",          "Мутное зелье"),
        Map.entry("fire_resistance",  "Огнестойкость"),
        Map.entry("harming",          "Вред"),
        Map.entry("healing",          "Мгновенное лечение"),
        Map.entry("infested",         "Заражение"),
        Map.entry("invisibility",     "Невидимость"),
        Map.entry("leaping",          "Прыжок"),
        Map.entry("long_fire_resistance","Огнестойкость (долгое)"),
        Map.entry("long_invisibility","Невидимость (долгое)"),
        Map.entry("long_leaping",     "Прыжок (долгое)"),
        Map.entry("long_night_vision","Ночное зрение (долгое)"),
        Map.entry("long_poison",      "Яд (долгое)"),
        Map.entry("long_regeneration","Регенерация (долгое)"),
        Map.entry("long_slow_falling","Медленное падение (долгое)"),
        Map.entry("long_slowness",    "Замедление (долгое)"),
        Map.entry("long_strength",    "Сила (долгое)"),
        Map.entry("long_swiftness",   "Скорость (долгое)"),
        Map.entry("long_turtle_master","Панцирь черепахи (долгое)"),
        Map.entry("long_water_breathing","Дыхание под водой (долгое)"),
        Map.entry("mundane",          "Обычное зелье"),
        Map.entry("night_vision",     "Ночное зрение"),
        Map.entry("oozing",           "Слизеобразование"),
        Map.entry("poison",           "Яд"),
        Map.entry("regeneration",     "Регенерация"),
        Map.entry("slow_falling",     "Медленное падение"),
        Map.entry("slowness",         "Замедление"),
        Map.entry("strength",         "Сила"),
        Map.entry("strong_harming",   "Вред II"),
        Map.entry("strong_healing",   "Мгновенное лечение II"),
        Map.entry("strong_leaping",   "Прыжок II"),
        Map.entry("strong_poison",    "Яд II"),
        Map.entry("strong_regeneration","Регенерация II"),
        Map.entry("strong_slowness",  "Замедление IV"),
        Map.entry("strong_strength",  "Сила II"),
        Map.entry("strong_swiftness", "Скорость II"),
        Map.entry("strong_turtle_master","Панцирь черепахи II"),
        Map.entry("swiftness",        "Скорость"),
        Map.entry("thick",            "Густое зелье"),
        Map.entry("turtle_master",    "Панцирь черепахи"),
        Map.entry("water",            "Водяное зелье"),
        Map.entry("water_breathing",  "Дыхание под водой"),
        Map.entry("weaving",          "Паутина"),
        Map.entry("wind_charged",     "Заряд ветра"),
        Map.entry("tipped_arrow",     "Стрела")
    );

    private static final Map<String, String> ENCHANT_NAMES = Map.ofEntries(
        Map.entry("aqua_affinity",       "Родство с водой"),
        Map.entry("bane_of_arthropods",  "Гибель членистоногих"),
        Map.entry("binding_curse",       "Проклятие привязки"),
        Map.entry("blast_protection",    "Взрывозащита"),
        Map.entry("breach",              "Пробой"),
        Map.entry("channeling",          "Проводник"),
        Map.entry("density",             "Плотность"),
        Map.entry("depth_strider",       "Пешеход глубин"),
        Map.entry("efficiency",          "Эффективность"),
        Map.entry("feather_falling",     "Мягкое падение"),
        Map.entry("fire_aspect",         "Огненный аспект"),
        Map.entry("fire_protection",     "Огнезащита"),
        Map.entry("flame",               "Пламя"),
        Map.entry("fortune",             "Удача"),
        Map.entry("frost_walker",        "Ледяная поступь"),
        Map.entry("impaling",            "Протыкание"),
        Map.entry("infinity",            "Бесконечность"),
        Map.entry("knockback",           "Отдача"),
        Map.entry("looting",             "Грабёж"),
        Map.entry("loyalty",             "Верность"),
        Map.entry("luck_of_the_sea",     "Морская удача"),
        Map.entry("lure",                "Приманка"),
        Map.entry("mending",             "Починка"),
        Map.entry("multishot",           "Мультивыстрел"),
        Map.entry("piercing",            "Пробивание"),
        Map.entry("power",               "Сила"),
        Map.entry("projectile_protection","Защита от снарядов"),
        Map.entry("protection",          "Защита"),
        Map.entry("punch",               "Удар"),
        Map.entry("quick_charge",        "Быстрая зарядка"),
        Map.entry("respiration",         "Дыхание"),
        Map.entry("riptide",             "Разрыв"),
        Map.entry("sharpness",           "Острота"),
        Map.entry("silk_touch",          "Шёлковое касание"),
        Map.entry("smite",               "Кара"),
        Map.entry("soul_speed",          "Скорость душ"),
        Map.entry("sweeping_edge",       "Рубящий удар"),
        Map.entry("swift_sneak",         "Быстрое подкрадывание"),
        Map.entry("thorns",              "Шипы"),
        Map.entry("unbreaking",          "Прочность"),
        Map.entry("vanishing_curse",     "Проклятие исчезновения"),
        Map.entry("wind_burst",          "Порыв ветра"),
        Map.entry("damage_arthropods",   "Гибель членистоногих"),
        Map.entry("dig_speed",           "Эффективность"),
        Map.entry("protection_explosions","Взрывозащита"),
        Map.entry("protection_fall",     "Мягкое падение"),
        Map.entry("protection_fire",     "Огнезащита"),
        Map.entry("protection_projectile","Защита от снарядов"),
        Map.entry("water_worker",        "Родство с водой"),
        Map.entry("arrow_damage",        "Сила"),
        Map.entry("arrow_fire",          "Пламя"),
        Map.entry("arrow_infinite",      "Бесконечность"),
        Map.entry("arrow_knockback",     "Удар")
    );

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    @SuppressWarnings("java:S1192")
    private static final Map<String, String> ITEM_NAMES = Map.ofEntries(
        // Potions (old format)
        Map.entry("potion",           "Зелье"),
        Map.entry("splash_potion",    "Бросаемое зелье"),
        Map.entry("lingering_potion", "Оседающее зелье"),
        Map.entry("tipped_arrow",     "Стрела с эффектом"),
        Map.entry("enchanted_book",   "Книга зачарований"),
        // Gems & ores
        Map.entry("diamond",          "Алмаз"),
        Map.entry("emerald",          "Изумруд"),
        Map.entry("amethyst_shard",   "Осколок аметиста"),
        Map.entry("quartz",           "Кварц"),
        Map.entry("prismarine_shard", "Осколок призмарина"),
        Map.entry("prismarine_crystals","Кристаллы призмарина"),
        Map.entry("iron_ingot",       "Железный слиток"),
        Map.entry("gold_ingot",       "Золотой слиток"),
        Map.entry("copper_ingot",     "Медный слиток"),
        Map.entry("netherite_ingot",  "Слиток незерита"),
        Map.entry("netherite_scrap",  "Осколок незерита"),
        Map.entry("raw_iron",         "Сырое железо"),
        Map.entry("raw_gold",         "Сырое золото"),
        Map.entry("raw_copper",       "Сырая медь"),
        Map.entry("coal",             "Уголь"),
        Map.entry("charcoal",         "Древесный уголь"),
        Map.entry("lapis_lazuli",     "Лазурит"),
        Map.entry("redstone",         "Красная пыль"),
        Map.entry("glowstone_dust",   "Пыль светокамня"),
        Map.entry("blaze_powder",     "Порошок огня"),
        Map.entry("blaze_rod",        "Стержень огня"),
        Map.entry("nether_star",      "Звезда незера"),
        Map.entry("nether_brick",     "Кирпич незера"),
        Map.entry("end_crystal",      "Кристалл края"),
        // Food
        Map.entry("bread",            "Хлеб"),
        Map.entry("apple",            "Яблоко"),
        Map.entry("golden_apple",     "Золотое яблоко"),
        Map.entry("enchanted_golden_apple","Зачарованное золотое яблоко"),
        Map.entry("cooked_beef",      "Жареная говядина"),
        Map.entry("cooked_porkchop",  "Жареная свинина"),
        Map.entry("cooked_chicken",   "Жареная курица"),
        Map.entry("cooked_mutton",    "Жареная баранина"),
        Map.entry("cooked_salmon",    "Жареный лосось"),
        Map.entry("cooked_cod",       "Жареная треска"),
        Map.entry("cooked_rabbit",    "Жареный кролик"),
        // Mob drops
        Map.entry("leather",          "Кожа"),
        Map.entry("feather",          "Перо"),
        Map.entry("bone",             "Кость"),
        Map.entry("bone_meal",        "Костная мука"),
        Map.entry("string",           "Нить"),
        Map.entry("spider_eye",       "Глаз паука"),
        Map.entry("fermented_spider_eye","Забродивший глаз паука"),
        Map.entry("gunpowder",        "Порох"),
        Map.entry("ender_pearl",      "Жемчуг края"),
        Map.entry("ender_eye",        "Oko края"),
        Map.entry("dragon_breath",    "Дыхание дракона"),
        Map.entry("ghast_tear",       "Слеза гаста"),
        Map.entry("magma_cream",      "Крем магмы"),
        Map.entry("slime_ball",       "Шарик слизи"),
        Map.entry("rabbit_foot",      "Заячья лапка"),
        Map.entry("rabbit_hide",      "Шкура кролика"),
        Map.entry("ink_sac",          "Чернильный мешочек"),
        Map.entry("glow_ink_sac",     "Светящийся чернильный мешок"),
        Map.entry("turtle_scute",     "Чешуйка черепахи"),
        Map.entry("armadillo_scute",  "Чешуйка броненосца"),
        Map.entry("wither_skeleton_skull","Голова скелета-иссушителя"),
        Map.entry("totem_of_undying", "Тотем бессмертия"),
        Map.entry("shulker_shell",    "Шкурка шалкера"),
        Map.entry("elytra",           "Элитры"),
        Map.entry("phantom_membrane","Плёнка фантома"),
        Map.entry("heart_of_the_sea", "Сердце моря"),
        Map.entry("nautilus_shell",   "Панцирь наутилуса"),
        Map.entry("trident",          "Трезубец"),
        // Farming
        Map.entry("wheat",            "Пшеница"),
        Map.entry("wheat_seeds",      "Семена пшеницы"),
        Map.entry("sugar_cane",       "Сахарный тростник"),
        Map.entry("sugar",            "Сахар"),
        Map.entry("carrot",           "Морковь"),
        Map.entry("potato",           "Картофель"),
        Map.entry("beetroot",         "Свёкла"),
        Map.entry("melon_slice",      "Долька дыни"),
        Map.entry("pumpkin",          "Тыква"),
        Map.entry("cactus",           "Кактус"),
        Map.entry("bamboo",           "Бамбук"),
        Map.entry("cocoa_beans",      "Какао-бобы"),
        // Wood
        Map.entry("oak_log",          "Дубовое бревно"),
        Map.entry("birch_log",        "Берёзовое бревно"),
        Map.entry("spruce_log",       "Еловое бревно"),
        Map.entry("jungle_log",       "Тропическое бревно"),
        Map.entry("acacia_log",       "Акациевое бревно"),
        Map.entry("dark_oak_log",     "Бревно тёмного дуба"),
        Map.entry("cherry_log",       "Вишнёвое бревно"),
        Map.entry("mangrove_log",     "Бревно мангрового дерева"),
        Map.entry("oak_planks",       "Дубовые доски"),
        Map.entry("birch_planks",     "Берёзовые доски"),
        Map.entry("spruce_planks",    "Еловые доски"),
        Map.entry("jungle_planks",    "Тропические доски"),
        Map.entry("acacia_planks",    "Акациевые доски"),
        Map.entry("dark_oak_planks",  "Доски тёмного дуба"),
        Map.entry("cherry_planks",    "Вишнёвые доски"),
        // Stones & blocks
        Map.entry("stone",            "Камень"),
        Map.entry("cobblestone",      "Булыжник"),
        Map.entry("deepslate",        "Глубинный сланец"),
        Map.entry("obsidian",         "Обсидиан"),
        Map.entry("crying_obsidian",  "Плачущий обсидиан"),
        Map.entry("ancient_debris",   "Древние обломки"),
        Map.entry("sand",             "Песок"),
        Map.entry("gravel",           "Гравий"),
        Map.entry("flint",            "Кремень"),
        Map.entry("glass",            "Стекло"),
        Map.entry("glass_pane",       "Стеклянная панель"),
        Map.entry("clay",             "Глина"),
        Map.entry("clay_ball",        "Шарик глины"),
        Map.entry("brick",            "Кирпич"),
        Map.entry("bricks",           "Кирпичи"),
        Map.entry("iron_block",       "Железный блок"),
        Map.entry("gold_block",       "Золотой блок"),
        Map.entry("diamond_block",    "Алмазный блок"),
        Map.entry("emerald_block",    "Изумрудный блок"),
        Map.entry("lapis_block",      "Блок лазурита"),
        Map.entry("redstone_block",   "Блок красного камня"),
        Map.entry("netherite_block",  "Блок незерита"),
        Map.entry("copper_block",     "Медный блок"),
        // Misc
        Map.entry("paper",            "Бумага"),
        Map.entry("book",             "Книга"),
        Map.entry("stick",            "Палка"),
        Map.entry("torch",            "Факел"),
        Map.entry("chest",            "Сундук"),
        Map.entry("barrel",           "Бочка"),
        Map.entry("crafting_table",   "Верстак"),
        Map.entry("furnace",          "Печь"),
        Map.entry("blast_furnace",    "Доменная печь"),
        Map.entry("smoker",           "Коптильня"),
        Map.entry("anvil",            "Наковальня"),
        Map.entry("grindstone",       "Точильный камень"),
        Map.entry("smithing_table",   "Кузнечный стол"),
        Map.entry("enchanting_table", "Стол зачарований"),
        Map.entry("brewing_stand",    "Стойка для зелий"),
        Map.entry("beacon",           "Маяк"),
        Map.entry("conduit",          "Кондуит"),
        Map.entry("bell",             "Колокол"),
        Map.entry("lantern",          "Фонарь"),
        Map.entry("soul_lantern",     "Душевой фонарь"),
        Map.entry("compass",          "Компас"),
        Map.entry("clock",            "Часы"),
        Map.entry("map",              "Карта"),
        Map.entry("name_tag",         "Именная бирка"),
        Map.entry("lead",             "Поводок"),
        Map.entry("saddle",           "Седло"),
        Map.entry("experience_bottle","Бутылка опыта"),
        Map.entry("ominous_bottle",   "Зловещая бутылка"),
        Map.entry("bundle",           "Мешок"),
        Map.entry("shears",           "Ножницы"),
        Map.entry("bucket",           "Ведро"),
        Map.entry("water_bucket",     "Ведро воды"),
        Map.entry("lava_bucket",      "Ведро лавы"),
        Map.entry("milk_bucket",      "Ведро молока"),
        Map.entry("axolotl_bucket",   "Ведро аксолотля"),
        Map.entry("fishing_rod",      "Удочка"),
        Map.entry("bow",              "Лук"),
        Map.entry("crossbow",         "Арбалет"),
        Map.entry("arrow",            "Стрела"),
        Map.entry("spectral_arrow",   "Призрачная стрела"),
        Map.entry("firework_rocket",  "Ракета фейерверка"),
        Map.entry("firework_star",    "Звезда фейерверка"),
        Map.entry("music_disc_13",    "Пластинка «13»"),
        Map.entry("music_disc_cat",   "Пластинка «Cat»"),
        Map.entry("music_disc_blocks","Пластинка «Blocks»"),
        Map.entry("music_disc_chirp", "Пластинка «Chirp»"),
        Map.entry("music_disc_far",   "Пластинка «Far»"),
        Map.entry("music_disc_mall",  "Пластинка «Mall»"),
        Map.entry("music_disc_mellohi","Пластинка «Mellohi»"),
        Map.entry("music_disc_stal",  "Пластинка «Stal»"),
        Map.entry("music_disc_strad", "Пластинка «Strad»"),
        Map.entry("music_disc_ward",  "Пластинка «Ward»"),
        Map.entry("music_disc_11",    "Пластинка «11»"),
        Map.entry("music_disc_wait",  "Пластинка «Wait»"),
        Map.entry("music_disc_otherside","Пластинка «Otherside»"),
        Map.entry("music_disc_pigstep","Пластинка «Pigstep»"),
        Map.entry("music_disc_5",     "Пластинка «5»"),
        Map.entry("music_disc_relic", "Пластинка «Relic»"),
        Map.entry("music_disc_creator","Пластинка «Creator»"),
        Map.entry("music_disc_precipice","Пластинка «Precipice»"),
        // Ominous bottles (special keys)
        Map.entry("ominous_bottle_1", "Зловещая бутылка I"),
        Map.entry("ominous_bottle_2", "Зловещая бутылка II"),
        Map.entry("ominous_bottle_3", "Зловещая бутылка III"),
        Map.entry("ominous_bottle_4", "Зловещая бутылка IV"),
        Map.entry("ominous_bottle_5", "Зловещая бутылка V")
    );

    private String readableKey(String key) {
        if (key == null) return "?";
        int c1 = key.indexOf(':');
        if (c1 < 0) return key.replace('_', ' ');

        String prefix = key.substring(0, c1).toLowerCase(Locale.ROOT);
        String rest   = key.substring(c1 + 1);

        // potion:night_vision, splash_potion:healing, lingering_potion:regeneration
        if (prefix.equals("potion") || prefix.equals("splash_potion") || prefix.equals("lingering_potion")) {
            String effectName = rest.split(":")[0].toLowerCase(Locale.ROOT);
            String effect = POTION_NAMES.getOrDefault(effectName, effectName.replace('_', ' '));
            return switch (prefix) {
                case "splash_potion"    -> "Бросаемое: " + effect;
                case "lingering_potion" -> "Оседающее: " + effect;
                default                 -> "Зелье: " + effect;
            };
        }

        // enchanted_book:sharpness:5
        if (prefix.equals("enchanted_book")) {
            int c2 = rest.indexOf(':');
            if (c2 >= 0) {
                String enchId = rest.substring(0, c2).toLowerCase(Locale.ROOT);
                String lvlStr = rest.substring(c2 + 1);
                String enchName = ENCHANT_NAMES.getOrDefault(enchId, enchId.replace('_', ' '));
                try {
                    int lvl = Integer.parseInt(lvlStr);
                    String roman = lvl > 0 && lvl < ROMAN.length ? ROMAN[lvl] : String.valueOf(lvl);
                    return "Книга: " + enchName + " " + roman;
                } catch (NumberFormatException ignored) {}
                return "Книга: " + enchName;
            }
            return "Книга зачарований";
        }

        // minecraft:* → lookup Russian name or capitalize English
        String lookup = ITEM_NAMES.get(prefix.equals("minecraft") ? rest.toLowerCase(Locale.ROOT) : key.toLowerCase(Locale.ROOT));
        if (lookup != null) return lookup;
        String itemId = rest.replace('_', ' ');
        return Character.toUpperCase(itemId.charAt(0)) + itemId.substring(1);
    }

    private boolean same(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }
    private String safe(String s)             { return s == null ? "" : s; }
    private String shortId(String id)         { return id == null || id.length() < 8 ? "?" : id.substring(0, 8); }
    private double round2(double v)           { return Math.round(v * 100.0) / 100.0; }
    private String money(double v)            { return String.format(Locale.US, "%.2f", round2(v)); }
}
