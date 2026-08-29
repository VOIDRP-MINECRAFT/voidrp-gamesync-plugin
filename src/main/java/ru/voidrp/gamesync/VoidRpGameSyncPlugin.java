package ru.voidrp.gamesync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import net.milkbowl.vault.economy.Economy;
import ru.voidrp.gamesync.command.MarketPriceCommand;
import ru.voidrp.gamesync.command.NationMarketCommand;
import ru.voidrp.gamesync.command.NationSetCapitalCommand;
import ru.voidrp.gamesync.command.PlayerMarketCommand;
import ru.voidrp.gamesync.command.PlayerMarketShopCommand;
import ru.voidrp.gamesync.command.PlayerNationDonateCommand;
import ru.voidrp.gamesync.command.PlayerNationTreasuryCommand;
import ru.voidrp.gamesync.command.PlayerNationTreasuryHistoryCommand;
import ru.voidrp.gamesync.command.PlayerNationWithdrawCommand;
import ru.voidrp.gamesync.command.AllianceCommand;
import ru.voidrp.gamesync.command.NationResearchCommand;
import ru.voidrp.gamesync.command.VoidRpAdminCommand;
import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.config.NationRegistry;
import ru.voidrp.gamesync.listener.AlliancePvpListener;
import ru.voidrp.gamesync.listener.GatherBonusListener;
import ru.voidrp.gamesync.listener.ModdedShopDisplayListener;
import ru.voidrp.gamesync.listener.NationMarketGuiListener;
import ru.voidrp.gamesync.listener.PlayerJoinRewardListener;
import ru.voidrp.gamesync.listener.PlayerMarketGuiListener;
import ru.voidrp.gamesync.listener.PlayerMarketListener;
import ru.voidrp.gamesync.listener.TabListListener;
import ru.voidrp.gamesync.listener.TierTrackingListener;
import ru.voidrp.gamesync.listener.WebGuiPlayerJoinListener;
import ru.voidrp.gamesync.service.AllianceCacheService;
import ru.voidrp.gamesync.service.NationResearchEffectService;
import ru.voidrp.gamesync.service.BackendClient;
import ru.voidrp.gamesync.service.PlayerMarketGuiService;
import ru.voidrp.gamesync.service.PlayerMarketService;
import ru.voidrp.gamesync.service.EconomyMarketCache;
import ru.voidrp.gamesync.service.EconomyMarketSyncService;
import ru.voidrp.gamesync.service.EconomyShopGuiBridgeService;
import ru.voidrp.gamesync.service.EconomyShopVisualSyncService;
import ru.voidrp.gamesync.service.ItemStackSnapshotService;
import ru.voidrp.gamesync.service.LuckPermsNationMetaService;
import ru.voidrp.gamesync.service.NationMarketConfirmService;
import ru.voidrp.gamesync.service.NationMarketGuiService;
import ru.voidrp.gamesync.service.NationMarketInventoryService;
import ru.voidrp.gamesync.service.NationSyncService;
import ru.voidrp.gamesync.service.ReferralRewardService;
import ru.voidrp.gamesync.service.RewardCacheService;
import ru.voidrp.gamesync.service.DynmapMarkerService;
import ru.voidrp.gamesync.service.DynmapWorldGuardStyleService;
import ru.voidrp.gamesync.service.SkinCommandService;
import ru.voidrp.gamesync.service.ModdedShopItemFixupService;
import ru.voidrp.gamesync.service.SyncScheduler;
import ru.voidrp.gamesync.service.TerritoryPointsResolver;
import ru.voidrp.gamesync.service.WebActionPollService;
import ru.voidrp.gamesync.service.TikTokRewardService;
import ru.voidrp.gamesync.service.WebGuiBridgeService;
import ru.voidrp.gamesync.store.PluginDataStore;

public final class VoidRpGameSyncPlugin extends JavaPlugin {

