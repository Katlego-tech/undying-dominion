package com.undyingdominion.tui;

import java.util.List;

/**
 * Which world the dashboard draws on a hub (docs/design/modes.md §7). It holds on to the world chosen
 * while others open and close, and goes back to the first, the free-for-all, once its own closes.
 * Used from the terminal thread only.
 */
final class WorldWatch<W> {

    private W watched;

    /** The world to draw among {@code worlds}, which start with the free-for-all. */
    W current(List<W> worlds) {
        if (watched == null || !worlds.contains(watched)) watched = worlds.getFirst();
        return watched;
    }

    /** Steps {@code delta} worlds on from the one drawn, wrapping round, and returns the new one. */
    W cycle(List<W> worlds, int delta) {
        int index = worlds.indexOf(current(worlds));
        watched = worlds.get(Math.floorMod(index + delta, worlds.size()));
        return watched;
    }
}
