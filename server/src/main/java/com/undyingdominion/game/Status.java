package com.undyingdominion.game;

/**
 * Something an ability or passive leaves on a unit for a while. Magnitudes are percentages, except
 * REGEN and BURNING (hp per second). See docs/design/characters.md §3 for what each one does.
 */
public enum Status {
    MIGHT, WARD, HASTE, SLOW, WEAKENED, EXPOSED, ROOTED, SILENCED, SEALED, STEALTHED,
    TAUNTED, UNSTOPPABLE, EMPOWERED, REGEN, BURNING, CLEAVING, EVADING;

    /** Control is what a cleanse removes and what UNSTOPPABLE keeps off. */
    public boolean isControl() {
        return switch (this) {
            case SLOW, WEAKENED, EXPOSED, ROOTED, SILENCED, SEALED, TAUNTED -> true;
            default -> false;
        };
    }
}
