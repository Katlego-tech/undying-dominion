package com.undyingdominion.game;

import java.util.List;

/**
 * One of a commander's three abilities, cast by the commander's unit. Times are in ticks, distances
 * in tiles. {@code affects} says who the effects land on, unless an effect names its own.
 */
public record Ability(String id, String label, Slot slot, Target target, int cost, int cooldownTicks,
                      int range, int radius, Affects affects, List<Effect> effects) {

    /** Which key it sits on: R, F and X. */
    public enum Slot { BASIC, UTILITY, SIGNATURE }

    /** What the player points it at. CORPSE and SELECTION are the necromancer's own commands. */
    public enum Target { SELF, AROUND_SELF, ALLY, ENEMY, ENEMY_COMMANDER, TILE, CORPSE, SELECTION }

    /** TARGET: the unit aimed at, or the caster for SELF. CASTER: the commander's unit alone. */
    public enum Affects { TARGET, ENEMIES, OWN, CASTER }

    public Ability {
        effects = List.copyOf(effects);
    }
}