    private GameSyncConfig gameSyncConfig;
    private BackendClient backendClient;
    private NationRegistry nationRegistry;
    private PluginDataStore dataStore;
    private RewardCacheService rewardCacheService;
    private ReferralRewardService referralRewardService;
    private LuckPermsNationMetaService luckPermsNationMetaService;
    private TerritoryPointsResolver territoryPointsResolver;
    private NationSyncService nationSyncService;
    private SyncScheduler syncScheduler;
    private SkinCommandService skinCommandService;
    private DynmapMarkerService dynmapMarkerService;
    private DynmapWorldGuardStyleService dynmapWgStyleService;
    private Economy economy;

    private TabListListener tabListListener;

    private EconomyMarketCache economyMarketCache;
    private EconomyMarketSyncService economyMarketSyncService;
    private EconomyShopGuiBridgeService economyShopGuiBridgeService;
    private EconomyShopVisualSyncService economyShopVisualSyncService;
    private ModdedShopItemFixupService moddedShopItemFixupService;
    private ItemStackSnapshotService itemStackSnapshotService;
    private NationMarketInventoryService nationMarketInventoryService;
    private NationMarketConfirmService nationMarketConfirmService;
    private NationMarketGuiService nationMarketGuiService;
    private AllianceCacheService allianceCacheService;
    private NationResearchEffectService nationResearchEffectService;
    private PlayerMarketService playerMarketService;
    private PlayerMarketGuiService playerMarketGuiService;
    private WebGuiBridgeService webGuiBridgeService;
    private WebActionPollService webActionPollService;
    private TikTokRewardService tikTokRewardService;

    @Override
    public void onEnable() {
        installNoiseFilter();
        saveDefaultConfig();
        saveResourceIfMissing("nations.yml");
        saveResourceIfMissing("data.yml");
        saveResourceIfMissing("tiktok_rewards.yml");

        setupEconomy();
        buildServices();
        registerCommands();
        registerListeners();

        if (gameSyncConfig.isSyncEnabled()) {
            syncScheduler.start();
        }
        if (gameSyncConfig.isEconomyMarketEnabled()) {
            economyMarketSyncService.start();
            economyShopGuiBridgeService.registerIfAvailable();
            economyShopVisualSyncService.start();
            this.moddedShopItemFixupService = new ModdedShopItemFixupService(this);
            moddedShopItemFixupService.scheduleFixup();
        }

        allianceCacheService.start();
        nationResearchEffectService.start();

        if (gameSyncConfig.isWebGuiEnabled()) {
            webActionPollService.start();
        }

        tikTokRewardService.start();

        getLogger().info("VoidRpGameSync enabled.");
    }

    @Override
    public void onDisable() {
        if (syncScheduler != null) {
            syncScheduler.stop();
        }
        if (economyMarketSyncService != null) {
            economyMarketSyncService.stop();
        }
        if (economyShopVisualSyncService != null) {
            economyShopVisualSyncService.stop();
        }
        if (dataStore != null) {
            dataStore.saveNow();
        }
        if (allianceCacheService != null) {
            allianceCacheService.stop();
        }
        if (nationResearchEffectService != null) {
            nationResearchEffectService.stop();
        }
        if (webActionPollService != null) {
            webActionPollService.stop();
        }
        if (tikTokRewardService != null) {
            tikTokRewardService.stop();
        }
        if (playerMarketGuiService != null) {
            playerMarketGuiService.closeAll();
        }
        getLogger().info("VoidRpGameSync disabled.");
    }

