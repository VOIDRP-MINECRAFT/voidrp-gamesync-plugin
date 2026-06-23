package ru.voidrp.gamesync.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public final class ItemStackSnapshotService {

    /**
     * Serializes a single item using Paper's NBT-based format (1.20.4+).
     * Falls back to BukkitObjectOutputStream for older stored data.
     */
    public String serializeSingle(ItemStack source) {
        if (source == null) throw new IllegalArgumentException("source item must not be null");
        ItemStack clone = source.clone();
        clone.setAmount(1);
        try {
            // Paper 1.20.4+ NBT format — reliable for both vanilla and modded items on Mohist
            return Base64.getEncoder().encodeToString(clone.serializeAsBytes());
        } catch (Exception nbt) {
            // Legacy fallback
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (BukkitObjectOutputStream stream = new BukkitObjectOutputStream(output)) {
                    stream.writeObject(clone);
                }
                return Base64.getEncoder().encodeToString(output.toByteArray());
            } catch (IOException io) {
                throw new IllegalStateException("Cannot serialize item: " + io.getMessage(), io);
            }
        }
    }

    /**
     * Deserializes an item — tries Paper NBT format first, then BukkitObjectInputStream
     * (for backwards-compatible reading of old DB entries).
     */
    public ItemStack deserialize(String base64, int amount) {
        if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("empty item snapshot");
        byte[] raw = Base64.getDecoder().decode(base64);

        // Try Paper NBT-based deserialization first (works for mod items on Mohist)
        try {
            ItemStack item = ItemStack.deserializeBytes(raw);
            item.setAmount(Math.max(1, amount));
            return item;
        } catch (Exception ignored) {}

        // Legacy fallback — BukkitObjectInputStream (Java object serialization)
        try (BukkitObjectInputStream stream = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
            Object value = stream.readObject();
            if (!(value instanceof ItemStack item)) throw new IllegalStateException("snapshot is not an ItemStack");
            ItemStack clone = item.clone();
            clone.setAmount(Math.max(1, amount));
            return clone;
        } catch (IOException | ClassNotFoundException ex) {
            throw new IllegalStateException("Cannot deserialize item: " + ex.getMessage(), ex);
        }
    }
}
