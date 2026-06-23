package ru.voidrp.gamesync.model;

import java.util.Map;

public record NationMarketCreateRequest(
        String nation_slug,
        String seller_player_name,
        String seller_role,
        String material,
        String display_name,
        String item_stack_base64,
        int total_amount,
        double anchor_unit_price,
        double market_price_at_create,
        Map<String, Object> metadata_json
) {}
