package com.undyingdominion.game;

/** A status on one unit: how strong, until which tick, and which unit put it there. */
public record StatusEffect(Status status, int magnitude, long untilTick, int sourceUnitId) {
}
