package ru.voidrp.gamesync.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

public record NationMemberStatsSyncRequest(
    @SerializedName("nation_slug") String nationSlug,
    @SerializedName("territory_points") int territoryPoints,
    @SerializedName("boss_kills") int bossKills,
    @SerializedName("events_completed") int eventsCompleted,
    @SerializedName("prestige_bonus") int prestigeBonus,
    @SerializedName("members") List<PlayerStatSnapshot> members
) {}
