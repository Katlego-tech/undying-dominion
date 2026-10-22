package com.undyingdominion.game;

/**
 * A commander's always-on trait. Which fields matter depends on the kind: an AURA uses status,
 * magnitude, radius and affects; BONUS_VS uses magnitude, against ("RETINUE" or "COMMANDER") and
 * affects (OWN for every own unit, CASTER for the commander alone).
 */
public record Passive(String id, String label, Kind kind, Status status, int magnitude, int radius,
                      Ability.Affects affects, String against) {

    public enum Kind { AURA, SELF, SEES_STEALTH, ENERGY_REGEN, ENERGY_ON_KILL, BONUS_VS, IMMUNE, SOUL_HARVEST }
}
