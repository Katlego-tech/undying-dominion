package com.undyingdominion.game;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Every commander a player can field, read from {@code commanders/roster.json}. The file is checked
 * when it's loaded, and a mistake stops the server with a message naming the commander and the field,
 * e.g. {@code roster.json: HEXER.abilities[2].effects[0]: unknown status SEAL}.
 *
 * <p>A unit written as a string names a type that already exists (the necromancer's skeletons). A
 * retinue unit written as an object starts from an {@link Archetype} and changes only the fields it
 * lists.
 */
public final class Roster {

    /** The retinue templates, from docs/design/characters.md §7.1. */
    public enum Archetype {
        //     cost  hp dmg rng atkCd mvCd splash soul xp chases
        GRUNT(  20,  60, 10, 1,   8,   4,   0,    6,  8, true),
        SHOT(   30,  35,  7, 5,  12,   4,   0,    8, 10, false),
        ADEPT(  70,  75, 16, 4,  20,   6,   1,   10, 10, true),
        SCOUT(  15,  30,  6, 1,   7,   3,   0,    6,  8, true),
        GUARD(  35, 110,  8, 1,  10,   5,   0,    8, 10, true);

        public final int cost, hp, damage, range, attackCooldown, moveCooldown, splash, soulValue, xpValue;
        public final boolean chases;

        Archetype(int cost, int hp, int damage, int range, int attackCooldown, int moveCooldown, int splash,
                  int soulValue, int xpValue, boolean chases) {
            this.cost = cost;
            this.hp = hp;
            this.damage = damage;
            this.range = range;
            this.attackCooldown = attackCooldown;
            this.moveCooldown = moveCooldown;
            this.splash = splash;
            this.soulValue = soulValue;
            this.xpValue = xpValue;
            this.chases = chases;
        }
    }

    public static final int RETINUE_AGGRO = 5;
    public static final int RETINUE_VISION = 6;
    private static final int COMMANDER_SOUL_VALUE = 40;
    private static final int COMMANDER_XP_VALUE = 100;
    private static final int TICKS_PER_SECOND = GameRules.defaults().ticksPerSecond();
    private static final String RESOURCE = "/commanders/roster.json";

    private final List<Commander> all;
    private final Map<String, Commander> byId = new LinkedHashMap<>();
    /** Each kit as the file wrote it, which is how the protocol sends it. */
    private final Map<String, JSONObject> kits;

    private Roster(List<Commander> all, Map<String, JSONObject> kits) {
        this.all = List.copyOf(all);
        this.kits = kits;
        for (Commander commander : all) byId.put(commander.id(), commander);
    }

    private static final class BuiltIn {
        static final Roster ROSTER = loadBuiltIn();
    }

    /** The roster shipped with the game. The first call registers every commander's unit types. */
    public static Roster builtIn() {
        return BuiltIn.ROSTER;
    }

    private static Roster loadBuiltIn() {
        try (InputStream in = Roster.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("roster.json is missing from the classpath");
            Roster roster = load(in);
            for (Commander commander : roster.all) {
                UnitType.register(commander.unit());
                commander.retinue().forEach(UnitType::register);
                commander.units().forEach(UnitType::register);
            }
            return roster;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads and checks a roster without registering anything. */
    public static Roster load(InputStream json) {
        JSONArray array;
        try {
            array = new JSONArray(new String(json.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JSONException e) {
            throw new IllegalArgumentException("roster.json: " + e.getMessage(), e);
        }
        List<Commander> commanders = new ArrayList<>();
        Map<String, JSONObject> kits = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        Set<String> unitNames = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            Commander commander = new Reader(unitNames).commander(array, i);
            if (!ids.add(commander.id())) throw new IllegalArgumentException("roster.json: " + commander.id() + ": duplicate id");
            commanders.add(commander);
            kits.put(commander.id(), array.getJSONObject(i));
        }
        return new Roster(commanders, kits);
    }

    /** The commander with this id, or null if there's none. */
    public Commander get(String id) {
        return byId.get(id);
    }

    /** The commanders a player may pick outside the campaign, in file order. */
    public List<Commander> pvp() {
        return all.stream().filter(c -> !c.campaignOnly()).toList();
    }

    /** The commander's kit as roster.json has it, for the protocol. Shared: don't change it. */
    public JSONObject kit(Commander commander) {
        return kits.get(commander.id());
    }

    /** Every pickable kit, as {@link #kit}. */
    public JSONArray pvpKits() {
        JSONArray out = new JSONArray();
        for (Commander commander : pvp()) out.put(kits.get(commander.id()));
        return out;
    }

    public Commander necromancer() {
        return byId.get("NECROMANCER");
    }

    /** One of {@link #pvp()}, for a player who joins without picking. */
    public Commander random(Random random) {
        List<Commander> pvp = pvp();
        return pvp.get(random.nextInt(pvp.size()));
    }

    /** Turns one commander object into the model, keeping track of where it is for error messages. */
    private static final class Reader {
        private final Set<String> unitNames;
        private String path;

        Reader(Set<String> unitNames) {
            this.unitNames = unitNames;
        }

        Commander commander(JSONArray array, int index) {
            path = "[" + index + "]";
            JSONObject c = object(array, index);
            String id = string(c, "id");
            path = id;
            Commander.Role role = enumValue(Commander.Role.class, string(c, "role"), "role");
            boolean campaignOnly = c.optBoolean("campaignOnly", false);
            String name = string(c, "name");

            at(id + ".unit");
            UnitType unit = c.opt("unit") instanceof String ref ? existing(ref) : commanderUnit(object(c, "unit"));

            Map<String, UnitType> placeable = new LinkedHashMap<>();
            List<UnitType> retinue = units(c, id, "retinue", true, placeable);
            List<UnitType> units = units(c, id, "units", false, placeable);
            if (!campaignOnly && (retinue.size() < 2 || retinue.size() > 3)) {
                at(id + ".retinue");
                throw refuse("needs 2 or 3 units, has " + retinue.size());
            }

            JSONArray list = array(c, "abilities", id);
            List<Ability> abilities = new ArrayList<>();
            Set<String> abilityIds = new HashSet<>();
            for (int i = 0; i < list.length(); i++) {
                at(id + ".abilities[" + i + "]");
                Ability ability = ability(object(list, i), placeable);
                if (!abilityIds.add(ability.id())) throw refuse("duplicate id " + ability.id());
                abilities.add(ability);
            }
            if (!campaignOnly) {
                EnumSet<Ability.Slot> slots = EnumSet.noneOf(Ability.Slot.class);
                for (Ability ability : abilities) slots.add(ability.slot());
                if (abilities.size() != 3 || slots.size() != 3) {
                    at(id + ".abilities");
                    throw refuse("needs one BASIC, one UTILITY and one SIGNATURE");
                }
            }

            at(id + ".passive");
            Passive passive = passive(object(c, "passive"));
            at(id);
            return new Commander(id, name, string(c, "title"), role, string(c, "blurb"), unit, string(c, "energy"),
                    string(c, "verb"), retinue, units, abilities, passive, campaignOnly);
        }

        private UnitType commanderUnit(JSONObject u) {
            String name = unitName(u);
            return UnitType.builder(name, string(u, "label"))
                    .stats(integer(u, "hp"), integer(u, "damage"), integer(u, "range"),
                            integer(u, "attackCooldown"), integer(u, "moveCooldown"))
                    .senses(integer(u, "aggro"), integer(u, "vision"))
                    .costs(-1, -1)
                    .rewards(COMMANDER_SOUL_VALUE, COMMANDER_XP_VALUE)
                    .look(look(u))
                    .commander(true)
                    .chasesWhenIdle(false)
                    .neutral(false)
                    .build();
        }

        private List<UnitType> units(JSONObject c, String id, String key, boolean summonable,
                                     Map<String, UnitType> placeable) {
            List<UnitType> types = new ArrayList<>();
            JSONArray list = c.optJSONArray(key);
            if (list == null) {
                if (summonable) {
                    at(id);
                    throw refuse("missing " + key);
                }
                return types;
            }
            for (int i = 0; i < list.length(); i++) {
                at(id + "." + key + "[" + i + "]");
                UnitType type = list.opt(i) instanceof String ref ? existing(ref) : archetypeUnit(object(list, i), summonable);
                types.add(type);
                placeable.put(type.name(), type);
            }
            return types;
        }

        private UnitType archetypeUnit(JSONObject u, boolean summonable) {
            String name = unitName(u);
            Archetype a = enumValue(Archetype.class, string(u, "archetype"), "archetype");
            int cost = u.has("cost") ? integer(u, "cost") : a.cost;
            return UnitType.builder(name, string(u, "label"))
                    .stats(u.has("hp") ? integer(u, "hp") : a.hp, u.has("damage") ? integer(u, "damage") : a.damage,
                            u.has("range") ? integer(u, "range") : a.range, a.attackCooldown,
                            u.has("moveCooldown") ? integer(u, "moveCooldown") : a.moveCooldown)
                    .senses(RETINUE_AGGRO, RETINUE_VISION)
                    .costs(summonable ? cost : -1, -1)
                    .rewards(a.soulValue, a.xpValue)
                    .splash(a.splash)
                    .look(look(u))
                    .chasesWhenIdle(a.chases)
                    .neutral(false)
                    .build();
        }

        private String unitName(JSONObject u) {
            String name = string(u, "name");
            if (!name.matches("[A-Z][A-Z0-9_]*")) throw refuse("unit names are UPPER_SNAKE_CASE, got " + name);
            if (!unitNames.add(name)) throw refuse("unit name " + name + " is taken");
            return name;
        }

        private UnitType existing(String name) {
            try {
                return UnitType.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw refuse("unknown unit " + name);
            }
        }

        private UnitType.Look look(JSONObject u) {
            return u.has("look") ? enumValue(UnitType.Look.class, string(u, "look"), "look") : UnitType.Look.HUMANOID;
        }

        private Ability ability(JSONObject a, Map<String, UnitType> placeable) {
            String abilityPath = path;
            String id = string(a, "id");
            Ability.Slot slot = enumValue(Ability.Slot.class, string(a, "slot"), "slot");
            Ability.Target target = enumValue(Ability.Target.class, string(a, "target"), "target");
            Ability.Affects affects = a.has("affects") ? affects(a) : Ability.Affects.TARGET;
            JSONArray list = array(a, "effects", abilityPath);
            if (list.isEmpty()) throw refuse("needs at least one effect");
            List<Effect> effects = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                at(abilityPath + ".effects[" + i + "]");
                effects.add(effect(object(list, i), placeable));
            }
            at(abilityPath);
            return new Ability(id, string(a, "label"), slot, target, integer(a, "cost"), ticks(a, "cooldown"),
                    a.has("range") ? integer(a, "range") : 0, a.has("radius") ? integer(a, "radius") : 0,
                    affects, effects);
        }

        private Effect effect(JSONObject e, Map<String, UnitType> placeable) {
            Ability.Affects affects = e.has("affects") ? affects(e) : null;
            String kind = string(e, "kind");
            return switch (kind) {
                case "damage" -> new Effect.Damage(integer(e, "amount"), e.has("stealthBonus") ? integer(e, "stealthBonus") : 0, affects);
                case "heal" -> new Effect.Heal(integer(e, "amount"), affects);
                case "shield" -> new Effect.Shield(integer(e, "amount"), ticks(e, "seconds"), affects);
                case "status" -> new Effect.Apply(enumValue(Status.class, string(e, "status"), "status"),
                        e.has("magnitude") ? integer(e, "magnitude") : 0, ticks(e, "seconds"),
                        e.has("afterSeconds") ? ticks(e, "afterSeconds") : 0, affects);
                case "taunt" -> new Effect.Taunt(ticks(e, "seconds"), toward(e), affects);
                case "dash" -> new Effect.Dash(integer(e, "tiles"), e.has("damage") ? integer(e, "damage") : 0,
                        e.optBoolean("overTerrain", false));
                case "push" -> new Effect.Push(integer(e, "tiles"), affects);
                case "summon" -> {
                    String unit = string(e, "unit");
                    UnitType type = placeable.get(unit);
                    if (type == null) throw refuse("unknown unit " + unit);
                    yield new Effect.Summon(type, integer(e, "count"), e.has("seconds") ? ticks(e, "seconds") : 0);
                }
                case "cleanse" -> new Effect.Cleanse(affects);
                case "resetCooldowns" -> new Effect.ResetCooldowns();
                case "reveal" -> new Effect.Reveal(ticks(e, "seconds"));
                case "raise" -> new Effect.Native(Effect.Native.Kind.RAISE);
                case "detonate" -> new Effect.Native(Effect.Native.Kind.DETONATE);
                case "fuse" -> new Effect.Native(Effect.Native.Kind.FUSE);
                default -> throw refuse("unknown kind " + kind);
            };
        }

        private boolean toward(JSONObject e) {
            String toward = string(e, "toward");
            return switch (toward) {
                case "caster" -> false;
                case "summoned" -> true;
                default -> throw refuse("unknown toward " + toward);
            };
        }

        private Passive passive(JSONObject p) {
            Passive.Kind kind = enumValue(Passive.Kind.class, snake(string(p, "kind")), "passive kind");
            Status status = p.has("status") ? enumValue(Status.class, string(p, "status"), "status") : null;
            if (status == null && (kind == Passive.Kind.AURA || kind == Passive.Kind.SELF || kind == Passive.Kind.IMMUNE)) {
                throw refuse("missing status");
            }
            String against = null;
            Ability.Affects affects = p.has("affects") ? affects(p) : null;
            if (kind == Passive.Kind.BONUS_VS) {
                against = string(p, "against");
                if (!against.equals("RETINUE") && !against.equals("COMMANDER")) throw refuse("unknown against " + against);
                affects = "OWN".equals(p.optString("who", null)) ? Ability.Affects.OWN : Ability.Affects.CASTER;
            }
            return new Passive(string(p, "id"), string(p, "label"), kind, status,
                    p.has("magnitude") ? integer(p, "magnitude") : 0, p.has("radius") ? integer(p, "radius") : 0,
                    affects, against);
        }

        private Ability.Affects affects(JSONObject o) {
            return enumValue(Ability.Affects.class, string(o, "affects"), "affects");
        }

        /** "seesStealth" to "SEES_STEALTH". */
        private static String snake(String camel) {
            return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        }

        private int ticks(JSONObject o, String key) {
            if (!o.has(key)) throw refuse("missing " + key);
            try {
                return (int) Math.round(o.getDouble(key) * TICKS_PER_SECOND);
            } catch (JSONException e) {
                throw refuse(key + " is not a number");
            }
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, String value, String what) {
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException e) {
                throw refuse("unknown " + what + " " + value);
            }
        }

        private String string(JSONObject o, String key) {
            if (!o.has(key)) throw refuse("missing " + key);
            Object value = o.get(key);
            if (!(value instanceof String s)) throw refuse(key + " is not a string");
            return s;
        }

        private int integer(JSONObject o, String key) {
            if (!o.has(key)) throw refuse("missing " + key);
            try {
                return o.getInt(key);
            } catch (JSONException e) {
                throw refuse(key + " is not a number");
            }
        }

        private JSONObject object(JSONObject o, String key) {
            JSONObject value = o.optJSONObject(key);
            if (value == null) throw refuse(o.has(key) ? key + " is not an object" : "missing " + key);
            return value;
        }

        private JSONObject object(JSONArray array, int index) {
            JSONObject value = array.optJSONObject(index);
            if (value == null) throw refuse("is not an object");
            return value;
        }

        private JSONArray array(JSONObject o, String key, String owner) {
            JSONArray value = o.optJSONArray(key);
            if (value == null) {
                at(owner);
                throw refuse("missing " + key);
            }
            return value;
        }

        private void at(String path) {
            this.path = path;
        }

        private IllegalArgumentException refuse(String problem) {
            return new IllegalArgumentException("roster.json: " + path + ": " + problem);
        }
    }
}
