package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameNationDonationRequest(
    @SerializedName("nation_slug") String nationSlug,
    @SerializedName("amount") double amount,
    @SerializedName("minecraft_nickname") String minecraftNickname,
    @SerializedName("comment") String comment
) {}
