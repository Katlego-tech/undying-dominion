package com.undyingdominion.bot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's decisions on hand-built states (docs/design/bot.md §4). */
class BotBrainTest {

    private static final int ME = 3;

    private final BotBrain brain = new BotBrain(new JSONObject()
            .put("playerId", ME)
            .put("rules", new JSONObject().put("raiseRange", 6))
            .put("map", new JSONObject().put("width", 40).put("height", 30))
            .put("unitTypes", new JSONObject()
                    .put("NECROMANCER", type(-1, -1, false))
                    .put("SKELETON_WARRIOR", type(20, 15, false))
                    .put("LICH", type(70, 50, false))
                    .put("WOLF", type(-1, 10, true))));

    @Test
    void itSummonsTheCostliestTypeItCanAfford() {
        assertEquals("SKELETON_WARRIOR", command(brain.decide(state(30, 0, units(necromancer(20, 15)), corpses())), "summon").getString("unit"));
        assertEquals("LICH", command(brain.decide(state(80, 0, units(necromancer(20, 15)), corpses())), "summon").getString("unit"));
    }

    @Test
    void withTooLittleSoulToSummonItRaisesTheNearestCorpseInRange() {
        JSONArray corpses = corpses(corpse(1, "WOLF", 30, 15), corpse(2, "WOLF", 24, 15), corpse(3, "WOLF", 22, 15));
        JSONObject raise = command(brain.decide(state(12, 0, units(necromancer(20, 15)), corpses)), "raise");
        assertEquals(3, raise.getInt("corpse"), "the nearest one within six tiles");
    }

    @Test
    void aFullLegionNeitherSummonsNorRaises() {
        JSONObject state = state(100, 0, units(necromancer(20, 15)), corpses(corpse(1, "WOLF", 21, 15)));
        state.getJSONObject("you").put("legion", 10);
        List<JSONObject> commands = brain.decide(state);
        assertTrue(commands.stream().noneMatch(c -> c.getString("command").matches("summon|raise")));
    }

    @Test
    void theLegionAttackMovesOnAnEnemyNecromancerAndOursStepsBackWhenItIsClose() {
        JSONArray units = units(necromancer(20, 15), unit(11, "SKELETON_WARRIOR", ME, 21, 15),
                unit(30, "NECROMANCER", 4, 23, 15));
        List<JSONObject> commands = brain.decide(state(0, 0, units, corpses()));

        JSONObject charge = commands.get(0);
        assertEquals(List.of(11), charge.getJSONArray("units").toList());
        assertEquals(23, charge.getInt("x"));
        assertTrue(charge.getBoolean("attackMove"));
        JSONObject retreat = commands.get(1);
        assertEquals(List.of(10), retreat.getJSONArray("units").toList());
        assertEquals(17, retreat.getInt("x"), "three tiles away from the enemy");
    }

    @Test
    void ordersGoOutAtMostOnceASecond() {
        JSONArray units = units(necromancer(20, 15), unit(11, "SKELETON_WARRIOR", ME, 21, 15));
        assertTrue(hasMove(brain.decide(state(0, 100, units, corpses()))));
        assertTrue(!hasMove(brain.decide(state(0, 105, units, corpses()))));
        assertTrue(hasMove(brain.decide(state(0, 110, units, corpses()))));
    }

    @Test
    void withNothingToFightItWalksTheWaypointsWithTheLegionBehind() {
        JSONArray units = units(necromancer(20, 15), unit(11, "SKELETON_WARRIOR", ME, 21, 15));
        List<JSONObject> commands = brain.decide(state(0, 0, units, corpses()));
        assertEquals(4, commands.get(0).getInt("x"));
        assertEquals(4, commands.get(0).getInt("y"));
        assertEquals(List.of(11), commands.get(1).getJSONArray("units").toList());

        JSONArray arrived = units(necromancer(5, 4), unit(11, "SKELETON_WARRIOR", ME, 6, 4));
        JSONObject next = brain.decide(state(0, 10, arrived, corpses())).get(0);
        assertEquals(35, next.getInt("x"), "on to the top-right waypoint");
    }

