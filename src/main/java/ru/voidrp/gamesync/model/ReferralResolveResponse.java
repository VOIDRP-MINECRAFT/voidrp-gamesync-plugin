package ru.voidrp.gamesync.model;

import java.util.List;

public final class ReferralResolveResponse {
    public String minecraft_nickname;
    public boolean player_exists;
    public boolean has_active_reward;
    public Reward reward;

    public static final class Reward {
        public String referral_rank;
        public String starts_at;
        public String expires_at;
        public String reward_state;
        public int source_qualified_referrals;
        public String reward_bundle_key;
        public List<String> game_perks;
    }
}
