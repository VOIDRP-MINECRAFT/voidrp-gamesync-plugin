package ru.voidrp.gamesync.model;

import java.util.Map;

public final class PlayerMarketSellOrderCreateRequest {
    public String seller_player_name;
    public String item_key;
    public String display_name;
    public String item_stack_base64;
    public int total_amount;
    public double unit_price;
    public Map<String, Object> metadata_json;
    public boolean is_premium;

    public PlayerMarketSellOrderCreateRequest(
            String seller_player_name,
            String item_key,
            String display_name,
            String item_stack_base64,
            int total_amount,
            double unit_price,
            Map<String, Object> metadata_json,
            boolean is_premium
    ) {
        this.seller_player_name = seller_player_name;
        this.item_key = item_key;
        this.display_name = display_name;
        this.item_stack_base64 = item_stack_base64;
        this.total_amount = total_amount;
        this.unit_price = unit_price;
        this.metadata_json = metadata_json;
        this.is_premium = is_premium;
    }
}
