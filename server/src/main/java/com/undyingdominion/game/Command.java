package com.undyingdominion.game;

import com.undyingdominion.world.Position;

import java.util.List;

/**
 * Something a player asked for. Commands are queued from network threads and applied by the
 * game loop at the start of the next tick, so the engine itself never needs locking.
 */
public sealed interface Command {

    int playerId();

    record Join(int playerId, String name) implements Command {}

    record Leave(int playerId) implements Command {}

    /**
     * Gives a player back what they carried out of the previous chapter: their progress, and their
     * legion, which rises again around their necromancer. Server-side only.
     */
    record Restore(int playerId, int level, int xp, int kills, int deaths, List<UnitType> legion) implements Command {}

    record Summon(int playerId, UnitType type) implements Command {}

    /** Walk to a tile. With {@code attackMove}, fight anything met on the way. */
    record Move(int playerId, List<Integer> unitIds, Position target, boolean attackMove) implements Command {}

    record Attack(int playerId, List<Integer> unitIds, int targetId) implements Command {}

    record Stop(int playerId, List<Integer> unitIds) implements Command {}

    /** Raise a corpse near the necromancer into the legion. */
    record Raise(int playerId, int corpseId) implements Command {}

    /** Detonate a corpse near the necromancer, hurting every enemy around it. */
    record Explode(int playerId, int corpseId) implements Command {}

    /** Fuse skeleton warriors standing together into a Skeleton King. */
    record Fuse(int playerId, List<Integer> unitIds) implements Command {}

    /** Root the units until they are whole again (Robot Worlds' repair). */
    record Mend(int playerId, List<Integer> unitIds) implements Command {}

    /** Grave Wardens lay a bone trap where they stand, then step forward (Robot Worlds' mine). */
    record SetTrap(int playerId, List<Integer> unitIds) implements Command {}

    /** Let units go from the legion: they crumble to nothing, freeing their slots. */
    record Dismiss(int playerId, List<Integer> unitIds) implements Command {}
}
