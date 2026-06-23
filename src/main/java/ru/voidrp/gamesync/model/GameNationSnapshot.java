package ru.voidrp.gamesync.model;

import java.util.List;
import java.util.Map;

public final class GameNationSnapshot {
    public String nation_id;
    public String nation_slug;
    public String title;
    public String tag;
    public String accent_color;
    public Integer capital_x;
    public Integer capital_z;
    public String capital_world;
    public String leader_minecraft_nickname;
    public List<String> officers;
    public List<String> members;
    public Map<String, String> member_custom_prefixes;
    public String updated_at;
}
