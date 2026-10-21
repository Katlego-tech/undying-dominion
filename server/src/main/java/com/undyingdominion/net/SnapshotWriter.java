package com.undyingdominion.net;

import com.undyingdominion.game.Corpse;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameEvent;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Trap;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.game.Visibility;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Obstacle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Base64;
import java.util.List;
import java.util.function.IntPredicate;

/** Builds the JSON messages the server sends. Everything except {@link #welcome} runs on the game loop thread. */
public final class SnapshotWriter {

    private SnapshotWriter() {
    }

    /**
     * Sent when a join or rejoin is accepted and on every chapter change: everything about the world that
     * never changes, and the seat's token for rejoining.
     */
    public static String welcome(int playerId, String name, String token, GameMap map, GameRules rules) {
        JSONObject types = new JSONObject();
        for (UnitType type : UnitType.values()) {
            types.put(type.name(), new JSONObject()
                    .put("label", type.label)
                    .put("maxHp", type.maxHp)
                    .put("damage", type.damage)
                    .put("range", type.range)
                    .put("moveCooldown", type.moveCooldown)
                    .put("visionRange", type.visionRange)
                    .put("summonCost", type.summonCost)
                    .put("raiseCost", type.raiseCost)
                    .put("neutral", type.neutral)
                    .put("laysTraps", type.laysTraps())
                    .put("look", type.look.name())
                    .put("level", type.level)
                    .put("boss", type.boss)
                    .put("stationary", type.stationary));
        }
        return new JSONObject()
                .put("type", "welcome")
                .put("playerId", playerId)
                .put("name", name)
                .put("token", token)
                .put("tickRate", rules.ticksPerSecond())
                .put("map", mapJson(map))
                .put("rules", new JSONObject()
                        .put("raiseRange", rules.raiseRange())
                        .put("fuseCount", rules.fuseCount())
                        .put("fuseSoulCost", rules.fuseSoulCost())
                        .put("fuseRadius", rules.fuseRadius())
                        .put("mendTicks", rules.mendTicks())
                        .put("trapSetTicks", rules.trapSetTicks())
                        .put("trapDamage", rules.trapDamage())
                        .put("explodeSoulCost", rules.explodeSoulCost())
                        .put("explodeRadius", rules.explodeRadius()))
                .put("unitTypes", types)
                .toString();
    }

    public static JSONObject mapJson(GameMap map) {
        JSONArray obstacles = new JSONArray();
        for (Obstacle o : map.getObstacles()) {
            obstacles.put(new JSONObject()
                    .put("x1", o.x1()).put("y1", o.y1())
                    .put("x2", o.x2()).put("y2", o.y2())
                    .put("terrain", o.terrain().name()));
        }
        return new JSONObject()
                .put("width", map.getWidth())
                .put("height", map.getHeight())
                .put("obstacles", obstacles);
    }

    /** The scoreboard every player may see. */
    public static JSONArray scoreboard(GameEngine engine) {
        return scoreboard(engine, playerId -> false);
    }

    /** Every necromancer's public record. {@code away} says whose connection has dropped while their place is kept. */
    public static JSONArray scoreboard(GameEngine engine, IntPredicate away) {
        JSONArray players = new JSONArray();
        for (Player p : engine.getPlayers()) {
            players.put(new JSONObject()
                    .put("id", p.getId())
                    .put("name", p.getName())
                    .put("color", p.getColor())
                    .put("level", p.getLevel())
                    .put("kills", p.getKills())
                    .put("deaths", p.getDeaths())
                    .put("alive", p.getNecromancerId() != null)
                    .put("away", away.test(p.getId())));
        }
        return players;
    }

    /** One player's view of the current tick, with everything under their fog left out. */
    public static String state(GameEngine engine, Player player, JSONArray scoreboard) {
        return state(engine, player, scoreboard, null);
    }

    /** As above, with the campaign's story progress when there is one. */
    public static String state(GameEngine engine, Player player, JSONArray scoreboard, JSONObject story) {
        return state(engine, player, scoreboard, story, null);
    }

