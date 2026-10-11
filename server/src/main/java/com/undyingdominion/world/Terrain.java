package com.undyingdominion.world;

/**
 * What covers a tile. Descended from Robot Worlds' MOUNTAIN / LAKE / PIT obstacles.
 */
public enum Terrain {
    /** Open ground. */
    GROUND(true, false, 1),
    /** Crumbled walls. Block movement, sight and missiles (was MOUNTAIN). */
    RUINS(false, true, 1),
    /** Bog. Passable, but every step takes twice as long (was LAKE). */
    SWAMP(true, false, 2),
    /** A bottomless rift. Impassable, but you can see and shoot across it (was PIT). */
    ABYSS(false, false, 1),
    /** Dense trees. Passable, but nothing can be seen or shot through them. */
    FOREST(true, true, 1);

    public final boolean walkable;
    public final boolean blocksSight;
    /** Multiplier on a unit's move cooldown while stepping onto this tile. */
    public final int moveCostMultiplier;

    Terrain(boolean walkable, boolean blocksSight, int moveCostMultiplier) {
        this.walkable = walkable;
        this.blocksSight = blocksSight;
        this.moveCostMultiplier = moveCostMultiplier;
    }
}
