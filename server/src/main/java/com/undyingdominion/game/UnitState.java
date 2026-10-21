package com.undyingdominion.game;

import com.undyingdominion.world.Position;

import java.util.List;

/**
 * Every field of a {@link Unit}, paths and cooldowns included, so a rebuilt world plays on exactly as
 * the original would have. {@code type} is the unit type's name.
 */
public record UnitState(int id, String type, int ownerId, boolean raised, Position position,
                        int facingX, int facingY, int hp, Unit.Order order, Position destination,
                        Integer targetId, boolean autoTarget, long modeEndsTick, List<Position> path,
                        Position pathGoal, long pathComputedTick, int blockedTicks, long moveReadyTick,
                        long attackReadyTick, long nextWanderTick, int lastHitByOwner, boolean hasBeenHit,
                        long expiresAtTick) {
}
