package ru.voidrp.gamesync.model;

public record ModdedShopEntry(
    String moddedId,    // "kubejs:rough_mechanism"
    String displayName, // "Черновой механизм"
    double buyPrice,    // 200.0
    double sellPrice,   // 50.0 (0 = not sellable)
    boolean locked      // true = ignore market cache, always use base price
) {}
