package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameAllianceVoteRequest(
    @SerializedName("minecraft_nickname") String minecraftNickname,
    @SerializedName("vote") String vote,
    @SerializedName("comment") String comment
) {}
