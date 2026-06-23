package ru.voidrp.gamesync.model;

import java.util.Map;

public record MarketTransactionPushRequest(
        String player_name,
        String material,
        int amount,
        String transaction_type,
        double base_total_price,
        double final_total_price,
        double market_multiplier,
        String display_name,
        String shop_section,
        String shop_item_index,
        String source,
        Map<String, Object> metadata_json
) {}
