package ru.voidrp.gamesync.model;

import java.util.Map;

/** Result of a research purchase (mirrors backend ResearchPurchaseResponse). */
public final class NationResearchPurchaseResponse {
    public String message;
    public String research_key;
    public int new_level;
    public double spent;
    public double treasury_balance;
    public Map<String, Double> effects;
}
