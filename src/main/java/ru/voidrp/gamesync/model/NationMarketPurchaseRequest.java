package ru.voidrp.gamesync.model;

public record NationMarketPurchaseRequest(
        String buyer_player_name,
        int amount,
        double expected_unit_price
) {}
