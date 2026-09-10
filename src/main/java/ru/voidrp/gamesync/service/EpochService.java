package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.VoidRpGameSyncPlugin;

/**
 * Эпохи прогрессии: игрок «открывает» эпоху, впервые получив её ключевой предмет.
 *
 * <p><b>Почему не только событие крафта.</b> Раньше эпохи ловились исключительно
 * через {@code CraftItemEvent}, а он срабатывает только на ВАНИЛЬНОМ верстаке.
 * Ключевые предметы этого пака делаются в модовых мультиблоках (fusion crafting
 * Draconic, Extreme Crafting Table у Avaritia, ассемблеры MI), поэтому верхние
 * эпохи не открывались никогда. Теперь основной способ — периодический осмотр
 * инвентаря онлайн-игроков: он ловит предмет независимо от того, скрафтили его,
 * выбили, купили или получили в подарок. Событие крафта осталось как быстрый
 * путь, чтобы объявление появлялось сразу, а не в течение интервала.
 *
 * <p>Список эпох задаётся в config.yml (секция {@code epochs}), а не в коде:
 * состав пака меняется, и переcборка джарника ради переименования эпохи не нужна.
 */
public final class EpochService {

    /** Одна эпоха: ключ хранения, предмет-гейт, заголовок и ветка прогрессии. */
    public record Epoch(String key, String item, String title, String branch) { }

    private final VoidRpGameSyncPlugin plugin;
    private final List<Epoch> epochs = new ArrayList<>();
    /** item id → эпоха; для быстрой проверки стака без обхода всего списка. */
    private final Map<String, Epoch> byItem = new LinkedHashMap<>();
    /** NAMESPACE_ITEM → эпоха; запасной путь для сборок Mohist без NamespacedKey. */
    private final Map<String, Epoch> byMaterialName = new LinkedHashMap<>();
    private int taskId = -1;

    public EpochService(VoidRpGameSyncPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        epochs.clear();
        byItem.clear();
        byMaterialName.clear();
        var section = plugin.getConfig().getConfigurationSection("epochs.list");
        if (section == null) {
            plugin.getLogger().info("[Epochs] Секция epochs.list не задана — эпохи выключены.");
            return;
        }
        for (String key : section.getKeys(false)) {
            String item = section.getString(key + ".item", "");
            String title = section.getString(key + ".title", key);
            String branch = section.getString(key + ".branch", "main");
            if (item.isBlank()) {
                plugin.getLogger().warning("[Epochs] У эпохи '" + key + "' не задан item — пропущена.");
                continue;
            }
            Epoch epoch = new Epoch(key, item.toLowerCase(Locale.ROOT), title, branch);
            epochs.add(epoch);
            byItem.put(epoch.item(), epoch);
            byMaterialName.put(materialName(epoch.item()), epoch);
        }
        plugin.getLogger().info("[Epochs] Загружено эпох: " + epochs.size());
    }

    public List<Epoch> getEpochs() {
        return List.copyOf(epochs);
    }

    /** Запускает периодический осмотр инвентарей. */
    public void start() {
        stop();
        if (epochs.isEmpty() || !plugin.getConfig().getBoolean("epochs.enabled", true)) {
            return;
        }
        long period = Math.max(100L, plugin.getConfig().getLong("epochs.scan-interval-ticks", 600L));
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::scanOnlinePlayers, period, period)
                .getTaskId();
        plugin.getLogger().info("[Epochs] Осмотр инвентарей каждые " + period + " тиков.");
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void scanOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                scan(player);
            } catch (Exception e) {
                plugin.getLogger().warning("[Epochs] Осмотр " + player.getName() + " не удался: " + e.getMessage());
            }
        }
    }

    /** Проверяет весь инвентарь игрока (включая броню и вторую руку). */
    public void scan(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            checkStack(player, stack);
        }
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            checkStack(player, stack);
        }
        checkStack(player, player.getInventory().getItemInOffHand());
        retryUnreported(player);
    }

    private void checkStack(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        Epoch epoch = byItem.get(itemId(stack));
        if (epoch == null) {
            epoch = byMaterialName.get(stack.getType().name());
        }
        if (epoch != null) {
            unlock(player, epoch);
        }
    }

    /** Namespaced-id стака. На Mohist предметы модов имеют собственный Material. */
    private static String itemId(ItemStack stack) {
        NamespacedKey key = stack.getType().getKey();
        return key.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Запасное имя Material вида NAMESPACE_ITEM.
     *
     * <p>Часть сборок Mohist кладёт модовый предмет в Material с таким именем
     * вместо честного NamespacedKey. Ровно эта пара проверок уже используется в
     * {@code EconomyShopGuiBridgeService.isModdedItem} и проверена на этом сервере.
     */
    private static String materialName(String itemId) {
        return itemId.toUpperCase(Locale.ROOT).replace(":", "_").replace("-", "_");
    }

    /** Быстрый путь для события крафта — объявить сразу, не дожидаясь осмотра. */
    public void onCrafted(Player player, ItemStack result) {
        checkStack(player, result);
    }

    /** Отмечает эпоху открытой; повторные вызовы игнорируются. */
    public void unlock(Player player, Epoch epoch) {
        UUID uuid = player.getUniqueId();
        var store = plugin.getDataStore();
        if (!store.getTierUnlocked(uuid, epoch.key())) {
            store.setTierUnlocked(uuid, epoch.key());
            store.saveNow();
            String name = player.getName();
            plugin.getLogger().info("[Epochs] " + name + " открыл эпоху " + epoch.key());
            Bukkit.broadcastMessage("§6[VoidRP] §f" + name + " §a" + epoch.title() + "§a!");
        }
        report(player, epoch.key());
    }

    /**
     * Отправляет анлок на бэкенд и помечает его подтверждённым только при успехе.
     *
     * <p>Разделение обязательно: объявление в чате должно случиться один раз, а
     * доставка — дожать. Раньше локальный флаг ставился до ответа, и любой отказ
     * бэкенда (недоступен, незнакомый ключ после переименования эпохи) терял
     * анлок навсегда, потому что повторной попытки не происходило.
     */
    private void report(Player player, String tierKey) {
        UUID uuid = player.getUniqueId();
        var store = plugin.getDataStore();
        if (store.isTierReported(uuid, tierKey)) {
            return;
        }
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBackendClient().reportTierUnlock(uuid.toString(), name, tierKey);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    store.setTierReported(uuid, tierKey);
                    store.saveNow();
                });
            } catch (IOException | InterruptedException e) {
                plugin.getLogger().warning("[Epochs] Бэкенд не принял " + tierKey
                        + " для " + name + ": " + e.getMessage() + " — повтор при следующем осмотре");
            }
        });
    }

    /** Дожимает отправку эпох, которые открыты локально, но не подтверждены бэкендом. */
    private void retryUnreported(Player player) {
        var store = plugin.getDataStore();
        for (String tierKey : store.getUnlockedTiers(player.getUniqueId())) {
            report(player, tierKey);
        }
    }

    /** Перечитывает секцию epochs из config.yml и перезапускает осмотр. */
    public void reload() {
        load();
        start();
    }
}
