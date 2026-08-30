package ru.voidrp.gamesync.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ru.voidrp.gamesync.config.GameSyncConfig;
import ru.voidrp.gamesync.model.GameAllianceActionResponse;
import ru.voidrp.gamesync.model.GameAllianceApplyRequest;
import ru.voidrp.gamesync.model.GameAllianceKickRequest;
import ru.voidrp.gamesync.model.GameAllianceLeaveRequest;
import ru.voidrp.gamesync.model.GameAllianceProposalListResponse;
import ru.voidrp.gamesync.model.GameAllianceVoteRequest;
import ru.voidrp.gamesync.model.GameNationDonationRequest;
import ru.voidrp.gamesync.model.GameNationListResponse;
import ru.voidrp.gamesync.model.GameNationTreasuryWithdrawRequest;
import ru.voidrp.gamesync.model.MarketPriceItem;
import ru.voidrp.gamesync.model.NationResearchEffectsResponse;
import ru.voidrp.gamesync.model.NationResearchInterestResponse;
import ru.voidrp.gamesync.model.NationResearchOverviewResponse;
import ru.voidrp.gamesync.model.NationResearchPurchaseResponse;
import ru.voidrp.gamesync.model.NationSeasonAwardResponse;
import ru.voidrp.gamesync.model.MarketPriceSnapshotResponse;
import ru.voidrp.gamesync.model.MarketTransactionPushRequest;
import ru.voidrp.gamesync.model.NationDefinition;
import ru.voidrp.gamesync.model.NationMarketCancelRequest;
import ru.voidrp.gamesync.model.NationMarketCancelResponse;
import ru.voidrp.gamesync.model.NationMarketCreateRequest;
import ru.voidrp.gamesync.model.NationMarketListing;
import ru.voidrp.gamesync.model.NationMarketListingListResponse;
import ru.voidrp.gamesync.model.NationMarketPurchaseRequest;
import ru.voidrp.gamesync.model.NationMarketPurchaseResponse;
import ru.voidrp.gamesync.model.NationMemberStatsSyncRequest;
import ru.voidrp.gamesync.model.NationStatsPayload;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderCreateRequest;
import ru.voidrp.gamesync.model.PlayerMarketBuyOrderListResponse;
import ru.voidrp.gamesync.model.PlayerMarketCancelBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCancelOrderRequest;
import ru.voidrp.gamesync.model.PlayerMarketCancelSellOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCreateBuyOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketCreateSellOrderResponse;
import ru.voidrp.gamesync.model.PlayerMarketDeliveryAckRequest;
import ru.voidrp.gamesync.model.PlayerMarketPendingDeliveriesResponse;
import ru.voidrp.gamesync.model.PlayerMarketSellOrderCreateRequest;
import ru.voidrp.gamesync.model.PlayerMarketSellOrderListResponse;
import ru.voidrp.gamesync.model.PlayerSkinResponse;
import ru.voidrp.gamesync.model.PlayerStatCacheSyncRequest;
import ru.voidrp.gamesync.model.ReferralResolveResponse;
import ru.voidrp.gamesync.model.WebActionItem;
import ru.voidrp.gamesync.model.WebActionListResponse;

public final class BackendClient {

    private final JavaPlugin plugin;
    private final GameSyncConfig config;
    private final HttpClient httpClient;
    private final Gson gson;

    public BackendClient(JavaPlugin plugin, GameSyncConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .build();
        this.gson = new GsonBuilder().create();
    }