    public void reloadPluginState() {
        reloadConfig();
        if (syncScheduler != null) syncScheduler.stop();
        if (economyMarketSyncService != null) economyMarketSyncService.stop();
        if (economyShopVisualSyncService != null) economyShopVisualSyncService.stop();
        if (allianceCacheService != null) allianceCacheService.stop();
        if (nationResearchEffectService != null) nationResearchEffectService.stop();
        if (webActionPollService != null) webActionPollService.stop();
        if (tikTokRewardService != null) tikTokRewardService.stop();
        buildServices();
        if (gameSyncConfig.isSyncEnabled()) syncScheduler.start();
        if (gameSyncConfig.isEconomyMarketEnabled()) {
            economyMarketSyncService.start();
            economyShopGuiBridgeService.registerIfAvailable();
            economyShopVisualSyncService.start();
            this.moddedShopItemFixupService = new ModdedShopItemFixupService(this);
            moddedShopItemFixupService.scheduleFixup();
        }
        allianceCacheService.start();
        nationResearchEffectService.start();
        if (gameSyncConfig.isWebGuiEnabled()) {
            webActionPollService.start();
        }
        tikTokRewardService.start();
    }

    private void buildServices() {
        this.gameSyncConfig = new GameSyncConfig(this);
        if (this.dataStore == null) {
            this.dataStore = new PluginDataStore(this);
        }
        this.backendClient = new BackendClient(this, gameSyncConfig);
        this.rewardCacheService = new RewardCacheService(this, dataStore);
        this.nationRegistry = new NationRegistry(this, backendClient, gameSyncConfig);
        this.territoryPointsResolver = new TerritoryPointsResolver(this, dataStore, gameSyncConfig);
        this.referralRewardService = new ReferralRewardService(this, backendClient, rewardCacheService, gameSyncConfig);
        this.luckPermsNationMetaService = new LuckPermsNationMetaService(this, nationRegistry, dataStore, gameSyncConfig);
        this.dynmapMarkerService = new DynmapMarkerService(this);
        this.dynmapWgStyleService = new DynmapWorldGuardStyleService(this);
        this.dynmapMarkerService.initialize();
        this.nationSyncService = new NationSyncService(this, backendClient, nationRegistry, dataStore, economy, gameSyncConfig, territoryPointsResolver, dynmapMarkerService, dynmapWgStyleService);
        this.syncScheduler = new SyncScheduler(this, nationSyncService, luckPermsNationMetaService, gameSyncConfig);
        this.skinCommandService = new SkinCommandService(this);

        this.economyMarketCache = new EconomyMarketCache();
        this.economyMarketSyncService = new EconomyMarketSyncService(this, economyMarketCache);
        this.economyShopGuiBridgeService = new EconomyShopGuiBridgeService(this);
        this.economyShopVisualSyncService = new EconomyShopVisualSyncService(this, economyMarketCache);
        this.itemStackSnapshotService = new ItemStackSnapshotService();
        this.nationMarketInventoryService = new NationMarketInventoryService(this);
        this.nationMarketConfirmService = new NationMarketConfirmService();
        this.nationMarketGuiService = new NationMarketGuiService(this, itemStackSnapshotService, nationMarketInventoryService);
        this.allianceCacheService = new AllianceCacheService(this, backendClient);
        this.nationResearchEffectService = new NationResearchEffectService(this);
        this.playerMarketService = new PlayerMarketService(this);
        this.playerMarketGuiService = new PlayerMarketGuiService(this);
        this.webGuiBridgeService = new WebGuiBridgeService(this);
        this.webActionPollService = new WebActionPollService(this);
        this.tikTokRewardService = new TikTokRewardService(this);
    }

