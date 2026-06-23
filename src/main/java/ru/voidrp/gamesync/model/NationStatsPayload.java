package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record NationStatsPayload(
    @SerializedName("nation_slug") String nationSlug,
    @SerializedName("treasury_balance") double treasuryBalance,
    @SerializedName("territory_points") int territoryPoints,
    @SerializedName("total_playtime_minutes") int totalPlaytimeMinutes,
    @SerializedName("pvp_kills") int pvpKills,
    @SerializedName("mob_kills") int mobKills,
    @SerializedName("boss_kills") int bossKills,
    @SerializedName("deaths") int deaths,
    @SerializedName("blocks_placed") long blocksPlaced,
    @SerializedName("blocks_broken") long blocksBroken,
    @SerializedName("events_completed") int eventsCompleted,
    @SerializedName("prestige_score") int prestigeScore
) {}
