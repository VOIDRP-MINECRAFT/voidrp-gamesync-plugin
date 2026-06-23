package ru.voidrp.gamesync.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public record PlayerStatCacheSyncRequest(
    @SerializedName("players") List<PlayerStatSnapshot> players
) {}
