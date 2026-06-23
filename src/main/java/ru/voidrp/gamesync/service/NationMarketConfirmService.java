package ru.voidrp.gamesync.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.inventory.ItemStack;

import ru.voidrp.gamesync.model.NationDefinition;

public final class NationMarketConfirmService {
    private final Map<UUID, PendingListing> pending = new ConcurrentHashMap<>();

    public void put(UUID playerId, PendingListing listing) {
        pending.put(playerId, listing);
    }

    public PendingListing consume(UUID playerId, long timeoutMs) {
        PendingListing listing = pending.remove(playerId);
        if (listing == null) return null;
        if (System.currentTimeMillis() - listing.createdAtMs() > timeoutMs) {
            return null;
        }
        return listing;
    }

    public void clear(UUID playerId) {
        pending.remove(playerId);
    }

    public record PendingListing(
            NationDefinition nation,
            String role,
            ItemStack sample,
            int amount,
            double pricePerItem,
            double marketPrice,
            String warning,
            long createdAtMs
    ) {}
}
