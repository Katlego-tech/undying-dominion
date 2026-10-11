package com.undyingdominion.game;

/**
 * Every kind of unit on the battlefield and its stats. Times are in ticks (10 per second).
 *
 * <p>{@code summonCost} is the soul energy a necromancer pays to summon one from nothing, or -1 if it
 * cannot be summoned. {@code raiseCost} is the price of raising one from its corpse, or -1 if its
 * corpse cannot be raised.
 */
public enum UnitType {
    //               label               hp  dmg rng atkCd mvCd aggro vision summon raise soul  xp splash chase  neutral
    NECROMANCER     ("Necromancer",     220,  9,  3,  12,   4,   3,    8,    -1,   -1,  40, 100, 0, false, false),
    SKELETON_WARRIOR("Skeleton Warrior", 60, 10,  1,   8,   4,   5,    6,    20,   12,   6,   8, 0, true,  false),
    SKELETON_ARCHER ("Skeleton Archer",  35,  7,  5,  12,   4,   6,    7,    30,   18,   8,  10, 0, true,  false),
    LICH            ("Lich",             75, 16,  4,  20,   6,   5,    7,    70,   40,  20,  25, 1, true,  false),
    /** Lays bone traps and has no attack of its own, as a mine-laying robot could not carry a gun. */
    GRAVE_WARDEN    ("Grave Warden",     40,  0,  0,   0,   4,   0,    6,    25,   15,   6,   8, 0, false, false),
    SKELETON_KING   ("Skeleton King",   420, 28,  1,  10,   5,   5,    6,    -1,  150,  60,  80, 1, true,  false),
    WOLF            ("Dire Wolf",        45,  7,  1,   7,   3,   5,    6,    -1,   10,  10,  12, 0, true,  true),
    KNIGHT          ("Fallen Knight",    95, 12,  1,  10,   5,   4,    5,    -1,   25,  18,  20, 0, true,  true);

    public final String label;
    public final int maxHp;
    public final int damage;
    public final int range;
    public final int attackCooldown;
    public final int moveCooldown;
    public final int aggroRange;
    /** How far the unit sees, in tiles. Ruins block sight. */
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

    UnitType(String label, int maxHp, int damage, int range, int attackCooldown, int moveCooldown,
             int aggroRange, int visionRange, int summonCost, int raiseCost, int soulValue, int xpValue,
             int splashRadius, boolean chasesWhenIdle, boolean neutral) {
        this.label = label;
        this.maxHp = maxHp;
        this.damage = damage;
        this.range = range;
        this.attackCooldown = attackCooldown;
        this.moveCooldown = moveCooldown;
        this.aggroRange = aggroRange;
        this.visionRange = visionRange;
        this.summonCost = summonCost;
        this.raiseCost = raiseCost;
        this.soulValue = soulValue;
        this.xpValue = xpValue;
        this.splashRadius = splashRadius;
        this.chasesWhenIdle = chasesWhenIdle;
        this.neutral = neutral;
    }

    public boolean isSummonable() { return summonCost >= 0; }
    public boolean isRaisable()   { return raiseCost >= 0; }
    public boolean canAttack()    { return damage > 0; }
    public boolean laysTraps()    { return this == GRAVE_WARDEN; }

    /**
     * How close an enemy trap must be before this unit notices it: a quarter of its vision,
     * rounded down, as Robot Worlds robots detected mines.
     */
    public int trapDetectionRange() {
        return visionRange / 4;
    }
}
