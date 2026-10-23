package com.undyingdominion.game;

import java.util.List;

/** A necromancer's resources, progression and score, as saved. Undelivered notices are not kept. */
public record PlayerState(int id, String name, String color, Integer necromancerId, long respawnAtTick,
                          int soul, int xp, int level, int kills, int deaths, List<UnitType> ossuary) {

    /** A player from before the ossuary: they carried no corpses. */
    public PlayerState(int id, String name, String color, Integer necromancerId, long respawnAtTick,
                       int soul, int xp, int level, int kills, int deaths) {
        this(id, name, color, necromancerId, respawnAtTick, soul, xp, level, kills, deaths, List.of());
    }
}
