package com.undyingdominion.game;

/** A necromancer's resources, progression and score, as saved. Undelivered notices are not kept. */
public record PlayerState(int id, String name, String color, Integer necromancerId, long respawnAtTick,
                          int soul, int xp, int level, int kills, int deaths) {
}
