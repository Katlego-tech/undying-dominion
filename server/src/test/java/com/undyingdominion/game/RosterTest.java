package com.undyingdominion.game;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The template every kit is drawn to (docs/design/characters.md §7.1), enforced so a kit can't quietly drift. */
class RosterTest {

    /** Role base rows: hp, damage, range, attack cd, move cd, aggro, vision. */
    private static final Map<Commander.Role, int[]> BASE = Map.of(
            Commander.Role.SUMMONER,   new int[] {200,  8, 3, 12, 4, 3, 8},
            Commander.Role.CASTER,     new int[] {180, 12, 4, 12, 4, 4, 8},
            Commander.Role.FRONT_LINE, new int[] {320, 11, 1, 10, 5, 4, 7},
            Commander.Role.FIGHTER,    new int[] {260, 15, 1, 10, 4, 4, 7},
            Commander.Role.ASSASSIN,   new int[] {180, 18, 1,  9, 3, 3, 8},
            Commander.Role.SUPPORT,    new int[] {200,  7, 3, 12, 4, 3, 8});

    private final Roster roster = Roster.builtIn();

    @Test
    void nineteenCommandersCanBePickedAndTheNecromancerStaysInTheCampaign() {
        assertEquals(19, roster.pvp().size());
        Commander necromancer = roster.necromancer();
        assertTrue(necromancer.campaignOnly());
        assertEquals(Commander.Role.NECROMANCER, necromancer.role());
        assertTrue(roster.pvp().stream().noneMatch(c -> c.role() == Commander.Role.NECROMANCER));
        Set<String> ids = new HashSet<>();
        roster.pvp().forEach(c -> assertTrue(ids.add(c.id()), "duplicate " + c.id()));
    }

    @Test
    void everyKitHasOneAbilityPerSlotAndAPassive() {
        for (Commander c : roster.pvp()) {
            EnumSet<Ability.Slot> slots = EnumSet.noneOf(Ability.Slot.class);
            c.abilities().forEach(a -> slots.add(a.slot()));
            assertEquals(3, c.abilities().size(), c.id());
            assertEquals(EnumSet.allOf(Ability.Slot.class), slots, c.id());
            assertNotNull(c.passive(), c.id());
        }
    }

    @Test
    void costsAndCooldownsSitInsideTheirSlotsBands() {
        for (Commander c : roster.pvp()) {
            for (Ability a : c.abilities()) {
                int[] band = switch (a.slot()) {
                    case BASIC -> new int[] {10, 15, 4, 10};
                    case UTILITY -> new int[] {15, 25, 10, 20};
                    case SIGNATURE -> new int[] {30, 50, 40, 90};
                };
                String where = c.id() + "." + a.id();
                assertTrue(a.cost() >= band[0] && a.cost() <= band[1], where + " cost " + a.cost());
                int seconds = a.cooldownTicks() / 10;
                assertTrue(seconds >= band[2] && seconds <= band[3], where + " cooldown " + seconds);
            }
        }
    }

    @Test
    void everyCommanderUnitIsItsRolesRowTradedFairly() {
        for (Commander c : roster.pvp()) {
            int[] row = BASE.get(c.role());
            UnitType u = c.unit();
            assertTrue(u.isCommander(), c.id());
            assertEquals(row[3], u.attackCooldown, c.id() + " attack cd is not traded");
            assertEquals(row[5], u.aggroRange, c.id() + " aggro");
            assertEquals(row[6], u.visionRange, c.id() + " vision");
            assertFairTrade(c.id(), row[0], row[1], 1, row[2], row[4], u.maxHp, u.damage, 1, u.range, u.moveCooldown);
        }
    }

