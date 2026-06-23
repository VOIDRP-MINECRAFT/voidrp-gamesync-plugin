package ru.voidrp.gamesync.util;

public final class NameNormalizer {

    private NameNormalizer() {}

    public static String normalizeMinecraftName(String value) {
        if (value == null) {
            throw new IllegalArgumentException("minecraft name cannot be null");
        }

        String trimmed = value.trim();
        if (trimmed.length() < 3 || trimmed.length() > 16) {
            throw new IllegalArgumentException("minecraft name must be between 3 and 16 characters");
        }

        if (!trimmed.matches("^[A-Za-z0-9_]+$")) {
            throw new IllegalArgumentException("minecraft name contains invalid characters");
        }

        return trimmed;
    }
}