    public GameNationListResponse fetchNationDefinitions() throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nations");
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), GameNationListResponse.class);
    }

    public void upsertNationStats(NationStatsPayload payload) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/upsert");
        postJson(url, gson.toJson(payload), "Nation stats upsert failed");
    }

    public void upsertNationMemberSnapshots(NationMemberStatsSyncRequest payload) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/member-snapshots/upsert");
        postJson(url, gson.toJson(payload), "Nation member snapshot sync failed");
    }

    public void upsertPlayerStatsCache(PlayerStatCacheSyncRequest payload) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/player-stats/upsert");
        postJson(url, gson.toJson(payload), "Player stats cache upsert failed");
    }

    public void syncNationMembership(NationDefinition definition) throws IOException, InterruptedException {
        String path = "/game-sync/nations/" + encode(definition.slug()) + "/membership";
        String url = apiUrl(path);

        String json = gson.toJson(new MembershipRequest(
                definition.leader(),
                definition.officers(),
                definition.members(),
                true
        ));

        postJson(url, json, "Nation membership sync failed");
    }

    public void donateToNationTreasury(GameNationDonationRequest payload) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/player-donate");
        postJson(url, gson.toJson(payload), "Nation treasury donation failed");
    }

    /** Push an in-game notification (HUD toast + notification center) to a player by nickname. */
    public void pushNotification(java.util.Map<String, Object> payload) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/notifications");
        postJson(url, gson.toJson(payload), "Notification push failed");
    }

    /** Whether the player wants the HUD opened on join (account setting). Defaults to true. */
    public boolean getHudAutoOpen(String nickname) {
        try {
            HttpResponse<String> response = get(apiUrl("/game-sync/player-settings?nickname=" + encode(nickname)));
            com.google.gson.JsonObject o = gson.fromJson(response.body(), com.google.gson.JsonObject.class);
            return o == null || !o.has("hud_auto_open") || o.get("hud_auto_open").getAsBoolean();
        } catch (Exception ignored) {
            return true; // fail-open: default behaviour if the backend is unreachable
        }
    }

    /** Push a player's current weekly-challenge state for display in the game-ui. */
    public void pushWeeklyChallenges(java.util.Map<String, Object> payload) throws IOException, InterruptedException {
        postJson(apiUrl("/game-sync/weekly-challenges"), gson.toJson(payload), "Weekly challenges push failed");
    }

    /** Add a finished chunk of playtime to a player's daily activity bucket for {@code day}. */
    public void pushPlaytime(String nickname, long seconds, String day) throws IOException, InterruptedException {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("minecraft_nickname", nickname);
        payload.put("seconds", seconds);
        if (day != null) {
            payload.put("day", day);
        }
        postJson(apiUrl("/game-sync/playtime"), gson.toJson(payload), "Playtime push failed");
    }

    public NationTreasuryActionResponse withdrawFromNationTreasury(GameNationTreasuryWithdrawRequest payload)
            throws IOException, InterruptedException {

        String url = apiUrl("/nation-stats/internal/player-withdraw");
        HttpResponse<String> response = postJsonForResponse(
                url,
                gson.toJson(payload),
                "Nation treasury withdraw failed"
        );
        return gson.fromJson(response.body(), NationTreasuryActionResponse.class);
    }

    public NationTreasurySummaryResponse getNationTreasurySummary(String slug) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/nations/" + encode(slug) + "/summary");
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), NationTreasurySummaryResponse.class);
    }

    public NationTreasuryTransactionListResponse getNationTreasuryTransactions(String slug) throws IOException, InterruptedException {
        String url = apiUrl("/nation-stats/internal/nations/" + encode(slug) + "/transactions");
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), NationTreasuryTransactionListResponse.class);
    }

    public PlayerSkinResponse getPlayerSkin(String minecraftNickname) throws IOException, InterruptedException {
        String path = "/server/auth/player-skin/" + encode(minecraftNickname);
        String url = apiUrl(path);
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), PlayerSkinResponse.class);
    }

    public ReferralResolveResponse resolveReferralReward(String minecraftNickname) throws IOException, InterruptedException {
        String path = "/game-sync/referrals/reward/" + encode(minecraftNickname);
        String url = apiUrl(path);
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), ReferralResolveResponse.class);
    }

    public MarketPriceSnapshotResponse fetchMarketPrices() throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/economy/prices");
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), MarketPriceSnapshotResponse.class);
    }

    public MarketPriceItem getMarketPrice(String material) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/economy/prices/" + encode(material));
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), MarketPriceItem.class);
    }

    public void pushMarketTransaction(MarketTransactionPushRequest payload) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/economy/transactions");
        postJson(url, gson.toJson(payload), "Market transaction push failed");
    }

    public MarketRecalculateResponse recalculateMarketPrices(boolean decayScores) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/economy/recalculate?decay_scores=" + decayScores);
        HttpResponse<String> response = postJsonForResponse(url, "{}", "Market recalculation failed");
        return gson.fromJson(response.body(), MarketRecalculateResponse.class);
    }

    public NationMarketListing getNationMarketListing(String listingId) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-market/listings/" + encode(listingId));
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), NationMarketListing.class);
    }

    public NationMarketListing createNationMarketListing(NationMarketCreateRequest payload) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-market/listings");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(payload), "Nation market listing create failed");
        return gson.fromJson(response.body(), NationMarketListing.class);
    }

    public NationMarketListingListResponse listNationMarketListings() throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-market/listings");
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), NationMarketListingListResponse.class);
    }

    public NationMarketListingListResponse listNationMarketListings(String nationSlug, boolean includeInactive) throws IOException, InterruptedException {
        String path = "/game-sync/nation-market/listings?nation_slug=" + encode(nationSlug) + "&include_inactive=" + includeInactive;
        HttpResponse<String> response = get(apiUrl(path));
        return gson.fromJson(response.body(), NationMarketListingListResponse.class);
    }

    public NationMarketPurchaseResponse purchaseNationMarketListing(String listingId, NationMarketPurchaseRequest payload)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-market/listings/" + encode(listingId) + "/purchase");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(payload), "Nation market purchase failed");
        return gson.fromJson(response.body(), NationMarketPurchaseResponse.class);
    }

    public NationMarketCancelResponse cancelNationMarketListing(String listingId, NationMarketCancelRequest payload)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-market/listings/" + encode(listingId) + "/cancel");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(payload), "Nation market cancel failed");
        return gson.fromJson(response.body(), NationMarketCancelResponse.class);
    }

    public void setNationCapital(String slug, int x, int z, String world) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nations/" + encode(slug) + "/capital");
        String json = gson.toJson(new NationCapitalRequest(x, z, world));
        postJson(url, json, "Nation capital update failed");
    }

    private record NationCapitalRequest(int capital_x, int capital_z, String capital_world) {}

    // ── Nation research (tech tree) endpoints ─────────────────────────────────

    public NationResearchOverviewResponse getNationResearchOverview(String minecraftNickname)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-research/overview?minecraft_nickname=" + encode(minecraftNickname));
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), NationResearchOverviewResponse.class);
    }

    public NationResearchPurchaseResponse purchaseNationResearch(String minecraftNickname, String researchKey)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-research/purchase");
        String json = gson.toJson(new ResearchPurchaseRequest(minecraftNickname, researchKey));
        HttpResponse<String> response = postJsonForResponse(url, json, "Nation research purchase failed");
        return gson.fromJson(response.body(), NationResearchPurchaseResponse.class);
    }

    public NationResearchEffectsResponse fetchNationResearchEffects() throws IOException, InterruptedException {
        HttpResponse<String> response = get(apiUrl("/game-sync/nation-research/effects"));
        return gson.fromJson(response.body(), NationResearchEffectsResponse.class);
    }

    public NationResearchInterestResponse applyNationResearchInterest() throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/nation-research/apply-interest");
        HttpResponse<String> response = postJsonForResponse(url, "{}", "Nation research interest tick failed");
        return gson.fromJson(response.body(), NationResearchInterestResponse.class);
    }

    public NationSeasonAwardResponse awardTopNations() throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/season/award-top");
        HttpResponse<String> response = postJsonForResponse(url, "{}", "Season top-nation reward tick failed");
        return gson.fromJson(response.body(), NationSeasonAwardResponse.class);
    }

    private record ResearchPurchaseRequest(String minecraft_nickname, String research_key) {}

    /** Pushes a raw daily-quest snapshot JSON (built by the DailyQuests plugin). */
    public void pushDailyQuestSnapshot(String jsonBody) throws IOException, InterruptedException {
        postJson(apiUrl("/game-sync/quests/snapshot"), jsonBody, "Daily quest snapshot push failed");
    }

    // ── Alliance endpoints ────────────────────────────────────────────────────

    public java.util.Map<String, String> fetchAlliancePvpMap() throws IOException, InterruptedException {
        HttpResponse<String> response = get(apiUrl("/game-sync/alliances/pvp-map"));
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.Map<String, String>>() {}.getType();
        return gson.fromJson(response.body(), type);
    }

    public GameAllianceActionResponse applyToAlliance(GameAllianceApplyRequest payload) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonForResponse(apiUrl("/game-sync/alliances/apply"), gson.toJson(payload), "Alliance apply failed");
        return gson.fromJson(response.body(), GameAllianceActionResponse.class);
    }

    public GameAllianceActionResponse leaveAlliance(GameAllianceLeaveRequest payload) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonForResponse(apiUrl("/game-sync/alliances/leave"), gson.toJson(payload), "Alliance leave failed");
        return gson.fromJson(response.body(), GameAllianceActionResponse.class);
    }

    public GameAllianceProposalListResponse getMyAllianceProposals(String minecraftNickname) throws IOException, InterruptedException {
        HttpResponse<String> response = get(apiUrl("/game-sync/alliances/proposals?minecraft_nickname=" + encode(minecraftNickname)));
        return gson.fromJson(response.body(), GameAllianceProposalListResponse.class);
    }

    public GameAllianceActionResponse voteAllianceProposal(String proposalId, GameAllianceVoteRequest payload) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonForResponse(apiUrl("/game-sync/alliances/proposals/" + encode(proposalId) + "/vote"), gson.toJson(payload), "Alliance vote failed");
        return gson.fromJson(response.body(), GameAllianceActionResponse.class);
    }

    public GameAllianceActionResponse proposeAllianceKick(GameAllianceKickRequest payload) throws IOException, InterruptedException {
        HttpResponse<String> response = postJsonForResponse(apiUrl("/game-sync/alliances/kick"), gson.toJson(payload), "Alliance kick proposal failed");
        return gson.fromJson(response.body(), GameAllianceActionResponse.class);
    }

    // ─────────────────────────────────────────────────────────────────────────

    // ── Player Market endpoints ───────────────────────────────────────────────

    public PlayerMarketCreateSellOrderResponse createPlayerMarketSellOrder(PlayerMarketSellOrderCreateRequest payload)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/sell-orders");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(payload), "Player market sell order create failed");
        return gson.fromJson(response.body(), PlayerMarketCreateSellOrderResponse.class);
    }

    public PlayerMarketCreateBuyOrderResponse createPlayerMarketBuyOrder(PlayerMarketBuyOrderCreateRequest payload)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/buy-orders");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(payload), "Player market buy order create failed");
        return gson.fromJson(response.body(), PlayerMarketCreateBuyOrderResponse.class);
    }

    public PlayerMarketCancelSellOrderResponse cancelPlayerMarketSellOrder(String orderId, String requesterName)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/sell-orders/" + encode(orderId) + "/cancel");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(new PlayerMarketCancelOrderRequest(requesterName)), "Player market sell order cancel failed");
        return gson.fromJson(response.body(), PlayerMarketCancelSellOrderResponse.class);
    }

    public PlayerMarketCancelBuyOrderResponse cancelPlayerMarketBuyOrder(String orderId, String requesterName)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/buy-orders/" + encode(orderId) + "/cancel");
        HttpResponse<String> response = postJsonForResponse(url, gson.toJson(new PlayerMarketCancelOrderRequest(requesterName)), "Player market buy order cancel failed");
        return gson.fromJson(response.body(), PlayerMarketCancelBuyOrderResponse.class);
    }

    public PlayerMarketPendingDeliveriesResponse getPlayerMarketPendingDeliveries(String playerName)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/pending-deliveries/" + encode(playerName));
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), PlayerMarketPendingDeliveriesResponse.class);
    }

    public void ackPlayerMarketDeliveries(String playerName, java.util.List<String> deliveryIds)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/player-market/pending-deliveries/" + encode(playerName) + "/ack");
        postJson(url, gson.toJson(new PlayerMarketDeliveryAckRequest(deliveryIds)), "Player market delivery ack failed");
    }

    public PlayerMarketSellOrderListResponse listPlayerMarketSellOrders(int limit, int offset)
            throws IOException, InterruptedException {
        String url = apiUrl("/market/player/sell-orders?status=active&limit=" + limit + "&offset=" + offset);
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), PlayerMarketSellOrderListResponse.class);
    }

    public PlayerMarketBuyOrderListResponse listPlayerMarketBuyOrders(int limit, int offset)
            throws IOException, InterruptedException {
        String url = apiUrl("/market/player/buy-orders?status=active&limit=" + limit + "&offset=" + offset);
        HttpResponse<String> response = get(url);
        return gson.fromJson(response.body(), PlayerMarketBuyOrderListResponse.class);
    }

    // ── WebGUI web-action endpoints ────────────────────────────────────────────

    public WebActionListResponse pollWebActions() throws IOException, InterruptedException {
        HttpResponse<String> response = get(apiUrl("/game-sync/market-web-actions"));
        return gson.fromJson(response.body(), WebActionListResponse.class);
    }

    public void ackWebAction(String actionId, String status, String errorMessage)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/market-web-actions/ack");
        postJson(url, gson.toJson(new WebActionAckRequest(actionId, status, errorMessage)), "WebAction ack failed");
    }

    private record WebActionAckRequest(String action_id, String status, String error_message) {}

    // ─────────────────────────────────────────────────────────────────────────

    public void reportTierUnlock(String minecraftUuid, String minecraftNickname, String tierName) throws IOException, InterruptedException {
        String url = apiUrl("/progression/internal/unlock");
        String json = gson.toJson(new TierUnlockRequest(minecraftUuid, minecraftNickname, tierName));
        postJson(url, json, "Tier unlock report failed");
    }

    private record TierUnlockRequest(String minecraft_uuid, String minecraft_nickname, String tier_name) {}

    // ── TikTok click-reward endpoints ──────────────────────────────────────────

    public ru.voidrp.gamesync.model.TikTokCampaignResponse createTikTokCampaign(String videoUrl)
            throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/tiktok/campaign");
        HttpResponse<String> response = postJsonForResponse(
                url, gson.toJson(new TikTokCampaignRequest(videoUrl, true)), "TikTok campaign create failed");
        return gson.fromJson(response.body(), ru.voidrp.gamesync.model.TikTokCampaignResponse.class);
    }

    public ru.voidrp.gamesync.model.TikTokPendingRewardsResponse pollTikTokRewards()
            throws IOException, InterruptedException {
        HttpResponse<String> response = get(apiUrl("/game-sync/tiktok/pending-rewards"));
        return gson.fromJson(response.body(), ru.voidrp.gamesync.model.TikTokPendingRewardsResponse.class);
    }

    public void ackTikTokRewards(java.util.List<String> ids) throws IOException, InterruptedException {
        String url = apiUrl("/game-sync/tiktok/pending-rewards/ack");
        postJson(url, gson.toJson(new TikTokAckRequest(ids)), "TikTok reward ack failed");
    }

    private record TikTokCampaignRequest(String video_url, boolean deactivate_previous) {}
    private record TikTokAckRequest(java.util.List<String> ids) {}

    /** Adds the optional X-Server-Slug header for explicit multi-server attribution. */
    private HttpRequest.Builder withServerSlug(HttpRequest.Builder builder) {
        String slug = config.getServerSlug();
        if (slug != null && !slug.isBlank()) {
            builder.header("X-Server-Slug", slug);
        }
        return builder;
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = withServerSlug(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .header("X-Game-Auth-Secret", config.getGameAuthSecret()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (config.isDebugHttp()) {
            plugin.getLogger().info("[HTTP] GET " + url + " -> " + response.statusCode() + " body=" + response.body());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET failed with status " + response.statusCode() + ": " + response.body());
        }

        return response;
    }

    private void postJson(String url, String json, String messagePrefix) throws IOException, InterruptedException {
        postJsonForResponse(url, json, messagePrefix);
    }

    private HttpResponse<String> postJsonForResponse(String url, String json, String messagePrefix)
            throws IOException, InterruptedException {

        HttpRequest request = withServerSlug(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-Game-Auth-Secret", config.getGameAuthSecret()))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (config.isDebugHttp()) {
            plugin.getLogger().info("[HTTP] POST " + url + " -> " + response.statusCode() + " body=" + response.body());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                String detail = parseDetailField(response.body());
                if (detail != null) {
                    throw new BackendApiException(response.statusCode(), detail);
                }
            }
            throw new IOException(messagePrefix + " with status " + response.statusCode() + ": " + response.body());
        }

        return response;
    }

    private String parseDetailField(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.google.gson.JsonObject obj = gson.fromJson(body, com.google.gson.JsonObject.class);
            if (obj != null && obj.has("detail") && obj.get("detail").isJsonPrimitive()) {
                return obj.get("detail").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String apiUrl(String path) {
        String prefix = config.getApiPrefix();
        if (prefix == null || prefix.isBlank()) {
            prefix = "";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return config.getBackendBaseUrl() + prefix + path;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record MembershipRequest(
            String leader_minecraft_nickname,
            java.util.List<String> officers,
            java.util.List<String> members,
            boolean replace_missing
    ) {}

    public static final class MarketRecalculateResponse {
        public int total;
        public int changed;
    }

    public static final class NationTreasuryActionResponse {
        public String message;
        public String nation_slug;
        public double new_treasury_balance;
    }

    public static final class NationTreasurySummaryResponse {
        public String nation_id;
        public double treasury_balance;
        public int territory_points;
        public int total_playtime_minutes;
        public int pvp_kills;
        public int mob_kills;
        public int boss_kills;
        public int deaths;
        public long blocks_placed;
        public long blocks_broken;
        public int events_completed;
        public int prestige_score;
        public String updated_at;
    }

    public static final class NationTreasuryTransactionListResponse {
        public int total;
        public java.util.List<NationTreasuryTransactionItem> items;
    }

    public static final class NationTreasuryTransactionItem {
        public String id;
        public String transaction_type;
        public double gross_amount;
        public double fee_amount;
        public double net_amount;
        public String comment;
        public java.util.Map<String, Object> metadata_json;
        public String created_at;
    }
}
