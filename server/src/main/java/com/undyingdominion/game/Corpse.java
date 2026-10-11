package com.undyingdominion.game;

import com.undyingdominion.world.Position;

/** What a fallen unit leaves behind until it rots or a necromancer raises it. */
public record Corpse(int id, UnitType type, Position position, long decaysAtTick) {
}
