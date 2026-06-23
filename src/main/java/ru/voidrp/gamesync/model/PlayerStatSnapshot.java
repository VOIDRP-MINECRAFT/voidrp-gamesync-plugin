package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record PlayerStatSnapshot(
    @SerializedName("minecraft_nickname") String minecraftNickname,
    @SerializedName("total_playtime_minutes") int totalPlaytimeMinutes,
    @SerializedName("pvp_kills") int pvpKills,
    @SerializedName("mob_kills") int mobKills,
    @SerializedName("deaths") int deaths,
    @SerializedName("blocks_placed") long blocksPlaced,
    @SerializedName("blocks_broken") long blocksBroken,
    @SerializedName("current_balance") double currentBalance,
    @SerializedName("source") String source,
    @SerializedName("last_seen_at") String lastSeenAt,
    @SerializedName("completed_quests") int completedQuests
) {}


