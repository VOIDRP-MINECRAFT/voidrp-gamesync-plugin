package ru.voidrp.gamesync.model;

import java.util.List;

/** Response from GET /game-sync/tiktok/pending-rewards. Gson-deserialized. */
public final class TikTokPendingRewardsResponse {
    public List<TikTokPendingReward> rewards;

    public static final class TikTokPendingReward {
        public String reward_id;
        public String campaign_id;
        public String minecraft_uuid;
        public String minecraft_nickname;
    }
}