    private void registerCommands() {
        VoidRpAdminCommand adminCommand = new VoidRpAdminCommand(this);
        registerCommand("vrgs", adminCommand, adminCommand);

        PlayerNationDonateCommand donateCommand = new PlayerNationDonateCommand(this);
        registerCommand("nationdonate", donateCommand, donateCommand);
        registerCommand("ndonate", donateCommand, donateCommand);

        PlayerNationTreasuryCommand treasuryCommand = new PlayerNationTreasuryCommand(this);
        registerCommand("nationtreasury", treasuryCommand, treasuryCommand);
        registerCommand("ntreasury", treasuryCommand, treasuryCommand);

        PlayerNationTreasuryHistoryCommand treasuryHistoryCommand = new PlayerNationTreasuryHistoryCommand(this);
        registerCommand("nationtreasuryhistory", treasuryHistoryCommand, treasuryHistoryCommand);
        registerCommand("ntreasuryhistory", treasuryHistoryCommand, treasuryHistoryCommand);

        PlayerNationWithdrawCommand withdrawCommand = new PlayerNationWithdrawCommand(this);
        registerCommand("nationwithdraw", withdrawCommand, withdrawCommand);
        registerCommand("nwithdraw", withdrawCommand, withdrawCommand);

        MarketPriceCommand marketPriceCommand = new MarketPriceCommand(this);
        registerCommand("marketprice", marketPriceCommand, marketPriceCommand);
        registerCommand("mprice", marketPriceCommand, marketPriceCommand);
        registerCommand("price", marketPriceCommand, marketPriceCommand);

        NationMarketCommand nationMarketCommand = new NationMarketCommand(this);
        registerCommand("nmarket", nationMarketCommand, nationMarketCommand);
        registerCommand("nationmarket", nationMarketCommand, nationMarketCommand);
        registerCommand("nm", nationMarketCommand, nationMarketCommand);

        NationSetCapitalCommand setCapitalCommand = new NationSetCapitalCommand(this);
        registerCommand("nsetcapital", setCapitalCommand, setCapitalCommand);

        AllianceCommand allianceCommand = new AllianceCommand(this);
        registerCommand("alliance", allianceCommand, allianceCommand);
        registerCommand("ally", allianceCommand, allianceCommand);

        NationResearchCommand researchCommand = new NationResearchCommand(this);
        registerCommand("nres", researchCommand, researchCommand);
        registerCommand("nresearch", researchCommand, researchCommand);

        PlayerMarketCommand playerMarketCommand = new PlayerMarketCommand(this);
        registerCommand("pm", playerMarketCommand, playerMarketCommand);
        registerCommand("pmarket", playerMarketCommand, playerMarketCommand);
        registerCommand("playermarket", playerMarketCommand, playerMarketCommand);

        PlayerMarketShopCommand shopCommand = new PlayerMarketShopCommand(this);
        registerCommand("shop", shopCommand, shopCommand);
        // Also forcefully override /shop in case EconomyShopGUI registered it first
        overrideShopCommand(shopCommand);
    }

    private void overrideShopCommand(PlayerMarketShopCommand shopCommand) {
        try {
            java.lang.reflect.Field cmdMapField = getServer().getClass().getDeclaredField("commandMap");
            cmdMapField.setAccessible(true);
            org.bukkit.command.SimpleCommandMap commandMap =
                    (org.bukkit.command.SimpleCommandMap) cmdMapField.get(getServer());
            org.bukkit.command.Command existing = commandMap.getCommand("shop");
            if (existing instanceof org.bukkit.command.PluginCommand pc) {
                pc.setExecutor(shopCommand);
                pc.setTabCompleter(shopCommand);
                getLogger().info("[PlayerMarket] Overrode /shop command executor.");
            }
        } catch (Exception e) {
            getLogger().warning("[PlayerMarket] Could not override /shop: " + e.getMessage());
        }
    }

