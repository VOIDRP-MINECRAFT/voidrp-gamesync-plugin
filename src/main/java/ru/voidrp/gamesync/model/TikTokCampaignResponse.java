package ru.voidrp.gamesync.model;

/** Response from POST /game-sync/tiktok/campaign. Gson-deserialized. */
public final class TikTokCampaignResponse {
    public String campaign_id;
    public String video_url;
    /** Full base URL; the plugin appends "/{uuid}/{sig}" per player. */
    public String click_base;
}