    @Test
    void aCommanderCastsItsBasicAtTheNearestEnemyInRangeAndHuntsEnemyCommanders() {
        JSONObject hexfire = new JSONObject().put("id", "hexfire").put("slot", "BASIC").put("target", "ENEMY")
                .put("cost", 12).put("range", 5);
        BotBrain hexer = new BotBrain(new JSONObject()
                .put("playerId", ME)
                .put("rules", new JSONObject().put("raiseRange", 6))
                .put("map", new JSONObject().put("width", 40).put("height", 30))
                .put("unitTypes", new JSONObject().put("HEXER", type(-1, -1, false)).put("CURSED_ARCHER", type(30, -1, false))
                        .put("SKELETON_WARRIOR", type(20, 15, false)))
                .put("commander", new JSONObject().put("unit", new JSONObject().put("name", "HEXER"))
                        .put("retinue", new JSONArray().put(new JSONObject().put("name", "CURSED_ARCHER")))
                        .put("abilities", new JSONArray().put(hexfire)))
                .put("roster", new JSONArray().put(new JSONObject().put("unit", new JSONObject().put("name", "BULWARK")))));
        JSONArray units = units(unit(10, "HEXER", ME, 20, 15), unit(11, "CURSED_ARCHER", ME, 19, 15),
                unit(30, "BULWARK", 4, 24, 15), unit(31, "WOLF", 0, 27, 15));

        List<JSONObject> commands = hexer.decide(state(45, 0, units, corpses()));

        JSONObject cast = command(commands, "cast");
        assertEquals("hexfire", cast.getString("ability"));
        assertEquals(30, cast.getInt("target"));
        assertEquals("CURSED_ARCHER", command(commands, "summon").getString("unit"), "only its retinue, with what's left");
        assertEquals(24, command(commands, "move").getInt("x"), "the legion goes for the enemy commander");

        JSONObject cooling = state(40, 20, units, corpses());
        cooling.getJSONObject("you").put("cooldowns", new JSONObject().put("hexfire", 3.5));
        assertTrue(hexer.decide(cooling).stream().noneMatch(c -> c.getString("command").equals("cast")));
    }

    @Test
    void itDoesNothingWhilePausedOrFallen() {
        JSONObject paused = state(100, 0, units(necromancer(20, 15)), corpses()).put("paused", new JSONObject());
        assertTrue(brain.decide(paused).isEmpty());
        JSONObject fallen = state(100, 0, units(), corpses());
        fallen.getJSONObject("you").put("necromancerId", JSONObject.NULL);
        assertTrue(brain.decide(fallen).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static JSONObject type(int summonCost, int raiseCost, boolean neutral) {
        return new JSONObject().put("summonCost", summonCost).put("raiseCost", raiseCost).put("neutral", neutral);
    }

    private static JSONObject state(int soul, long tick, JSONArray units, JSONArray corpses) {
        return new JSONObject().put("type", "state").put("tick", tick)
                .put("you", new JSONObject().put("soul", soul).put("legion", 1).put("legionCap", 10).put("necromancerId", 10))
                .put("units", units).put("corpses", corpses);
    }

    private static JSONObject necromancer(int x, int y) {
        return unit(10, "NECROMANCER", ME, x, y);
    }

    private static JSONObject unit(int id, String type, int owner, int x, int y) {
        return new JSONObject().put("id", id).put("type", type).put("owner", owner).put("x", x).put("y", y);
    }

    private static JSONObject corpse(int id, String type, int x, int y) {
        return new JSONObject().put("id", id).put("type", type).put("x", x).put("y", y);
    }

    private static JSONArray units(JSONObject... units) {
        return new JSONArray(List.of(units));
    }

    private static JSONArray corpses(JSONObject... corpses) {
        return new JSONArray(List.of(corpses));
    }

    private static JSONObject command(List<JSONObject> commands, String name) {
        return commands.stream().filter(c -> c.getString("command").equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in " + commands));
    }

    private static boolean hasMove(List<JSONObject> commands) {
        return commands.stream().anyMatch(c -> c.getString("command").equals("move"));
    }
}