    @Test
    void everyRetinueUnitIsItsArchetypeTradedFairly() throws IOException {
        JSONArray raw = rawRoster();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject c = raw.getJSONObject(i);
            if (c.getBoolean("campaignOnly")) continue;
            JSONArray retinue = c.getJSONArray("retinue");
            for (int j = 0; j < retinue.length(); j++) {
                JSONObject entry = retinue.getJSONObject(j);
                Roster.Archetype a = Roster.Archetype.valueOf(entry.getString("archetype"));
                UnitType u = UnitType.valueOf(entry.getString("name"));
                String where = c.getString("id") + "." + u.name();
                assertEquals(a.attackCooldown, u.attackCooldown, where);
                assertEquals(a.splash, u.splashRadius, where);
                assertEquals(Roster.RETINUE_AGGRO, u.aggroRange, where);
                assertEquals(Roster.RETINUE_VISION, u.visionRange, where);
                assertEquals(-1, u.raiseCost, where);
                assertTrue(!u.neutral && !u.isCommander(), where);
                assertFairTrade(where, a.hp, a.damage, a.cost, a.range, a.moveCooldown,
                        u.maxHp, u.damage, u.summonCost, u.range, u.moveCooldown);
            }
        }
    }

    @Test
    void summonersCallThreeKindsAndEveryoneElseTwo() {
        for (Commander c : roster.pvp()) {
            int expected = c.role() == Commander.Role.SUMMONER ? 3 : 2;
            assertEquals(expected, c.retinue().size(), c.id());
            c.retinue().forEach(t -> assertTrue(t.isSummonable(), c.id() + "." + t.name()));
        }
    }

    @Test
    void aSummonEffectPlacesAtMostTwiceItsCost() {
        for (Commander c : roster.pvp()) {
            for (Ability a : c.abilities()) {
                for (Effect e : a.effects()) {
                    if (e instanceof Effect.Summon s && s.unit().isSummonable()) {
                        assertTrue(s.unit().summonCost * s.count() <= 2 * a.cost(), c.id() + "." + a.id());
                    }
                }
            }
        }
    }

    @Test
    void theHexerIsTheDesignsEntryToTheLetter() {
        Commander hexer = roster.get("HEXER");
        assertEquals("Morrow", hexer.name());
        assertEquals("the Hexer", hexer.title());
        assertEquals("Mana", hexer.energy());
        assertEquals("Call", hexer.verb());
        assertEquals(List.of("HEXBOUND_THRALL", "CURSED_ARCHER"), hexer.retinue().stream().map(UnitType::name).toList());
        assertEquals(new Ability("hexfire", "Hexfire", Ability.Slot.BASIC, Ability.Target.ENEMY, 12, 60, 5, 0,
                Ability.Affects.TARGET, List.of(new Effect.Damage(22, 0, null),
                        new Effect.Apply(Status.EXPOSED, 15, 40, 0, null))), hexer.ability("hexfire"));
        Ability seal = hexer.ability("summoning-seal");
        assertEquals(Ability.Target.ENEMY_COMMANDER, seal.target());
        assertEquals(List.of(new Effect.Apply(Status.SEALED, 0, 120, 0, null),
                new Effect.Apply(Status.SILENCED, 0, 30, 0, null)), seal.effects());
        assertEquals(new Passive("hatred-of-summons", "Hatred of Summons", Passive.Kind.BONUS_VS, null, 20, 0,
                Ability.Affects.CASTER, "RETINUE"), hexer.passive());
        assertNull(hexer.ability("raise"));
    }

    @Test
    void mixedTargetsAndDelayedStatusesReadAsWritten() {
        Ability ironStance = roster.get("BULWARK").ability("iron-stance");
        assertEquals(Ability.Affects.ENEMIES, ironStance.affects());
        assertEquals(new Effect.Apply(Status.ROOTED, 0, 60, 0, Ability.Affects.CASTER), ironStance.effects().get(2));
        assertEquals(new Effect.Taunt(40, false, null), ironStance.effects().get(3));

        Ability berserk = roster.get("BERSERKER").ability("berserk");
        assertEquals(new Effect.Apply(Status.WEAKENED, 30, 80, 100, null), berserk.effects().get(2));

        Ability decoy = roster.get("PAPER_BINDER").ability("paper-double");
        Effect.Summon summon = (Effect.Summon) decoy.effects().get(0);
        assertEquals("PAPER_DOUBLE", summon.unit().name());
        assertEquals(80, summon.ticks());
        assertTrue(!summon.unit().isSummonable() && !summon.unit().canAttack());
        assertEquals(new Effect.Taunt(40, true, null), decoy.effects().get(1));

        assertEquals(new Passive("kill-the-summoner", "Kill the Summoner", Passive.Kind.BONUS_VS, null, 15, 0,
                Ability.Affects.OWN, "COMMANDER"), roster.get("WARD_MAGE").passive());
        assertEquals(Passive.Kind.SEES_STEALTH, roster.get("DAWN_RIDER").passive().kind());
        assertEquals(4, roster.get("SHADE").passive().radius());
    }

    @Test
    void theNecromancerIsTodaysNecromancer() {
        Commander n = roster.necromancer();
        assertEquals(UnitType.NECROMANCER, n.unit());
        assertTrue(UnitType.NECROMANCER.isCommander());
        assertEquals(List.of(UnitType.SKELETON_WARRIOR, UnitType.SKELETON_ARCHER, UnitType.LICH, UnitType.GRAVE_WARDEN),
                n.retinue());
        assertEquals(new Effect.Native(Effect.Native.Kind.RAISE), n.ability("raise").effects().get(0));
        assertEquals(new Effect.Native(Effect.Native.Kind.FUSE), n.ability("fuse").effects().get(0));
        assertEquals(new Effect.Native(Effect.Native.Kind.DETONATE), n.ability("explode").effects().get(0));
        assertEquals(Passive.Kind.SOUL_HARVEST, n.passive().kind());
        assertEquals(60, UnitType.SKELETON_WARRIOR.maxHp);
        assertEquals(20, UnitType.SKELETON_WARRIOR.summonCost);
    }

    @Test
    void loadingTheBuiltInRosterRegistersEveryUnitType() {
        for (Commander c : roster.pvp()) {
            assertEquals(c.unit(), UnitType.valueOf(c.unit().name()));
            c.retinue().forEach(t -> assertEquals(t, UnitType.valueOf(t.name())));
            c.units().forEach(t -> assertEquals(t, UnitType.valueOf(t.name())));
        }
        assertEquals(20, UnitType.valueOf("HEXBOUND_THRALL").summonCost);
    }

    @Test
    void lookUpAndRandomPick() {
        assertNull(roster.get("NOBODY"));
        Random random = new Random(7);
        for (int i = 0; i < 50; i++) assertTrue(roster.pvp().contains(roster.random(random)));
    }

    @Test
    void aMalformedRosterIsRefusedNamingTheCommanderAndTheField() throws IOException {
        assertRefused("roster.json: HEXER.abilities[2].effects[0]: unknown status SEAL",
                h -> effect(h, 2, 0).put("status", "SEAL"));
        assertRefused("roster.json: HEXER.abilities[0].effects[1]: unknown kind curse",
                h -> effect(h, 0, 1).put("kind", "curse"));
        assertRefused("roster.json: HEXER.abilities[1]: unknown target FOE",
                h -> h.getJSONArray("abilities").getJSONObject(1).put("target", "FOE"));
        assertRefused("roster.json: HEXER.retinue[0]: unknown archetype BRUTE",
                h -> h.getJSONArray("retinue").getJSONObject(0).put("archetype", "BRUTE"));
        assertRefused("roster.json: HEXER.abilities: needs one BASIC, one UTILITY and one SIGNATURE",
                h -> h.getJSONArray("abilities").getJSONObject(1).put("slot", "BASIC"));
        assertRefused("roster.json: HEXER.abilities: needs one BASIC, one UTILITY and one SIGNATURE",
                h -> h.getJSONArray("abilities").remove(2));
        assertRefused("roster.json: HEXER.retinue: needs 2 or 3 units, has 1",
                h -> h.getJSONArray("retinue").remove(1));
        assertRefused("roster.json: HEXER.abilities[0]: missing cost",
                h -> h.getJSONArray("abilities").getJSONObject(0).remove("cost"));
        assertRefused("roster.json: HEXER.retinue[1]: unit name HEXBOUND_THRALL is taken",
                h -> h.getJSONArray("retinue").getJSONObject(1).put("name", "HEXBOUND_THRALL"));

        JSONObject hexer = hexer();
        JSONArray twice = new JSONArray().put(hexer).put(new JSONObject(hexer.toString()));
        renameUnits(twice.getJSONObject(1), "_TWO");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(twice));
        assertEquals("roster.json: HEXER: duplicate id", e.getMessage());
    }

    @Test
    void aWellFormedEntryLoadsWithoutRegisteringAnything() throws IOException {
        JSONObject hexer = hexer();
        renameUnits(hexer, "_UNREGISTERED");
        Roster one = load(new JSONArray().put(hexer));
        assertEquals("Morrow", one.get("HEXER").name());
        assertThrows(IllegalArgumentException.class, () -> UnitType.valueOf("HEXBOUND_THRALL_UNREGISTERED"));
    }

    private static void assertFairTrade(String where, int hp0, int dmg0, int cost0, int range0, int move0,
                                        int hp, int dmg, int cost, int range, int move) {
        double hpRatio = (double) hp / hp0, dmgRatio = (double) dmg / dmg0, costRatio = (double) cost / cost0;
        for (double ratio : new double[] {hpRatio, dmgRatio, costRatio}) {
            assertTrue(ratio >= 0.75 - 1e-9 && ratio <= 1.25 + 1e-9, where + " moves a stat by " + ratio);
        }
        double value = hpRatio * dmgRatio / costRatio * step(range - range0) * step(move0 - move);
        assertTrue(value >= 0.9 - 1e-9 && value <= 1.1 + 1e-9, where + " value " + value);
    }

    /** Each step better is × 1.15, each step worse × 0.85. */
    private static double step(int better) {
        return better >= 0 ? Math.pow(1.15, better) : Math.pow(0.85, -better);
    }

    private static JSONArray rawRoster() throws IOException {
        try (InputStream in = RosterTest.class.getResourceAsStream("/commanders/roster.json")) {
            return new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static JSONObject hexer() throws IOException {
        JSONArray raw = rawRoster();
        for (int i = 0; i < raw.length(); i++) {
            if (raw.getJSONObject(i).getString("id").equals("HEXER")) return raw.getJSONObject(i);
        }
        throw new AssertionError("no HEXER in roster.json");
    }

    private static JSONObject effect(JSONObject commander, int ability, int effect) {
        return commander.getJSONArray("abilities").getJSONObject(ability).getJSONArray("effects").getJSONObject(effect);
    }

    private static void renameUnits(JSONObject commander, String suffix) {
        JSONObject unit = commander.getJSONObject("unit");
        unit.put("name", unit.getString("name") + suffix);
        JSONArray retinue = commander.getJSONArray("retinue");
        for (int i = 0; i < retinue.length(); i++) {
            retinue.getJSONObject(i).put("name", retinue.getJSONObject(i).getString("name") + suffix);
        }
    }

    private static Roster load(JSONArray array) {
        return Roster.load(new ByteArrayInputStream(array.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRefused(String message, Consumer<JSONObject> breakIt) throws IOException {
        JSONObject hexer = hexer();
        breakIt.accept(hexer);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(new JSONArray().put(hexer)));
        assertEquals(message, e.getMessage());
    }
}
