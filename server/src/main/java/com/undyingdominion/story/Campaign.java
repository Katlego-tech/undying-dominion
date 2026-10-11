package com.undyingdominion.story;

import com.undyingdominion.game.UnitType;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A story told as a sequence of chapters, each a map with its own creatures, waves, events and goal.
 * Loaded from a folder holding {@code campaign.json} and the map files it names. See the README for
 * the format.
 */
public record Campaign(String title, List<Chapter> chapters, Path folder) {

    /** One playable chapter of the story. */
    public record Chapter(String id, String title, String source, String mapFile, int[] startArea,
                          int startingSoul, List<String> briefing, Objective objective, List<Spawn> spawns,
                          List<Trigger> triggers, List<String> victory, int timeLimitSeconds) {}

    /** What has to happen for the chapter to be won. */
    public record Objective(Kind kind, String text, String unitType, int count, int seconds) {
        public enum Kind { CLEAR_ALL, KILL_BOSS, KILL_COUNT, SURVIVE }
    }

    /** Creatures placed at the start, or later by a trigger: at fixed tiles, or scattered across an area. */
    public record Spawn(String unitType, int count, int[] area, List<int[]> at) {}

    /**
     * Something that happens once during a chapter: after some seconds, when a boss is wounded below a
     * share of its life, or when every creature of a type has fallen. It can bring more creatures and
     * show a line of story.
     */
    public record Trigger(When when, int seconds, String unitType, int percent, List<Spawn> spawns, List<String> say) {
        public enum When { AT_SECOND, BOSS_HP_BELOW, TYPE_CLEARED }
    }

    public static Campaign load(Path folder) throws IOException {
        Path file = folder.resolve("campaign.json");
        JSONObject json = new JSONObject(Files.readString(file));

        JSONObject creatures = json.optJSONObject("creatures", new JSONObject());
        for (String name : creatures.keySet()) {
            UnitType.register(creature(name, creatures.getJSONObject(name)));
        }

        List<Chapter> chapters = new ArrayList<>();
        JSONArray list = json.getJSONArray("chapters");
        for (int i = 0; i < list.length(); i++) {
            chapters.add(chapter(list.getJSONObject(i)));
        }
        Campaign campaign = new Campaign(json.getString("title"), List.copyOf(chapters), folder);
        campaign.validate();
        return campaign;
    }

    public GameMap loadMap(Chapter chapter) throws IOException {
        return MapConfig.load(folder.resolve(chapter.mapFile()).toString());
    }

    /** Fails fast on a typo, rather than in the middle of a game. */
    private void validate() throws IOException {
        for (Chapter chapter : chapters) {
            GameMap map = loadMap(chapter);
            for (Spawn spawn : allSpawns(chapter)) {
                UnitType.valueOf(spawn.unitType());
                for (int[] p : spawn.at()) {
                    if (!map.inBounds(p[0], p[1]) || !map.isWalkable(p[0], p[1])) {
                        throw new IllegalArgumentException(chapter.id() + ": " + spawn.unitType()
                                + " placed on an unwalkable tile " + p[0] + "," + p[1]);
                    }
                }
            }
            if (chapter.objective().unitType() != null) {
                for (String name : chapter.objective().unitType().split(",")) UnitType.valueOf(name);
            }
        }
    }

    static List<Spawn> allSpawns(Chapter chapter) {
        List<Spawn> all = new ArrayList<>(chapter.spawns());
        for (Trigger trigger : chapter.triggers()) all.addAll(trigger.spawns());
        return all;
    }

    // ------------------------------------------------------------------ parsing

    private static UnitType creature(String name, JSONObject c) {
        return UnitType.builder(name, c.getString("label"))
                .stats(c.getInt("hp"), c.optInt("damage", 8), c.optInt("range", 1),
                        c.optInt("attackCooldown", 10), c.optInt("moveCooldown", 4))
                .senses(c.optInt("aggro", 5), c.optInt("vision", 6))
                .costs(-1, c.optInt("raise", 15))
                .rewards(c.optInt("soul", 8), c.optInt("xp", 10))
                .splash(c.optInt("splash", 0))
                .look(UnitType.Look.valueOf(c.optString("look", "BEAST")))
                .level(c.optInt("level", 0))
                .boss(c.optBoolean("boss", false))
                .stationary(c.optBoolean("stationary", false))
                .build();
    }

    private static Chapter chapter(JSONObject c) {
        List<Trigger> triggers = new ArrayList<>();
        JSONArray t = c.optJSONArray("triggers", new JSONArray());
        for (int i = 0; i < t.length(); i++) triggers.add(trigger(t.getJSONObject(i)));
        JSONObject start = c.getJSONObject("start");
        return new Chapter(c.getString("id"), c.getString("title"), c.optString("source", ""),
                c.getString("map"), ints(start.getJSONArray("area")), start.optInt("soul", 80),
                strings(c.optJSONArray("briefing")), objective(c.getJSONObject("objective")),
                spawns(c.optJSONArray("spawns")), List.copyOf(triggers), strings(c.optJSONArray("victory")),
                c.optInt("timeLimitSeconds", 0));
    }

    private static Objective objective(JSONObject o) {
        return new Objective(Objective.Kind.valueOf(o.getString("kind")), o.getString("text"),
                o.has("unit") ? o.getString("unit") : null, o.optInt("count", 0), o.optInt("seconds", 0));
    }

    private static Trigger trigger(JSONObject t) {
        Trigger.When when = Trigger.When.valueOf(t.getString("when"));
        return new Trigger(when, t.optInt("seconds", 0), t.has("unit") ? t.getString("unit") : null,
                t.optInt("percent", 0), spawns(t.optJSONArray("spawns")), strings(t.optJSONArray("say")));
    }

    private static List<Spawn> spawns(JSONArray array) {
        List<Spawn> spawns = new ArrayList<>();
        if (array == null) return spawns;
        for (int i = 0; i < array.length(); i++) {
            JSONObject s = array.getJSONObject(i);
            List<int[]> at = new ArrayList<>();
            JSONArray points = s.optJSONArray("at");
            if (points != null) {
                for (int j = 0; j < points.length(); j++) at.add(ints(points.getJSONArray(j)));
            }
            spawns.add(new Spawn(s.getString("unit"), s.optInt("count", at.size()),
                    s.has("area") ? ints(s.getJSONArray("area")) : null, List.copyOf(at)));
        }
        return List.copyOf(spawns);
    }

    private static int[] ints(JSONArray array) {
        int[] out = new int[array.length()];
        for (int i = 0; i < out.length; i++) out[i] = array.getInt(i);
        return out;
    }

    private static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        return List.copyOf(out);
    }
}
