package ru.voidrp.gamesync.model;

import java.util.List;

public final class PlayerMarketCreateBuyOrderResponse {
    public String message;
    public PlayerMarketBuyOrderRead order;
    public List<PlayerMarketImmediateFill> immediate_fills;
}
