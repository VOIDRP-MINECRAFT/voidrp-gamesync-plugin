package ru.voidrp.gamesync.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record NationDefinition(
    String slug,
    String title,
    String tag,
    String leader,
    List<String> officers,
    List<String> members,
    int territoryPoints,
    int bossKills,
    int eventsCompleted,
    int prestigeBonus,
    String accentColor,
    Integer capitalX,
    Integer capitalZ,
    String capitalWorld,
    Map<String, String> customPrefixes
) {
    public List<String> allMembersIncludingRoles() {
        Set<String> all = new LinkedHashSet<>();
        if (leader != null && !leader.isBlank()) {
            all.add(leader);
        }
        all.addAll(officers);
        all.addAll(members);
        return new ArrayList<>(all);
    }

    public String roleFor(String minecraftNickname) {
        if (minecraftNickname == null || minecraftNickname.isBlank()) {
            return null;
        }
        if (leader != null && leader.equalsIgnoreCase(minecraftNickname)) {
            return "leader";
        }
        for (String officer : officers) {
            if (officer.equalsIgnoreCase(minecraftNickname)) {
                return "officer";
            }
        }
        for (String member : members) {
            if (member.equalsIgnoreCase(minecraftNickname)) {
                return "member";
            }
        }
        return null;
    }

    public boolean contains(String minecraftNickname) {
        return roleFor(minecraftNickname) != null;
    }

    public String customPrefixFor(String minecraftNickname) {
        if (minecraftNickname == null || customPrefixes == null) return null;
        return customPrefixes.get(minecraftNickname);
    }
}
