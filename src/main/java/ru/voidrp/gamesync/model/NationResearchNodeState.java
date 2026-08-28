package ru.voidrp.gamesync.model;

/** One research node's state for a nation (mirrors backend ResearchNodeState). */
public final class NationResearchNodeState {
    public String key;
    public String title;
    public String description;
    public String category;
    public String icon;
    public String effect_key;
    public String effect_unit;
    public double effect_per_level;
    public int level;
    public int max_level;
    public double current_effect;
    public Double next_effect;   // null when maxed
    public Double next_cost;     // null when maxed
    public boolean can_afford;
    public boolean locked;
    public String lock_reason;
    public String requires;
    public int requires_level;
}
