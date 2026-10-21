package com.undyingdominion.bot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what the sample bot does with each state. It knows only what the protocol says: the
 * welcome's map, rules and unit types, and each state's view of the world (docs/design/bot.md §4).
 */
public final class BotBrain {

    /** Orders go out at most this often, in ticks, so the bot doesn't flood the server. */
    static final int ORDER_EVERY = 10;
    /** How far from the necromancer a wild creature or summon is worth chasing. */
    static final int HUNT_RANGE = 12;
    /** A target this close makes the necromancer step back. */
    static final int TOO_CLOSE = 4;
    private static final int WAYPOINT_INSET = 4;

    private final int playerId;
    /** Summonable types, costliest first. */
    private final List<String> summonable = new ArrayList<>();
    private final Map<String, Integer> summonCosts = new HashMap<>();
    private final Map<String, Integer> raiseCosts = new HashMap<>();
    private final int raiseRange;
    private final int width;
    private final int height;

    private long lastOrderTick = Long.MIN_VALUE / 2;
    private int waypoint;

    public BotBrain(JSONObject welcome) {
        playerId = welcome.getInt("playerId");
        raiseRange = welcome.getJSONObject("rules").getInt("raiseRange");
        JSONObject map = welcome.getJSONObject("map");
        width = map.getInt("width");
        height = map.getInt("height");
        JSONObject types = welcome.getJSONObject("unitTypes");
        for (String type : types.keySet()) {
            JSONObject stats = types.getJSONObject(type);
            if (stats.getInt("summonCost") >= 0 && !stats.getBoolean("neutral")) {
                summonable.add(type);
                summonCosts.put(type, stats.getInt("summonCost"));
            }
            raiseCosts.put(type, stats.getInt("raiseCost"));
        }
        summonable.sort(Comparator.comparing((String type) -> summonCosts.get(type)).reversed());
    }

    /** The commands to send for this state; often none. */
    public List<JSONObject> decide(JSONObject state) {
        List<JSONObject> commands = new ArrayList<>();
        JSONObject you = state.getJSONObject("you");
        if (state.has("paused") || you.isNull("necromancerId")) return commands;

        JSONArray units = state.getJSONArray("units");
        JSONObject necromancer = unit(units, you.getInt("necromancerId"));
        if (necromancer == null) return commands;
        int soul = you.getInt("soul");
        boolean room = you.getInt("legion") < you.getInt("legionCap");

        String summon = room ? affordableSummon(soul) : null;
        if (summon != null) {
            commands.add(new JSONObject().put("command", "summon").put("unit", summon));
        } else if (room) {
            JSONObject corpse = raisableCorpse(state.getJSONArray("corpses"), necromancer, soul);
            if (corpse != null) commands.add(new JSONObject().put("command", "raise").put("corpse", corpse.getInt("id")));
        }

        long tick = state.getLong("tick");
        if (tick - lastOrderTick >= ORDER_EVERY) {
            lastOrderTick = tick;
            commands.addAll(orders(units, necromancer));
        }
        return commands;
    }

    private String affordableSummon(int soul) {
        for (String type : summonable) {
            if (summonCosts.get(type) <= soul) return type;
        }
        return null;
    }

    private JSONObject raisableCorpse(JSONArray corpses, JSONObject necromancer, int soul) {
        JSONObject best = null;
        for (int i = 0; i < corpses.length(); i++) {
            JSONObject corpse = corpses.getJSONObject(i);
            int cost = raiseCosts.getOrDefault(corpse.getString("type"), -1);
            int distance = distance(corpse, necromancer);
            if (cost < 0 || cost > soul || distance > raiseRange) continue;
            if (best == null || distance < distance(best, necromancer)) best = corpse;
        }
        return best;
    }

    private List<JSONObject> orders(JSONArray units, JSONObject necromancer) {
        List<JSONObject> orders = new ArrayList<>();
        List<Integer> legion = new ArrayList<>();
        JSONObject enemyNecromancer = null;
        JSONObject nearestHostile = null;
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.getJSONObject(i);
            if (unit.getInt("owner") == playerId) {
                if (unit.getInt("id") != necromancer.getInt("id")) legion.add(unit.getInt("id"));
                continue;
            }
            int distance = distance(unit, necromancer);
            if (unit.getString("type").equals("NECROMANCER")) {
                if (enemyNecromancer == null || distance < distance(enemyNecromancer, necromancer)) enemyNecromancer = unit;
            } else if (distance <= HUNT_RANGE
                    && (nearestHostile == null || distance < distance(nearestHostile, necromancer))) {
                nearestHostile = unit;
            }
        }
        JSONObject target = enemyNecromancer != null ? enemyNecromancer : nearestHostile;

        if (target != null && !legion.isEmpty()) {
            orders.add(move(legion, target.getInt("x"), target.getInt("y"), true));
            if (distance(target, necromancer) <= TOO_CLOSE) {
                int x = necromancer.getInt("x");
                int y = necromancer.getInt("y");
                orders.add(move(List.of(necromancer.getInt("id")),
                        clamp(x + 3 * Integer.signum(x - target.getInt("x")), width),
                        clamp(y + 3 * Integer.signum(y - target.getInt("y")), height), false));
            }
            return orders;
        }

        int[] goal = waypoint();
        if (Math.max(Math.abs(goal[0] - necromancer.getInt("x")), Math.abs(goal[1] - necromancer.getInt("y"))) <= 2) {
            waypoint = (waypoint + 1) % 4;
            goal = waypoint();
        }
        orders.add(move(List.of(necromancer.getInt("id")), goal[0], goal[1], false));
        if (!legion.isEmpty()) orders.add(move(legion, necromancer.getInt("x"), necromancer.getInt("y"), true));
        return orders;
    }

    /** The four corners, inset, clockwise from the top left. */
    private int[] waypoint() {
        int left = Math.min(WAYPOINT_INSET, width - 1);
        int top = Math.min(WAYPOINT_INSET, height - 1);
        int right = Math.max(0, width - 1 - WAYPOINT_INSET);
        int bottom = Math.max(0, height - 1 - WAYPOINT_INSET);
        return switch (waypoint) {
            case 0 -> new int[] {left, top};
            case 1 -> new int[] {right, top};
            case 2 -> new int[] {right, bottom};
            default -> new int[] {left, bottom};
        };
    }

    private static JSONObject move(List<Integer> units, int x, int y, boolean attackMove) {
        return new JSONObject().put("command", "move").put("units", new JSONArray(units))
                .put("x", x).put("y", y).put("attackMove", attackMove);
    }

    private static JSONObject unit(JSONArray units, int id) {
        for (int i = 0; i < units.length(); i++) {
            if (units.getJSONObject(i).getInt("id") == id) return units.getJSONObject(i);
        }
        return null;
    }

    private static int distance(JSONObject a, JSONObject b) {
        return Math.max(Math.abs(a.getInt("x") - b.getInt("x")), Math.abs(a.getInt("y") - b.getInt("y")));
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
