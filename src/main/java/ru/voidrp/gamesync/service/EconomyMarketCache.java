package ru.voidrp.gamesync.service;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;

import ru.voidrp.gamesync.model.MarketPriceItem;
import ru.voidrp.gamesync.model.MarketPriceSnapshotResponse;

public final class EconomyMarketCache {
    private final Map<String, MarketPriceItem> byMaterial = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0L;

    public void applySnapshot(MarketPriceSnapshotResponse response) {
        byMaterial.clear();
        if (response != null && response.items != null) {
            for (MarketPriceItem item : response.items) {
                if (item == null || item.material == null || item.material.isBlank()) {
                    continue;
                }
                byMaterial.put(normalize(item.material), item);
            }
        }
        lastRefreshMs = System.currentTimeMillis();
    }

    public MarketPriceItem get(String material) {
        if (material == null) return null;
        return byMaterial.get(normalize(material));
    }

    public MarketPriceItem get(Material material) {
        return material == null ? null : get(material.name());
    }

    public Collection<MarketPriceItem> all() {
        return Collections.unmodifiableCollection(byMaterial.values());
    }

    public long lastRefreshMs() {
        return lastRefreshMs;
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
