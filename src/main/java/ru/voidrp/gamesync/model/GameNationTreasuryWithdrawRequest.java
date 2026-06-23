package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;

public record GameNationTreasuryWithdrawRequest(
        @SerializedName("nation_slug")
        String nationSlug,
        @SerializedName("amount")
        double amount,
        @SerializedName("minecraft_nickname")
        String minecraftNickname,
        @SerializedName("comment")
        String comment
        ) {

}
