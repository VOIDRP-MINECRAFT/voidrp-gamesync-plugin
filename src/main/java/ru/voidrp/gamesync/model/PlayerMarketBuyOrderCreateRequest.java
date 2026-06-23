package ru.voidrp.gamesync.model;

public final class PlayerMarketBuyOrderCreateRequest {
    public String buyer_player_name;
    public String item_key;
    public String display_name;
    public String item_display_base64;
    public int total_amount;
    public double unit_price;
    public double reserved_funds;
    public boolean is_premium;

    public PlayerMarketBuyOrderCreateRequest(
            String buyer_player_name,
            String item_key,
            String display_name,
            String item_display_base64,
            int total_amount,
            double unit_price,
            double reserved_funds,
            boolean is_premium
    ) {
        this.buyer_player_name = buyer_player_name;
        this.item_key = item_key;
        this.display_name = display_name;
        this.item_display_base64 = item_display_base64;
        this.total_amount = total_amount;
        this.unit_price = unit_price;
        this.reserved_funds = reserved_funds;
        this.is_premium = is_premium;
    }
}