    private void registerCommand(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(completer);
        }
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerJoinRewardListener(this), this);
        Bukkit.getPluginManager().registerEvents(new NationMarketGuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerMarketGuiListener(this), this);
        PlayerMarketCommand pmCmd = new PlayerMarketCommand(this);
        Bukkit.getPluginManager().registerEvents(new PlayerMarketListener(this, pmCmd), this);
        Bukkit.getPluginManager().registerEvents(new TierTrackingListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AlliancePvpListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GatherBonusListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ru.voidrp.gamesync.listener.BlockStatsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ru.voidrp.gamesync.listener.CombatPlaytimeStatsListener(this), this);
        Bukkit.getPluginManager().registerEvents(
                new ModdedShopDisplayListener(this, economyShopGuiBridgeService.getModdedShopConfig()), this);
        this.tabListListener = new TabListListener(this);
        Bukkit.getPluginManager().registerEvents(tabListListener, this);
        Bukkit.getPluginManager().registerEvents(new WebGuiPlayerJoinListener(this), this);
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found. Economy-dependent features may be limited.");
            this.economy = null;
            return;
        }

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("No Economy provider found via Vault.");
            this.economy = null;
            return;
        }

        this.economy = provider.getProvider();
        if (this.economy == null) {
            getLogger().warning("Economy provider resolved as null.");
        }
    }

    public GameSyncConfig getGameSyncConfig() {
        return gameSyncConfig;
    }

    public BackendClient getBackendClient() {
        return backendClient;
    }

    public TikTokRewardService getTikTokRewardService() {
        return tikTokRewardService;
    }

    public NationRegistry getNationRegistry() {
        return nationRegistry;
    }

    public PluginDataStore getDataStore() {
        return dataStore;
    }

    public RewardCacheService getRewardCacheService() {
        return rewardCacheService;
    }

    public ReferralRewardService getReferralRewardService() {
        return referralRewardService;
    }

    public LuckPermsNationMetaService getLuckPermsNationMetaService() {
        return luckPermsNationMetaService;
    }

    public TerritoryPointsResolver getTerritoryPointsResolver() {
        return territoryPointsResolver;
    }

    public NationSyncService getNationSyncService() {
        return nationSyncService;
    }

    public SkinCommandService getSkinCommandService() {
        return skinCommandService;
    }

    public Economy getEconomy() {
        return economy;
    }

    public EconomyMarketCache getEconomyMarketCache() {
        return economyMarketCache;
    }

    public EconomyMarketSyncService getEconomyMarketSyncService() {
        return economyMarketSyncService;
    }

    public EconomyShopGuiBridgeService getEconomyShopGuiBridgeService() {
        return economyShopGuiBridgeService;
    }

    public EconomyShopVisualSyncService getEconomyShopVisualSyncService() {
        return economyShopVisualSyncService;
    }

    public ModdedShopItemFixupService getModdedShopItemFixupService() {
        return moddedShopItemFixupService;
    }

    public ItemStackSnapshotService getItemStackSnapshotService() {
        return itemStackSnapshotService;
    }

    public NationMarketInventoryService getNationMarketInventoryService() {
        return nationMarketInventoryService;
    }

    public NationMarketConfirmService getNationMarketConfirmService() {
        return nationMarketConfirmService;
    }

    public NationMarketGuiService getNationMarketGuiService() {
        return nationMarketGuiService;
    }

    public NationResearchEffectService getNationResearchEffectService() {
        return nationResearchEffectService;
    }

    public AllianceCacheService getAllianceCacheService() {
        return allianceCacheService;
    }

    public PlayerMarketService getPlayerMarketService() {
        return playerMarketService;
    }

    public PlayerMarketGuiService getPlayerMarketGuiService() {
        return playerMarketGuiService;
    }

    public WebGuiBridgeService getWebGuiBridgeService() {
        return webGuiBridgeService;
    }

    public WebActionPollService getWebActionPollService() {
        return webActionPollService;
    }

    private void saveResourceIfMissing(String name) {
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void installNoiseFilter() {
        try {
            org.apache.logging.log4j.core.Logger root =
                (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
            root.addFilter(new AbstractFilter() {
                @Override
                public Result filter(LogEvent event) {
                    String msg = event.getMessage().getFormattedMessage();
                    if (msg.contains("InventoryClickEvent") &&
                            (msg.contains("WorldGuard") || msg.contains("LagFixer"))) {
                        return Result.DENY;
                    }
                    if (msg.contains("greater than inventory slot count")) {
                        return Result.DENY;
                    }
                    return Result.NEUTRAL;
                }
            });
            getLogger().info("Noise filter installed (WorldGuard/LagFixer inventory slot errors).");
        } catch (Exception e) {
            getLogger().warning("Could not install noise filter: " + e.getMessage());
        }
    }
}
