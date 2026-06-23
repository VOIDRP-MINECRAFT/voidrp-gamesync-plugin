package ru.voidrp.gamesync.model;

import java.util.List;

public final class PlayerMarketDeliveryAckRequest {
    public List<String> delivery_ids;

    public PlayerMarketDeliveryAckRequest(List<String> delivery_ids) {
        this.delivery_ids = delivery_ids;
    }
}
