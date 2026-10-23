package com.undyingdominion.persistence;

import com.undyingdominion.game.Corpse;
import com.undyingdominion.game.EngineState;
import com.undyingdominion.game.PlayerState;
import com.undyingdominion.game.Trap;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitState;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.story.ChapterRun;
import com.undyingdominion.story.ChapterRunState;
import com.undyingdominion.story.StoryState;
import com.undyingdominion.world.Position;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** A {@link SaveGame} as the JSON document of docs/design/persistence.md §6, and back. */
public final class SaveGameCodec {

    private SaveGameCodec() {
    }

    public static String toJson(SaveGame save) {
        return new JSONObject()
                .put("format", save.format())
                .put("name", save.name())
                .put("savedAt", save.savedAt().toString())
                .put("campaign", new JSONObject()
                        .put("folder", save.campaignFolder())
                        .put("title", save.campaignTitle())
                        .put("fingerprint", save.fingerprint()))
                .put("random", save.random())
                .put("story", story(save.story()))
                .put("engine", engine(save.engine()))
                .toString();
    }

    /** Throws {@link SaveFormatException} for any other format, or a document that doesn't parse. */
    public static SaveGame fromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            int format = root.getInt("format");
            if (format != SaveGame.FORMAT) throw new SaveFormatException("Unsupported save format " + format);
            JSONObject campaign = root.getJSONObject("campaign");
            return new SaveGame(format, root.getString("name"), Instant.parse(root.getString("savedAt")),
                    campaign.getString("folder"), campaign.getString("title"), campaign.getString("fingerprint"),
                    root.getString("random"), story(root.getJSONObject("story")), engine(root.getJSONObject("engine")));
        } catch (JSONException | DateTimeParseException | IllegalArgumentException e) {
            throw new SaveFormatException("The save can't be read: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ story

    private static JSONObject story(StoryState story) {
        ChapterRunState run = story.run();
        return new JSONObject()
                .put("chapterIndex", story.chapterIndex())
                .put("moveOnAtTick", story.moveOnAtTick())
                .put("moveOnTo", story.moveOnTo())
                .put("run", new JSONObject()
                        .put("startTick", run.startTick())
                        .put("creatures", new JSONArray(run.creatures()))
                        .put("firedTriggers", new JSONArray(run.firedTriggers()))
                        .put("seenTypes", new JSONArray(run.seenTypes()))
                        .put("pendingLines", new JSONArray(run.pendingLines()))
                        .put("kills", run.kills())
                        .put("bossSeen", run.bossSeen())
                        .put("outcome", run.outcome().name()));
    }

    private static StoryState story(JSONObject json) {
        JSONObject run = json.getJSONObject("run");
        return new StoryState(json.getInt("chapterIndex"), json.getLong("moveOnAtTick"), json.getInt("moveOnTo"),
                new ChapterRunState(run.getLong("startTick"), ints(run.getJSONArray("creatures")),
                        ints(run.getJSONArray("firedTriggers")), strings(run.getJSONArray("seenTypes")),
                        strings(run.getJSONArray("pendingLines")), run.getInt("kills"), run.getBoolean("bossSeen"),
                        ChapterRun.Outcome.valueOf(run.getString("outcome"))));
    }

    // ------------------------------------------------------------------ engine

    private static JSONObject engine(EngineState engine) {
        JSONArray players = new JSONArray();
        for (PlayerState p : engine.players()) {
            players.put(new JSONObject()
                    .put("id", p.id())
                    .put("name", p.name())
                    .put("color", p.color())
                    .put("necromancerId", nullable(p.necromancerId()))
                    .put("respawnAtTick", p.respawnAtTick())
                    .put("soul", p.soul())
                    .put("xp", p.xp())
                    .put("level", p.level())
                    .put("kills", p.kills())
                    .put("deaths", p.deaths())
                    .put("ossuary", new JSONArray(p.ossuary().stream().map(UnitType::name).toList())));
        }
        JSONArray units = new JSONArray();
        for (UnitState u : engine.units()) {
            JSONArray path = new JSONArray();
            for (Position step : u.path()) path.put(position(step));
            units.put(new JSONObject()
                    .put("id", u.id())
                    .put("type", u.type())
                    .put("ownerId", u.ownerId())
                    .put("raised", u.raised())
                    .put("x", u.position().x())
                    .put("y", u.position().y())
                    .put("facingX", u.facingX())
                    .put("facingY", u.facingY())
                    .put("hp", u.hp())
                    .put("order", u.order().name())
                    .put("destination", u.destination() == null ? JSONObject.NULL : position(u.destination()))
                    .put("targetId", nullable(u.targetId()))
                    .put("autoTarget", u.autoTarget())
                    .put("modeEndsTick", u.modeEndsTick())
                    .put("path", path)
                    .put("pathGoal", u.pathGoal() == null ? JSONObject.NULL : position(u.pathGoal()))
                    .put("pathComputedTick", u.pathComputedTick())
                    .put("blockedTicks", u.blockedTicks())
                    .put("moveReadyTick", u.moveReadyTick())
                    .put("attackReadyTick", u.attackReadyTick())
                    .put("nextWanderTick", u.nextWanderTick())
                    .put("lastHitByOwner", u.lastHitByOwner())
                    .put("expiresAtTick", u.expiresAtTick())
                    .put("hasBeenHit", u.hasBeenHit()));
        }
        JSONArray corpses = new JSONArray();
        for (Corpse c : engine.corpses()) {
            corpses.put(new JSONObject()
                    .put("id", c.id())
                    .put("type", c.type().name())
                    .put("x", c.position().x())
                    .put("y", c.position().y())
                    .put("decaysAtTick", c.decaysAtTick()));
        }
        JSONArray traps = new JSONArray();
        for (Trap t : engine.traps()) {
            traps.put(new JSONObject()
                    .put("id", t.id())
                    .put("ownerId", t.ownerId())
                    .put("x", t.position().x())
                    .put("y", t.position().y()));
        }
        return new JSONObject()
                .put("tick", engine.tick())
                .put("nextPlayerId", engine.nextPlayerId())
                .put("nextUnitId", engine.nextUnitId())
                .put("nextCorpseId", engine.nextCorpseId())
                .put("nextTrapId", engine.nextTrapId())
                .put("startArea", engine.startArea() == null ? JSONObject.NULL : new JSONArray(engine.startArea()))
                .put("players", players)
                .put("units", units)
                .put("corpses", corpses)
                .put("traps", traps);
    }

    /** A save from before the ossuary has none: the player carried no corpses. */
    private static List<UnitType> ossuary(JSONArray names) {
        List<UnitType> corpses = new ArrayList<>();
        if (names == null) return corpses;
        for (int i = 0; i < names.length(); i++) corpses.add(UnitType.valueOf(names.getString(i)));
        return List.copyOf(corpses);
    }

    private static EngineState engine(JSONObject json) {
        List<PlayerState> players = list(json.getJSONArray("players"), p -> new PlayerState(p.getInt("id"),
                p.getString("name"), p.getString("color"), optInt(p, "necromancerId"), p.getLong("respawnAtTick"),
                p.getInt("soul"), p.getInt("xp"), p.getInt("level"), p.getInt("kills"), p.getInt("deaths"),
                ossuary(p.optJSONArray("ossuary"))));
        List<UnitState> units = list(json.getJSONArray("units"), u -> {
            List<Position> path = new ArrayList<>();
            JSONArray steps = u.getJSONArray("path");
            for (int i = 0; i < steps.length(); i++) path.add(position(steps.getJSONArray(i)));
            return new UnitState(u.getInt("id"), u.getString("type"), u.getInt("ownerId"), u.getBoolean("raised"),
                    new Position(u.getInt("x"), u.getInt("y")), u.getInt("facingX"), u.getInt("facingY"), u.getInt("hp"),
                    Unit.Order.valueOf(u.getString("order")), optPosition(u, "destination"), optInt(u, "targetId"),
                    u.getBoolean("autoTarget"), u.getLong("modeEndsTick"), List.copyOf(path), optPosition(u, "pathGoal"),
                    u.getLong("pathComputedTick"), u.getInt("blockedTicks"), u.getLong("moveReadyTick"),
                    u.getLong("attackReadyTick"), u.getLong("nextWanderTick"), u.getInt("lastHitByOwner"),
                    u.getBoolean("hasBeenHit"),
                    // Saves from before raised units expired carry no time: the engine gives them a full one.
                    u.optLong("expiresAtTick", Unit.NO_EXPIRY));
        });
        List<Corpse> corpses = list(json.getJSONArray("corpses"), c -> new Corpse(c.getInt("id"),
                UnitType.valueOf(c.getString("type")), new Position(c.getInt("x"), c.getInt("y")), c.getLong("decaysAtTick")));
        List<Trap> traps = list(json.getJSONArray("traps"), t -> new Trap(t.getInt("id"), t.getInt("ownerId"),
                new Position(t.getInt("x"), t.getInt("y"))));
        return new EngineState(json.getLong("tick"), json.getInt("nextPlayerId"), json.getInt("nextUnitId"),
                json.getInt("nextCorpseId"), json.getInt("nextTrapId"),
                json.isNull("startArea") ? null : ints(json.getJSONArray("startArea")),
                players, units, corpses, traps);
    }

    // ------------------------------------------------------------------ helpers

    private static JSONArray position(Position p) {
        return new JSONArray().put(p.x()).put(p.y());
    }

    private static Position position(JSONArray xy) {
        return new Position(xy.getInt(0), xy.getInt(1));
    }

    private static Position optPosition(JSONObject json, String key) {
        return json.isNull(key) ? null : position(json.getJSONArray(key));
    }

    private static Object nullable(Integer value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static Integer optInt(JSONObject json, String key) {
        return json.isNull(key) ? null : json.getInt(key);
    }

    private static List<Integer> ints(JSONArray array) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getInt(i));
        return List.copyOf(values);
    }

    private static List<String> strings(JSONArray array) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
        return List.copyOf(values);
    }

    private static <T> List<T> list(JSONArray array, Function<JSONObject, T> read) {
        List<T> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) values.add(read.apply(array.getJSONObject(i)));
        return List.copyOf(values);
    }
}
