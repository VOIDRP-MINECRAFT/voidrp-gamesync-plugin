package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameAllianceLeaveRequest(
    @SerializedName("minecraft_nickname") String minecraftNickname
) {}
