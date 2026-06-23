package ru.voidrp.gamesync.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class GameSyncConfig {

    private final String backendBaseUrl;
    private final String apiPrefix;
    private final String gameAuthSecret;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean backendNationSourceEnabled;
    private final boolean fallbackYmlEnabled;
    private final boolean syncEnabled;
    private final long syncPeriodTicks;
    private final boolean syncMembership;
    private final boolean syncStats;
    private final boolean statsOnlineOnly;
    private final String territorySourceMode;
    private final String territoryWorldGuardCountMode;
    private final boolean territoryWorldGuardFallbackToManual;
    private final boolean resolveOnJoin;
    private final long joinDelayTicks;
    private final boolean autoApplyOnJoin;
    private final boolean suppressDuplicateRewardUntilExpiry;
    private final boolean luckPermsEnabled;
    private final boolean applyNationMetaOnJoin;
    private final boolean reconcileNationMetaAfterSync;
    private final String luckPermsServerContext;
    private final boolean prefixEnabled;
    private final int prefixPriority;
    private final String prefixTemplate;
    private final boolean suffixEnabled;
    private final int suffixPriority;
    private final String suffixTemplate;
    private final String leaderLabel;
    private final String officerLabel;
    private final String memberLabel;
    private final boolean skinSyncEnabled;
    private final boolean skinApplyOnJoin;
    private final long skinJoinDelayTicks;
    private final boolean clearMissingSkin;
    private final boolean syncOnPlayerJoin;
    private final long playerJoinSyncDelayTicks;

    private final boolean economyMarketEnabled;
    private final long economyMarketSyncPeriodTicks;
    private final boolean economyShopGuiBridgeEnabled;
    private final boolean economyMarketRealPriceEnabled;
    private final boolean economyMarketApplyBuyPrice;
    private final boolean economyMarketApplySellPrice;
    private final boolean economyMarketPushTransactions;
    private final boolean economyMarketNotifyActionbar;
    private final boolean economyMarketNotifyChat;
    private final boolean economyMarketIgnoreOpPlayers;
    private final boolean economyMarketIgnoreCreativePlayers;
    private final boolean economyMarketRecordMultiItemTransactions;
    private final boolean economyMarketVisualSyncEnabled;
    private final long economyMarketVisualSyncPeriodTicks;
    private final int economyMarketVisualSyncMinChangesBeforeReload;
    private final int economyMarketVisualSyncMaxReloadsPerHour;
    private final int economyMarketVisualSyncMaxItemsPerCycle;
    private final double economyMarketVisualSyncMinDiffPercent;
    private final String economyMarketVisualSyncEditCommand;
    private final String economyMarketVisualSyncReloadCommand;
    private final boolean economyMarketVisualSyncRunReload;
    private final boolean nationMarketEnabled;
    private final double nationMarketFeePercent;
    private final int nationMarketListingExpireHours;
    private final int nationMarketMaxListingsPerNation;
    private final int nationMarketMaxListingsPerPlayer;
    private final boolean nationMarketAllowCustomItems;
    private final boolean nationMarketAllowEnchantedItems;
    private final boolean nationMarketAllowDamagedItems;
    private final boolean nationMarketAllowShulkerBoxes;
    private final boolean nationMarketAllowBundles;
    private final double nationMarketMinAboveServerSellMultiplier;
    private final double nationMarketWarnBelowMarketMultiplier;
    private final double nationMarketWarnAboveMarketMultiplier;
    private final double nationMarketHardMaxMarketMultiplier;
    private final long nationMarketConfirmTimeoutMs;
    private final String nationMarketGuiTitle;

    private final boolean playerMarketEnabled;
    private final int playerMarketMaxSellOrders;
    private final int playerMarketMaxBuyOrders;

    private final boolean startingBalanceEnabled;
    private final double startingBalanceAmount;
    private final long startingBalanceDelayTicks;

    private final boolean webGuiEnabled;
    private final String webGuiMenuUrl;
    private final String webGuiMarketUrl;
    private final String webGuiNationMarketUrl;
    private final String webGuiTreasuryUrl;
    private final String webGuiAllianceUrl;
    private final String webGuiBattlepassUrl;
    private final String webGuiQuestsUrl;

    private final boolean debugHttp;
    private final boolean verboseSync;
    private final ConfigurationSection rewardBundlesSection;
    private final JavaPlugin plugin;

    public GameSyncConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.backendBaseUrl = trimTrailingSlash(plugin.getConfig().getString("backend.base-url", "https://api.void-rp.ru"));
        this.apiPrefix = plugin.getConfig().getString("backend.api-prefix", "/api/v1");
        this.gameAuthSecret = plugin.getConfig().getString("backend.game-auth-secret", "");
        this.connectTimeoutMs = plugin.getConfig().getInt("backend.connect-timeout-ms", 10000);
        this.readTimeoutMs = plugin.getConfig().getInt("backend.read-timeout-ms", 20000);

        this.backendNationSourceEnabled = plugin.getConfig().getBoolean("nation-source.backend-enabled", true);
        this.fallbackYmlEnabled = plugin.getConfig().getBoolean("nation-source.fallback-yml-enabled", true);

        this.syncEnabled = plugin.getConfig().getBoolean("sync.enabled", true);
        this.syncPeriodTicks = Math.max(20L, plugin.getConfig().getLong("sync.period-seconds", 300L) * 20L);
        this.syncMembership = plugin.getConfig().getBoolean("sync.sync-membership", true);
        this.syncStats = plugin.getConfig().getBoolean("sync.sync-stats", true);
        this.statsOnlineOnly = plugin.getConfig().getBoolean("sync.stats-online-only", false);

        this.territorySourceMode = plugin.getConfig().getString("territory.source", "manual");
        this.territoryWorldGuardCountMode = plugin.getConfig().getString("territory.worldguard.count-mode", "2d");
        this.territoryWorldGuardFallbackToManual = plugin.getConfig().getBoolean("territory.worldguard.fallback_to_manual", true);

        this.resolveOnJoin = plugin.getConfig().getBoolean("referrals.resolve-on-join", true);
        this.joinDelayTicks = Math.max(1L, plugin.getConfig().getLong("referrals.join-delay-ticks", 60L));
        this.autoApplyOnJoin = plugin.getConfig().getBoolean("referrals.auto-apply-on-join", true);
        this.suppressDuplicateRewardUntilExpiry = plugin.getConfig().getBoolean("referrals.suppress-duplicate-reward-until-expiry", true);

        this.luckPermsEnabled = plugin.getConfig().getBoolean("luckperms.enabled", true);
        this.applyNationMetaOnJoin = plugin.getConfig().getBoolean("luckperms.apply-on-join", true);
        this.reconcileNationMetaAfterSync = plugin.getConfig().getBoolean("luckperms.reconcile-after-sync", true);
        this.luckPermsServerContext = plugin.getConfig().getString("luckperms.server-context", "");
        this.prefixEnabled = plugin.getConfig().getBoolean("luckperms.prefix.enabled", true);
        this.prefixPriority = plugin.getConfig().getInt("luckperms.prefix.priority", 25);
        this.prefixTemplate = plugin.getConfig().getString("luckperms.prefix.template", "&8[&b%tag%&8]&r ");
        this.suffixEnabled = plugin.getConfig().getBoolean("luckperms.suffix.enabled", true);
        this.suffixPriority = plugin.getConfig().getInt("luckperms.suffix.priority", 25);
        this.suffixTemplate = plugin.getConfig().getString("luckperms.suffix.template", " &8• &7%role%");
        this.leaderLabel = plugin.getConfig().getString("luckperms.role-labels.leader", "Лидер");
        this.officerLabel = plugin.getConfig().getString("luckperms.role-labels.officer", "Офицер");
        this.memberLabel = plugin.getConfig().getString("luckperms.role-labels.member", "Участник");

        this.skinSyncEnabled = plugin.getConfig().getBoolean("skins.enabled", true);
        this.skinApplyOnJoin = plugin.getConfig().getBoolean("skins.apply-on-join", true);
        this.skinJoinDelayTicks = Math.max(1L, plugin.getConfig().getLong("skins.join-delay-ticks", 100L));
        this.clearMissingSkin = plugin.getConfig().getBoolean("skins.clear-when-missing", true);

        this.syncOnPlayerJoin = plugin.getConfig().getBoolean("sync.sync-on-player-join", true);
        this.playerJoinSyncDelayTicks = Math.max(1L, plugin.getConfig().getLong("sync.player-join-delay-ticks", 80L));

        this.economyMarketEnabled = plugin.getConfig().getBoolean("economy-market.enabled", true);
        this.economyMarketSyncPeriodTicks = Math.max(20L, plugin.getConfig().getLong("economy-market.sync.period-seconds", 60L) * 20L);
        this.economyShopGuiBridgeEnabled = plugin.getConfig().getBoolean("economy-market.economyshopgui.enabled", true);
        this.economyMarketRealPriceEnabled = plugin.getConfig().getBoolean("economy-market.real-price.enabled", true);
        this.economyMarketApplyBuyPrice = plugin.getConfig().getBoolean("economy-market.real-price.apply-buy", true);
        this.economyMarketApplySellPrice = plugin.getConfig().getBoolean("economy-market.real-price.apply-sell", true);
        this.economyMarketPushTransactions = plugin.getConfig().getBoolean("economy-market.transactions.push", true);
        this.economyMarketNotifyActionbar = plugin.getConfig().getBoolean("economy-market.player-notify.actionbar", true);
        this.economyMarketNotifyChat = plugin.getConfig().getBoolean("economy-market.player-notify.chat-on-price-diff", false);
        this.economyMarketIgnoreOpPlayers = plugin.getConfig().getBoolean("economy-market.abuse-guard.ignore-op-players", true);
        this.economyMarketIgnoreCreativePlayers = plugin.getConfig().getBoolean("economy-market.abuse-guard.ignore-creative-players", true);
        this.economyMarketRecordMultiItemTransactions = plugin.getConfig().getBoolean("economy-market.transactions.record-multi-item-events", true);
        this.economyMarketVisualSyncEnabled = plugin.getConfig().getBoolean("economy-market.visual-sync.enabled", true);
        this.economyMarketVisualSyncPeriodTicks = Math.max(20L, plugin.getConfig().getLong("economy-market.visual-sync.period-seconds", 300L) * 20L);
        this.economyMarketVisualSyncMinChangesBeforeReload = Math.max(1, plugin.getConfig().getInt("economy-market.visual-sync.min-changes-before-reload", 1));
        this.economyMarketVisualSyncMaxReloadsPerHour = Math.max(0, plugin.getConfig().getInt("economy-market.visual-sync.max-reloads-per-hour", 12));
        this.economyMarketVisualSyncMaxItemsPerCycle = Math.max(1, plugin.getConfig().getInt("economy-market.visual-sync.max-items-per-cycle", 40));
        this.economyMarketVisualSyncMinDiffPercent = Math.max(0D, plugin.getConfig().getDouble("economy-market.visual-sync.min-diff-percent", 1.0D));
        this.economyMarketVisualSyncEditCommand = plugin.getConfig().getString("economy-market.visual-sync.edit-command", "editshop");
        this.economyMarketVisualSyncReloadCommand = plugin.getConfig().getString("economy-market.visual-sync.reload-command", "sreload");
        this.economyMarketVisualSyncRunReload = plugin.getConfig().getBoolean("economy-market.visual-sync.run-reload-command", true);

        this.nationMarketEnabled = plugin.getConfig().getBoolean("nation-market.enabled", true);
        this.nationMarketFeePercent = plugin.getConfig().getDouble("nation-market.fee-percent", 3.0D);
        this.nationMarketListingExpireHours = plugin.getConfig().getInt("nation-market.listing-expire-hours", 72);
        this.nationMarketMaxListingsPerNation = plugin.getConfig().getInt("nation-market.sell.max-listings-per-nation", 50);
        this.nationMarketMaxListingsPerPlayer = plugin.getConfig().getInt("nation-market.sell.max-listings-per-player", 15);
        this.nationMarketAllowCustomItems = plugin.getConfig().getBoolean("nation-market.item-rules.allow-custom-items", false);
        this.nationMarketAllowEnchantedItems = plugin.getConfig().getBoolean("nation-market.item-rules.allow-enchanted-items", false);
        this.nationMarketAllowDamagedItems = plugin.getConfig().getBoolean("nation-market.item-rules.allow-damaged-items", false);
        this.nationMarketAllowShulkerBoxes = plugin.getConfig().getBoolean("nation-market.item-rules.allow-shulker-boxes", false);
        this.nationMarketAllowBundles = plugin.getConfig().getBoolean("nation-market.item-rules.allow-bundles", false);
        this.nationMarketMinAboveServerSellMultiplier = plugin.getConfig().getDouble("nation-market.price-guard.min-above-server-sell-multiplier", 1.25D);
        this.nationMarketWarnBelowMarketMultiplier = plugin.getConfig().getDouble("nation-market.price-guard.warn-below-market-multiplier", 0.65D);
        this.nationMarketWarnAboveMarketMultiplier = plugin.getConfig().getDouble("nation-market.price-guard.warn-above-market-multiplier", 2.5D);
        this.nationMarketHardMaxMarketMultiplier = plugin.getConfig().getDouble("nation-market.price-guard.hard-max-market-multiplier", 10.0D);
        this.nationMarketConfirmTimeoutMs = Math.max(5L, plugin.getConfig().getLong("nation-market.price-guard.confirm-timeout-seconds", 60L)) * 1000L;
        this.nationMarketGuiTitle = plugin.getConfig().getString("nation-market.gui.title", "Рынок государств");

        this.playerMarketEnabled = plugin.getConfig().getBoolean("player-market.enabled", true);
        this.playerMarketMaxSellOrders = plugin.getConfig().getInt("player-market.max-sell-orders", 50);
        this.playerMarketMaxBuyOrders = plugin.getConfig().getInt("player-market.max-buy-orders", 50);

        this.startingBalanceEnabled = plugin.getConfig().getBoolean("starting-balance.enabled", false);
        this.startingBalanceAmount = plugin.getConfig().getDouble("starting-balance.amount", 1000.0);
        this.startingBalanceDelayTicks = Math.max(1L, plugin.getConfig().getLong("starting-balance.delay-ticks", 60L));

        this.webGuiEnabled = plugin.getConfig().getBoolean("webgui.enabled", false);
        this.webGuiMenuUrl = plugin.getConfig().getString("webgui.urls.menu", "https://void-rp.ru/game-ui/menu");
        this.webGuiMarketUrl = plugin.getConfig().getString("webgui.urls.market", "https://void-rp.ru/game-ui/market");
        this.webGuiNationMarketUrl = plugin.getConfig().getString("webgui.urls.nation-market", "https://void-rp.ru/game-ui/nmarket");
        this.webGuiTreasuryUrl = plugin.getConfig().getString("webgui.urls.treasury", "https://void-rp.ru/game-ui/treasury");
        this.webGuiAllianceUrl = plugin.getConfig().getString("webgui.urls.alliance", "https://void-rp.ru/game-ui/alliance");
        this.webGuiBattlepassUrl = plugin.getConfig().getString("webgui.urls.battlepass", "https://void-rp.ru/game-ui/battlepass");
        this.webGuiQuestsUrl = plugin.getConfig().getString("webgui.urls.quests", "https://void-rp.ru/game-ui/quests");

        this.debugHttp = plugin.getConfig().getBoolean("debug.log-http", false);
        this.verboseSync = plugin.getConfig().getBoolean("debug.verbose-sync", false);

        this.rewardBundlesSection = plugin.getConfig().getConfigurationSection("reward-bundles");
    }

    public String getBackendBaseUrl() { return backendBaseUrl; }
    public String getApiPrefix() { return apiPrefix; }
    public String getGameAuthSecret() { return gameAuthSecret; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public boolean isBackendNationSourceEnabled() { return backendNationSourceEnabled; }
    public boolean isFallbackYmlEnabled() { return fallbackYmlEnabled; }
    public boolean isSyncEnabled() { return syncEnabled; }
    public long getSyncPeriodTicks() { return syncPeriodTicks; }
    public boolean isSyncMembership() { return syncMembership; }
    public boolean isSyncStats() { return syncStats; }
    public boolean isStatsOnlineOnly() { return statsOnlineOnly; }
    public String getTerritorySourceMode() { return territorySourceMode; }
    public String getTerritoryWorldGuardCountMode() { return territoryWorldGuardCountMode; }
    public boolean isTerritoryWorldGuardFallbackToManual() { return territoryWorldGuardFallbackToManual; }
    public boolean isResolveOnJoin() { return resolveOnJoin; }
    public long getJoinDelayTicks() { return joinDelayTicks; }
    public boolean isAutoApplyOnJoin() { return autoApplyOnJoin; }
    public boolean isSuppressDuplicateRewardUntilExpiry() { return suppressDuplicateRewardUntilExpiry; }
    public boolean isLuckPermsEnabled() { return luckPermsEnabled; }
    public boolean isApplyNationMetaOnJoin() { return applyNationMetaOnJoin; }
    public boolean isReconcileNationMetaAfterSync() { return reconcileNationMetaAfterSync; }
    public String getLuckPermsServerContext() { return luckPermsServerContext; }
    public boolean isPrefixEnabled() { return prefixEnabled; }
    public int getPrefixPriority() { return prefixPriority; }
    public String getPrefixTemplate() { return prefixTemplate; }
    public boolean isSuffixEnabled() { return suffixEnabled; }
    public int getSuffixPriority() { return suffixPriority; }
    public String getSuffixTemplate() { return suffixTemplate; }
    public String getLeaderLabel() { return leaderLabel; }
    public String getOfficerLabel() { return officerLabel; }
    public String getMemberLabel() { return memberLabel; }
    public boolean isSkinSyncEnabled() { return skinSyncEnabled; }
    public boolean isSkinApplyOnJoin() { return skinApplyOnJoin; }
    public long getSkinJoinDelayTicks() { return skinJoinDelayTicks; }
    public boolean isClearMissingSkin() { return clearMissingSkin; }
    public boolean isSyncOnPlayerJoin() { return syncOnPlayerJoin; }
    public long getPlayerJoinSyncDelayTicks() { return playerJoinSyncDelayTicks; }

    public boolean isEconomyMarketEnabled() { return economyMarketEnabled; }
    public long getEconomyMarketSyncPeriodTicks() { return economyMarketSyncPeriodTicks; }
    public boolean isEconomyShopGuiBridgeEnabled() { return economyShopGuiBridgeEnabled; }
    public boolean isEconomyMarketRealPriceEnabled() { return economyMarketRealPriceEnabled; }
    public boolean isEconomyMarketApplyBuyPrice() { return economyMarketApplyBuyPrice; }
    public boolean isEconomyMarketApplySellPrice() { return economyMarketApplySellPrice; }
    public boolean isEconomyMarketPushTransactions() { return economyMarketPushTransactions; }
    public boolean isEconomyMarketNotifyActionbar() { return economyMarketNotifyActionbar; }
    public boolean isEconomyMarketNotifyChat() { return economyMarketNotifyChat; }
    public boolean isEconomyMarketIgnoreOpPlayers() { return economyMarketIgnoreOpPlayers; }
    public boolean isEconomyMarketIgnoreCreativePlayers() { return economyMarketIgnoreCreativePlayers; }
    public boolean isEconomyMarketRecordMultiItemTransactions() { return economyMarketRecordMultiItemTransactions; }
    public boolean isEconomyMarketVisualSyncEnabled() { return economyMarketVisualSyncEnabled; }
    public long getEconomyMarketVisualSyncPeriodTicks() { return economyMarketVisualSyncPeriodTicks; }
    public int getEconomyMarketVisualSyncMinChangesBeforeReload() { return economyMarketVisualSyncMinChangesBeforeReload; }
    public int getEconomyMarketVisualSyncMaxReloadsPerHour() { return economyMarketVisualSyncMaxReloadsPerHour; }
    public int getEconomyMarketVisualSyncMaxItemsPerCycle() { return economyMarketVisualSyncMaxItemsPerCycle; }
    public double getEconomyMarketVisualSyncMinDiffPercent() { return economyMarketVisualSyncMinDiffPercent; }
    public String getEconomyMarketVisualSyncEditCommand() { return economyMarketVisualSyncEditCommand; }
    public String getEconomyMarketVisualSyncReloadCommand() { return economyMarketVisualSyncReloadCommand; }
    public boolean isEconomyMarketVisualSyncRunReload() { return economyMarketVisualSyncRunReload; }
    public boolean isNationMarketEnabled() { return nationMarketEnabled; }
    public double getNationMarketFeePercent() { return nationMarketFeePercent; }
    public int getNationMarketListingExpireHours() { return nationMarketListingExpireHours; }
    public int getNationMarketMaxListingsPerNation() { return nationMarketMaxListingsPerNation; }
    public int getNationMarketMaxListingsPerPlayer() { return nationMarketMaxListingsPerPlayer; }
    public boolean isNationMarketAllowCustomItems() { return nationMarketAllowCustomItems; }
    public boolean isNationMarketAllowEnchantedItems() { return nationMarketAllowEnchantedItems; }
    public boolean isNationMarketAllowDamagedItems() { return nationMarketAllowDamagedItems; }
    public boolean isNationMarketAllowShulkerBoxes() { return nationMarketAllowShulkerBoxes; }
    public boolean isNationMarketAllowBundles() { return nationMarketAllowBundles; }
    public double getNationMarketMinAboveServerSellMultiplier() { return nationMarketMinAboveServerSellMultiplier; }
    public double getNationMarketWarnBelowMarketMultiplier() { return nationMarketWarnBelowMarketMultiplier; }
    public double getNationMarketWarnAboveMarketMultiplier() { return nationMarketWarnAboveMarketMultiplier; }
    public double getNationMarketHardMaxMarketMultiplier() { return nationMarketHardMaxMarketMultiplier; }
    public long getNationMarketConfirmTimeoutMs() { return nationMarketConfirmTimeoutMs; }
    public String getNationMarketGuiTitle() { return nationMarketGuiTitle; }

    public boolean isPlayerMarketEnabled() { return playerMarketEnabled; }
    public int getPlayerMarketMaxSellOrders() { return playerMarketMaxSellOrders; }
    public int getPlayerMarketMaxBuyOrders() { return playerMarketMaxBuyOrders; }

    public boolean isStartingBalanceEnabled() { return startingBalanceEnabled; }
    public double getStartingBalanceAmount() { return startingBalanceAmount; }
    public long getStartingBalanceDelayTicks() { return startingBalanceDelayTicks; }

    public boolean isWebGuiEnabled() { return webGuiEnabled; }
    public String getWebGuiMenuUrl() { return webGuiMenuUrl; }
    public String getWebGuiMarketUrl() { return webGuiMarketUrl; }
    public String getWebGuiNationMarketUrl() { return webGuiNationMarketUrl; }
    public String getWebGuiTreasuryUrl() { return webGuiTreasuryUrl; }
    public String getWebGuiAllianceUrl() { return webGuiAllianceUrl; }
    public String getWebGuiBattlepassUrl() { return webGuiBattlepassUrl; }
    public String getWebGuiQuestsUrl() { return webGuiQuestsUrl; }

    public boolean isDebugHttp() { return debugHttp; }
    public boolean isVerboseSync() { return verboseSync; }
    public ConfigurationSection getRewardBundlesSection() { return rewardBundlesSection; }

    public java.util.List<String> getRewardCommands(String bundleKey) {
        if (bundleKey == null || bundleKey.isBlank()) return java.util.List.of();
        String path = "reward-bundles." + bundleKey + ".commands";
        java.util.List<String> commands = plugin.getConfig().getStringList(path);
        if (commands == null || commands.isEmpty()) return java.util.List.of();
        return java.util.List.copyOf(commands);
    }

    public String roleDisplay(String role) {
        if (role == null || role.isBlank()) return "Участник";
        String normalized = role.trim().toLowerCase(java.util.Locale.ROOT);
        String configured = plugin.getConfig().getString("luckperms.role-labels." + normalized);
        if (configured != null && !configured.isBlank()) return configured;
        return switch (normalized) {
            case "leader" -> "Лидер";
            case "officer" -> "Офицер";
            case "member" -> "Участник";
            default -> role;
        };
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