    /** As above, and {@code paused} ({@code {by, reason}}) while the world is stopped. */
    public static String state(GameEngine engine, Player player, JSONArray scoreboard, JSONObject story,
                               JSONObject paused) {
        long tick = engine.getTick();
        Visibility sight = Visibility.of(engine.getMap(), engine.getUnits(), player.getId());

        JSONArray units = new JSONArray();
        for (Unit u : engine.getUnits()) {
            boolean own = u.getOwnerId() == player.getId();
            if (!own && !sight.sees(u.getPosition())) continue;
            JSONObject json = new JSONObject()
                    .put("id", u.getId())
                    .put("type", u.getType().name())
                    .put("owner", u.getOwnerId())
                    .put("x", u.getPosition().x())
                    .put("y", u.getPosition().y())
                    .put("hp", u.getHp())
                    .put("order", u.getOrder().name());
            if (u.isRaised()) json.put("raised", true);
            if (u.isRooted()) json.put("modeEndsIn", Math.max(0, u.getModeEndsTick() - tick));
            units.put(json);
        }

        JSONArray corpses = new JSONArray();
        for (Corpse c : engine.getCorpses()) {
            if (!sight.sees(c.position())) continue;
            corpses.put(new JSONObject()
                    .put("id", c.id())
                    .put("type", c.type().name())
                    .put("x", c.position().x())
                    .put("y", c.position().y())
                    .put("decaysIn", Math.max(0, c.decaysAtTick() - tick)));
        }

        JSONArray traps = new JSONArray();
        for (Trap t : engine.getTraps()) {
            if (!sight.knowsTrap(t, player.getId())) continue;
            traps.put(new JSONObject()
                    .put("id", t.id())
                    .put("owner", t.ownerId())
                    .put("x", t.position().x())
                    .put("y", t.position().y()));
        }

        // A paused engine still holds the last tick's events; they were sent once already.
        JSONArray events = new JSONArray();
        for (GameEvent e : paused == null ? engine.getEvents() : List.<GameEvent>of()) {
            boolean seen = switch (e.kind()) {
                case LEVEL_UP -> true;
                case ATTACK -> sight.sees(e.x(), e.y()) || sight.sees(e.x2(), e.y2());
                default -> sight.sees(e.x(), e.y());
            };
            if (!seen) continue;
            events.put(new JSONObject()
                    .put("kind", e.kind().name())
                    .put("a", e.a()).put("b", e.b())
                    .put("x", e.x()).put("y", e.y())
                    .put("x2", e.x2()).put("y2", e.y2()));
        }

        Integer necromancerId = player.getNecromancerId();
        JSONObject you = new JSONObject()
                .put("id", player.getId())
                .put("soul", player.getSoul())
                .put("soulMax", player.soulMax())
                .put("level", player.getLevel())
                .put("xp", player.getXp())
                .put("xpNext", player.xpForNextLevel())
                .put("legion", engine.legionSizeOf(player))
                .put("legionCap", player.legionCap())
                .put("necromancerId", necromancerId == null ? JSONObject.NULL : necromancerId)
                .put("respawnIn", necromancerId == null && player.getRespawnAtTick() >= 0
                        ? Math.max(0, player.getRespawnAtTick() - tick) : 0)
                .put("notices", new JSONArray(player.takeNotices()));

        JSONObject message = new JSONObject();
        if (story != null) message.put("story", story);
        if (paused != null) message.put("paused", paused);
        return message
                .put("type", "state")
                .put("tick", tick)
                .put("you", you)
                .put("fog", Base64.getEncoder().encodeToString(sight.toBits()))
                .put("units", units)
                .put("corpses", corpses)
                .put("traps", traps)
                .put("events", events)
                .put("players", scoreboard)
                .toString();
    }

    public static String error(String message) {
        return new JSONObject().put("type", "error").put("message", message).toString();
    }
}
