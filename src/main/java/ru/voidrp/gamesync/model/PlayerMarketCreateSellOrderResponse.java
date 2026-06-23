package ru.voidrp.gamesync.model;

import java.util.List;

public final class PlayerMarketCreateSellOrderResponse {
    public String message;
    public PlayerMarketSellOrderRead order;
    public List<PlayerMarketImmediateFill> immediate_fills;
}
