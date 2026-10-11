package com.undyingdominion.game;

import com.undyingdominion.world.Position;

/**
 * A bone trap, descended from the Robot Worlds mine. It does not block movement; whoever steps on
 * it takes the damage, including units of the player who laid it.
 */
public record Trap(int id, int ownerId, Position position) {
}
