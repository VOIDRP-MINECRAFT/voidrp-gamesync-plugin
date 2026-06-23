package ru.voidrp.gamesync.model;

public final class NationMarketPurchaseResponse {
    public String message;
    public NationMarketListing listing;
    public int purchased_amount;
    public double unit_price;
    public double gross_total;
    public double fee_amount;
    public double net_total;
    public String item_stack_base64;
}
