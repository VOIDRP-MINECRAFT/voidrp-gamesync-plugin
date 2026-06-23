package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameAllianceKickRequest(
    @SerializedName("minecraft_nickname") String minecraftNickname,
    @SerializedName("target_nation_slug") String targetNationSlug
) {}
