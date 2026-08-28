package ru.voidrp.gamesync.model;

import java.util.List;
import java.util.Map;

/** Nation research overview (mirrors backend NationResearchOverview). */
public final class NationResearchOverviewResponse {
    public String nation_slug;
    public String nation_title;
    public String role;
    public double treasury_balance;
    public List<NationResearchNodeState> nodes;
    public Map<String, Double> effects;
}
