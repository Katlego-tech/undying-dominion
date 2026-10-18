package com.undyingdominion.game;

import java.util.List;

/**
 * Everything a {@link GameEngine} needs to carry on after a restart: its clocks, id counters and
 * every body on the field. The map, rules and random generator come from elsewhere.
 *
 * @param startArea where necromancers rise, {@code [x1, y1, x2, y2]}, or null for anywhere
 * @param traps in the order they were laid, which is the order the engine finds them in
 */
public record EngineState(long tick, int nextPlayerId, int nextUnitId, int nextCorpseId, int nextTrapId,
                          List<Integer> startArea, List<PlayerState> players, List<UnitState> units,
                          List<Corpse> corpses, List<Trap> traps) {
}
