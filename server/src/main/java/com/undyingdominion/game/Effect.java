package com.undyingdominion.game;

/**
 * One thing an ability does, in the order the ability lists them. {@link #affects()} is null when the
 * effect lands on whoever the ability affects.
 */
public sealed interface Effect {

    Ability.Affects affects();

    /** {@code stealthBonus}: extra damage, in percent, when the caster was stealthed as it cast. */
    record Damage(int amount, int stealthBonus, Ability.Affects affects) implements Effect {}

    record Heal(int amount, Ability.Affects affects) implements Effect {}

    /** Absorbs the next {@code amount} damage for {@code ticks}. A new shield replaces a smaller one. */
    record Shield(int amount, int ticks, Ability.Affects affects) implements Effect {}

    /** {@code afterTicks}: applied that long after the cast, as Berserk's weakness is. */
    record Apply(Status status, int magnitude, int ticks, int afterTicks, Ability.Affects affects) implements Effect {}

    /** Points the taunted at the caster, or at the unit the same ability just summoned. */
    record Taunt(int ticks, boolean towardSummoned, Ability.Affects affects) implements Effect {}

    /** Moves the caster; resolves first, so later effects centre on the landing. */
    record Dash(int tiles, int damage, boolean overTerrain) implements Effect {
        @Override
        public Ability.Affects affects() {
            return null;
        }
    }

    record Push(int tiles, Ability.Affects affects) implements Effect {}

    /** {@code ticks} 0: the units stay; otherwise they crumble after that long. */
    record Summon(UnitType unit, int count, int ticks) implements Effect {
        @Override
        public Ability.Affects affects() {
            return null;
        }
    }

    record Cleanse(Ability.Affects affects) implements Effect {}

    record ResetCooldowns() implements Effect {
        @Override
        public Ability.Affects affects() {
            return null;
        }
    }

    record Reveal(int ticks) implements Effect {
        @Override
        public Ability.Affects affects() {
            return null;
        }
    }

    /** The necromancer's raise, Corpse Explosion and fuse, which keep their own commands and prices. */
    record Native(Kind kind) implements Effect {
        public enum Kind { RAISE, DETONATE, FUSE }

        @Override
        public Ability.Affects affects() {
            return null;
        }
    }
}
