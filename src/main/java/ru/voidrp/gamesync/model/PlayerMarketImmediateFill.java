package ru.voidrp.gamesync.model;

public final class PlayerMarketImmediateFill {
    public String trade_id;
    public String matched_order_id;
    public int amount_filled;
    public double unit_price;
    public double net_seller_proceeds;
    public String item_stack_base64;
    public double funds_released_to_seller;
    public double funds_returned_to_buyer;
    public String seller_player_name;
    public String buyer_player_name;
}
