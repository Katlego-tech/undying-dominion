package com.undyingdominion.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which world the dashboard draws on a hub, as campaign worlds open and close (docs/design/modes.md §7). */
class WorldWatchTest {

    private final WorldWatch<String> watch = new WorldWatch<>();

    @Test
    void itStartsOnTheFirstWorldTheFreeForAll() {
        assertEquals("ffa", watch.current(List.of("ffa", "lin")));
    }

    @Test
    void theBracketsStepThroughTheWorldsAndWrapAround() {
        List<String> worlds = List.of("ffa", "lin", "mara");

        assertEquals("lin", watch.cycle(worlds, 1));
        assertEquals("lin", watch.current(worlds), "it stays on the world chosen");
        assertEquals("mara", watch.cycle(worlds, 1));
        assertEquals("ffa", watch.cycle(worlds, 1));
        assertEquals("mara", watch.cycle(worlds, -1));
    }

    @Test
    void itKeepsItsWorldWhenOthersOpenOrClose() {
        watch.cycle(List.of("ffa", "lin", "mara"), 2);

        assertEquals("mara", watch.current(List.of("ffa", "mara")), "lin closing doesn't move the watch");
        assertEquals("mara", watch.current(List.of("ffa", "mara", "corvin")));
    }

    @Test
    void whenItsWorldClosesItGoesBackToTheFreeForAll() {
        watch.cycle(List.of("ffa", "lin"), 1);

        assertEquals("ffa", watch.current(List.of("ffa")));
        assertEquals("ffa", watch.current(List.of("ffa", "lin")), "and stays there if a new world opens");
    }
}
