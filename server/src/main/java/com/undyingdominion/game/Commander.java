package com.undyingdominion.game;

import java.util.List;

/**
 * A character a player fields: their own unit, an energy pool named for them, a retinue they summon
 * or call, three abilities and a passive. All of it is data from {@code commanders/roster.json}.
 *
 * <p>{@code units} are the types only an ability places, such as a decoy; they can't be summoned.
 */
public record Commander(String id, String name, String title, Role role, String blurb, UnitType unit,
                        String energy, String verb, List<UnitType> retinue, List<UnitType> units,
                        List<Ability> abilities, Passive passive, boolean campaignOnly) {

    public enum Role { SUMMONER, CASTER, FRONT_LINE, FIGHTER, ASSASSIN, SUPPORT, NECROMANCER }

    public Commander {
        retinue = List.copyOf(retinue);
        units = List.copyOf(units);
        abilities = List.copyOf(abilities);
    }

    /** The ability with this id, or null if the kit has none. */
    public Ability ability(String id) {
        for (Ability ability : abilities) {
            if (ability.id().equals(id)) return ability;
        }
        return null;
    }
}
