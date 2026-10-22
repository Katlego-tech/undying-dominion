package com.undyingdominion.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every kind of unit on the battlefield and its stats. Times are in ticks (10 per second).
 *
 * <p>The built-in types are constants. A campaign can {@link #register} more, such as the monsters and
 * bosses of a particular dungeon, before its world starts.
 *
 * <p>{@code summonCost} is the soul energy a necromancer pays to summon one from nothing, or -1 if it
 * cannot be summoned. {@code raiseCost} is the price of raising one from its corpse, or -1 if its
 * corpse cannot be raised.
 */
public final class UnitType {

    /** How the client should draw a unit it has no dedicated picture for. */
    public enum Look { UNDEAD, BEAST, GOBLIN, PLANT, HUMANOID, BEASTMAN, DEMON }

    private static final Map<String, UnitType> REGISTRY = new LinkedHashMap<>();

    //                                                   label               hp  dmg rng atkCd mvCd aggro vision summon raise soul  xp splash chase  neutral
    public static final UnitType NECROMANCER      = register(row("NECROMANCER", "Necromancer",  220,  9,  3,  12,   4,   3,    8,    -1,   -1,  40, 100, 0, false, false, Look.UNDEAD).commander(true).build());
    public static final UnitType SKELETON_WARRIOR = builtIn("SKELETON_WARRIOR", "Skeleton Warrior", 60, 10,  1,   8,   4,   5,    6,    20,   12,   6,   8, 0, true,  false, Look.UNDEAD);
    public static final UnitType SKELETON_ARCHER  = builtIn("SKELETON_ARCHER",  "Skeleton Archer",  35,  7,  5,  12,   4,   6,    7,    30,   18,   8,  10, 0, true,  false, Look.UNDEAD);
    public static final UnitType LICH             = builtIn("LICH",             "Lich",             75, 16,  4,  20,   6,   5,    7,    70,   40,  20,  25, 1, true,  false, Look.UNDEAD);
    /** Lays bone traps and has no attack of its own, as a mine-laying robot could not carry a gun. */
    public static final UnitType GRAVE_WARDEN     = builtIn("GRAVE_WARDEN",     "Grave Warden",     40,  0,  0,   0,   4,   0,    6,    25,   15,   6,   8, 0, false, false, Look.UNDEAD);
    public static final UnitType SKELETON_KING    = builtIn("SKELETON_KING",    "Skeleton King",   420, 28,  1,  10,   5,   5,    6,    -1,  150,  60,  80, 1, true,  false, Look.UNDEAD);
    public static final UnitType WOLF             = builtIn("WOLF",             "Dire Wolf",        45,  7,  1,   7,   3,   5,    6,    -1,   10,  10,  12, 0, true,  true,  Look.BEAST);
    public static final UnitType KNIGHT           = builtIn("KNIGHT",           "Fallen Knight",    95, 12,  1,  10,   5,   4,    5,    -1,   25,  18,  20, 0, true,  true,  Look.HUMANOID);

    private final String name;
    public final String label;
    public final int maxHp;
    public final int damage;
    public final int range;
    public final int attackCooldown;
    public final int moveCooldown;
    public final int aggroRange;
    /** How far the unit sees, in tiles. Ruins and forest block sight. */
    public final int visionRange;
    public final int summonCost;
    public final int raiseCost;
    public final int soulValue;
    public final int xpValue;
    public final int splashRadius;
    /** Whether an idle unit walks after an enemy it notices, or only fires at what is already in range. */
    public final boolean chasesWhenIdle;
    /** Wild creatures spawned by the world rather than by a player. */
    public final boolean neutral;
    public final Look look;
    /** The level shown beside the name, as monsters in the story carry one. Zero for none. */
    public final int level;
    public final boolean boss;
    /** Rooted in place, like a tree that lashes out at whatever comes near. */
    public final boolean stationary;
    /** A player's own body on the field: the necromancer, or a PvP commander's unit. */
    public final boolean commander;

    private UnitType(Builder b) {
        this.name = b.name;
        this.label = b.label;
        this.maxHp = b.maxHp;
        this.damage = b.damage;
        this.range = b.range;
        this.attackCooldown = b.attackCooldown;
        this.moveCooldown = b.moveCooldown;
        this.aggroRange = b.aggroRange;
        this.visionRange = b.visionRange;
        this.summonCost = b.summonCost;
        this.raiseCost = b.raiseCost;
        this.soulValue = b.soulValue;
        this.xpValue = b.xpValue;
        this.splashRadius = b.splashRadius;
        this.chasesWhenIdle = b.chasesWhenIdle;
        this.neutral = b.neutral;
        this.look = b.look;
        this.level = b.level;
        this.boss = b.boss;
        this.stationary = b.stationary;
        this.commander = b.commander;
    }

    private static UnitType builtIn(String name, String label, int maxHp, int damage, int range, int attackCooldown,
                                    int moveCooldown, int aggroRange, int visionRange, int summonCost, int raiseCost,
                                    int soulValue, int xpValue, int splashRadius, boolean chasesWhenIdle,
                                    boolean neutral, Look look) {
        return register(row(name, label, maxHp, damage, range, attackCooldown, moveCooldown, aggroRange, visionRange,
                summonCost, raiseCost, soulValue, xpValue, splashRadius, chasesWhenIdle, neutral, look).build());
    }

    private static Builder row(String name, String label, int maxHp, int damage, int range, int attackCooldown,
                               int moveCooldown, int aggroRange, int visionRange, int summonCost, int raiseCost,
                               int soulValue, int xpValue, int splashRadius, boolean chasesWhenIdle,
                               boolean neutral, Look look) {
        Builder b = builder(name, label).stats(maxHp, damage, range, attackCooldown, moveCooldown)
                .senses(aggroRange, visionRange).costs(summonCost, raiseCost).rewards(soulValue, xpValue)
                .splash(splashRadius).look(look);
        b.chasesWhenIdle = chasesWhenIdle;
        b.neutral = neutral;
        return b;
    }

    /** Adds a type so it can be looked up by name. A type with the same name replaces the old one. */
    public static synchronized UnitType register(UnitType type) {
        REGISTRY.put(type.name, type);
        return type;
    }

    /** Every known type, built-in first, in the order they were registered. */
    public static synchronized Collection<UnitType> values() {
        return List.copyOf(REGISTRY.values());
    }

    public static synchronized UnitType valueOf(String name) {
        UnitType type = REGISTRY.get(name);
        if (type == null) throw new IllegalArgumentException("No unit type " + name);
        return type;
    }

    public static Builder builder(String name, String label) {
        return new Builder(name, label);
    }

    public String name() { return name; }

    public boolean isSummonable() { return summonCost >= 0; }
    public boolean isRaisable()   { return raiseCost >= 0; }
    public boolean canAttack()    { return damage > 0; }
    public boolean laysTraps()    { return this == GRAVE_WARDEN; }
    public boolean isCommander()  { return commander; }

    /**
     * How close an enemy trap must be before this unit notices it: a quarter of its vision,
     * rounded down, as Robot Worlds robots detected mines.
     */
    public int trapDetectionRange() {
        return visionRange / 4;
    }

    @Override
    public String toString() {
        return name;
    }

    /** Builds a new kind of creature. Defaults make a wild melee monster that cannot be summoned. */
    public static final class Builder {
        private final String name;
        private final String label;
        private int maxHp = 50, damage = 8, range = 1, attackCooldown = 10, moveCooldown = 4;
        private int aggroRange = 5, visionRange = 6;
        private int summonCost = -1, raiseCost = 15, soulValue = 8, xpValue = 10, splashRadius;
        private boolean chasesWhenIdle = true, neutral = true, boss, stationary, commander;
        private Look look = Look.BEAST;
        private int level;

        private Builder(String name, String label) {
            if (!name.matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("Unit type names are UPPER_SNAKE_CASE, got " + name);
            }
            this.name = name;
            this.label = label;
        }

        public Builder stats(int maxHp, int damage, int range, int attackCooldown, int moveCooldown) {
            this.maxHp = maxHp;
            this.damage = damage;
            this.range = range;
            this.attackCooldown = attackCooldown;
            this.moveCooldown = moveCooldown;
            return this;
        }

        public Builder senses(int aggroRange, int visionRange) {
            this.aggroRange = aggroRange;
            this.visionRange = visionRange;
            return this;
        }

        public Builder costs(int summonCost, int raiseCost) {
            this.summonCost = summonCost;
            this.raiseCost = raiseCost;
            return this;
        }

        public Builder rewards(int soulValue, int xpValue) {
            this.soulValue = soulValue;
            this.xpValue = xpValue;
            return this;
        }

        public Builder splash(int radius) { this.splashRadius = radius; return this; }
        public Builder look(Look look) { this.look = look; return this; }
        public Builder level(int level) { this.level = level; return this; }
        public Builder boss(boolean boss) { this.boss = boss; return this; }
        public Builder stationary(boolean stationary) { this.stationary = stationary; return this; }
        public Builder commander(boolean commander) { this.commander = commander; return this; }
        public Builder chasesWhenIdle(boolean chases) { this.chasesWhenIdle = chases; return this; }
        public Builder neutral(boolean neutral) { this.neutral = neutral; return this; }

        public UnitType build() {
            if (stationary) chasesWhenIdle = false;
            return new UnitType(this);
        }
    }
}
