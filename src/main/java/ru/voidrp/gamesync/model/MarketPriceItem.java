package ru.voidrp.gamesync.model;

public final class MarketPriceItem {
    public String material;
    public String display_name;
    public String group_key;
    public double base_buy_price;
    public double base_sell_price;
    public double current_buy_price;
    public double current_sell_price;
    public double buy_multiplier;
    public double sell_multiplier;
    public double demand_score;
    public double supply_score;
    public boolean enabled;
    public String updated_at;
    public String shop_section;
    public String shop_item_index;
    public String source;

    public double marketBuyPrice() {
        return Math.max(0D, current_buy_price);
    }

    public double marketSellPrice() {
        return Math.max(0D, current_sell_price);
    }
}
