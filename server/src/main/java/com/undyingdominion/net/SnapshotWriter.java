package com.undyingdominion.net;

import com.undyingdominion.game.Ability;
import com.undyingdominion.game.Commander;
import com.undyingdominion.game.Corpse;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameEvent;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Status;
import com.undyingdominion.game.StatusEffect;
import com.undyingdominion.game.Trap;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.game.Visibility;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Obstacle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;

/** Builds the JSON messages the server sends. Everything except {@link #welcome} runs on the game loop thread. */
public final class SnapshotWriter {

    /** What a player can't tell about another's unit (docs/design/characters.md §6). */
    private static final Set<Status> HIDDEN_ON_OTHERS = EnumSet.of(Status.EMPOWERED, Status.REGEN, Status.EXPOSED,
            Status.WEAKENED, Status.CLEAVING, Status.STEALTHED);

    private SnapshotWriter() {
    }

    /**
     * Sent when a join or rejoin is accepted and on every chapter change: everything about the world that
     * never changes, and the seat's token for rejoining.
     */
    public static String welcome(int playerId, String name, String token, GameMap map, GameRules rules) {
        return welcome(playerId, name, token, map, rules, null, new JSONArray());
    }

    /**
     * As above, with the player's commander kit ({@code null} before a pick) and the kits they may pick
     * from ({@code []} in a campaign).
     */
    public static String welcome(int playerId, String name, String token, GameMap map, GameRules rules,
                                 JSONObject commander, JSONArray roster) {
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
                        .put("explodeRadius", rules.explodeRadius())
                        .put("explodeTargetRange", rules.explodeTargetRange())
                        .put("explodeDamagePercent", rules.explodeDamagePercent())
                        .put("explodeTargetPercent", rules.explodeTargetPercent())
                        .put("explodeSplitPercent", rules.explodeSplitPercent())
                        .put("explodeSplitMax", rules.explodeSplitMax())
                        .put("maxCorpses", rules.maxCorpses()))
                .put("unitTypes", unitTypes())
                .put("commander", commander == null ? JSONObject.NULL : commander)
                .put("roster", roster)
                .toString();
    }

    /** Every unit type the world knows, as the welcome and {@code GET /roster} describe them. */
    public static JSONObject unitTypes() {
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
        return types;
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
        Visibility sight = engine.sightOf(player);
        int perSecond = engine.getRules().ticksPerSecond();

        JSONArray units = new JSONArray();
        for (Unit u : engine.getUnits()) {
            boolean own = u.getOwnerId() == player.getId();
            if (!sight.shows(u)) continue;
            JSONObject json = new JSONObject()
                    .put("id", u.getId())
                    .put("type", u.getType().name())
                    .put("owner", u.getOwnerId())
                    .put("x", u.getPosition().x())
                    .put("y", u.getPosition().y())
                    .put("hp", u.getHp())
                    .put("order", u.getOrder().name());
            if (u.isRaised()) json.put("raised", true);
            if (u.isRaised() && u.getExpiresAtTick() != Unit.NO_EXPIRY) {
                json.put("expiresIn", Math.max(0, u.getExpiresAtTick() - tick));
            }
            if (u.isRooted()) json.put("modeEndsIn", Math.max(0, u.getModeEndsTick() - tick));
            JSONArray statuses = new JSONArray();
            for (StatusEffect s : u.statuses()) {
                if (!own && HIDDEN_ON_OTHERS.contains(s.status())) continue;
                statuses.put(new JSONObject().put("id", s.status().name().toLowerCase(Locale.ROOT))
                        .put("for", seconds(s.untilTick() - tick, perSecond)));
            }
            units.put(json.put("statuses", statuses).put("shield", u.getShield()));
        }

        JSONArray corpses = new JSONArray();
        for (Corpse c : engine.getCorpses()) {
            if (!sight.sees(c.position())) continue;
            corpses.put(new JSONObject()
                    .put("id", c.id())
                    .put("type", c.type().name())
                    .put("x", c.position().x())
                    .put("y", c.position().y())
                    .put("decaysIn", c.decaysAtTick() == Corpse.NEVER ? JSONObject.NULL
                            : Math.max(0, c.decaysAtTick() - tick)));
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
                case ATTACK, CAST -> sight.sees(e.x(), e.y()) || sight.sees(e.x2(), e.y2());
                default -> sight.sees(e.x(), e.y());
            };
            if (!seen) continue;
            JSONObject event = new JSONObject()
                    .put("kind", e.kind().name())
                    .put("a", e.a()).put("b", e.b())
                    .put("x", e.x()).put("y", e.y())
                    .put("x2", e.x2()).put("y2", e.y2());
            if (e.kind() == GameEvent.Kind.CAST) event.put("ability", e.ability()).put("radius", e.radius());
            events.put(event);
        }

        Commander commander = player.commander();
        JSONObject cooldowns = new JSONObject();
        for (Ability ability : commander.abilities()) {
            cooldowns.put(ability.id(), seconds(player.cooldownLeft(ability.id(), tick), perSecond));
        }
        Commander next = player.nextCommander();
        Integer necromancerId = player.getNecromancerId();
        JSONObject you = new JSONObject()
                .put("id", player.getId())
                .put("soul", player.getSoul())
                .put("soulMax", player.soulMax())
                .put("level", player.getLevel())
                .put("xp", player.getXp())
                .put("xpNext", player.xpForNextLevel())
                .put("legion", engine.legionSizeOf(player))
                .put("raised", engine.raisedSizeOf(player))
                .put("legionCap", player.legionCap())
                .put("necromancerId", necromancerId == null ? JSONObject.NULL : necromancerId)
                .put("respawnIn", necromancerId == null && player.getRespawnAtTick() >= 0
                        ? Math.max(0, player.getRespawnAtTick() - tick) : 0)
                .put("commander", commander.id())
                .put("next", next == null ? JSONObject.NULL : next.id())
                .put("energy", commander.energy())
                .put("cooldowns", cooldowns)
                .put("notices", new JSONArray(player.takeNotices()));
        // The necromancer's Corpse Explosion and the corpses it carries (docs/design/corpse-explosion.md).
        if (commander.role() == Commander.Role.NECROMANCER) {
            JSONArray ossuary = new JSONArray();
            for (UnitType corpse : player.ossuary()) {
                ossuary.put(new JSONObject().put("type", corpse.name()).put("blast", engine.blastOf(player, corpse)));
            }
            you.put("ossuary", ossuary)
                    .put("ossuaryCap", player.ossuaryCap())
                    .put("explodeStage", engine.explodeStage(player).name());
        }

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

    /** Ticks as seconds to one decimal place. */
    private static double seconds(long ticks, int perSecond) {
        return Math.max(0, Math.round(ticks * 10.0 / perSecond) / 10.0);
    }

    public static String error(String message) {
        return new JSONObject().put("type", "error").put("message", message).toString();
    }
}
