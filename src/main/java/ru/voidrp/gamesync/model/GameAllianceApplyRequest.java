package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameAllianceApplyRequest(
    @SerializedName("minecraft_nickname") String minecraftNickname,
    @SerializedName("alliance_slug") String allianceSlug
) {}
