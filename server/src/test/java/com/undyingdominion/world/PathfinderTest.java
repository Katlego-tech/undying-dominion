package com.undyingdominion.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderTest {

    /** A 10 x 10 map split by a wall at x = 5, open only at the bottom row. */
    private final GameMap walled = new GameMap(10, 10, List.of(new Obstacle(5, 0, 5, 8, Terrain.RUINS)));

    @Test
    void routesAroundAWallThroughItsGap() {
        Position start = new Position(2, 2);
        Position goal = new Position(8, 2);

        List<Position> path = new Pathfinder(walled).findPath(start, goal, p -> false);

        assertEquals(goal, path.get(path.size() - 1));
        assertTrue(path.contains(new Position(5, 9)), "the only way through is the gap at [5,9]");
        assertContinuousAndWalkable(walled, start, path);
    }

    @Test
    void neverCutsTheCornerOfAnObstacle() {
        GameMap map = new GameMap(5, 5, List.of(new Obstacle(2, 1, 2, 1, Terrain.RUINS)));
        List<Position> path = new Pathfinder(map).findPath(new Position(1, 1), new Position(3, 0), p -> false);

        Position previous = new Position(1, 1);
        for (Position step : path) {
            int dx = step.x() - previous.x();
            int dy = step.y() - previous.y();
            if (dx != 0 && dy != 0) {
                assertTrue(map.isWalkable(previous.x() + dx, previous.y()) && map.isWalkable(previous.x(), previous.y() + dy),
                        "diagonal step " + previous + " -> " + step + " slips past a corner");
            }
            previous = step;
        }
    }

    @Test
    void walksAsCloseAsPossibleWhenTheGoalIsWalledIn() {
        GameMap map = new GameMap(10, 10, List.of(
                new Obstacle(6, 3, 8, 3, Terrain.RUINS),
                new Obstacle(6, 5, 8, 5, Terrain.RUINS),
                new Obstacle(6, 4, 6, 4, Terrain.RUINS),
                new Obstacle(8, 4, 8, 4, Terrain.RUINS)));
        Position start = new Position(1, 4);

        List<Position> path = new Pathfinder(map).findPath(start, new Position(7, 4), p -> false);

        assertFalse(path.isEmpty());
        Position end = path.get(path.size() - 1);
        assertEquals(2, end.distanceTo(new Position(7, 4)), "stops at a nearest reachable tile, outside the walls");
        assertContinuousAndWalkable(map, start, path);
    }

    @Test
    void goesAroundSwampWhenTheDetourIsCheaper() {
        GameMap map = new GameMap(9, 5, List.of(new Obstacle(4, 1, 4, 3, Terrain.SWAMP)));
        List<Position> path = new Pathfinder(map).findPath(new Position(2, 2), new Position(6, 2), p -> false);

        assertTrue(path.stream().noneMatch(p -> map.terrainAt(p) == Terrain.SWAMP));
    }

    @Test
    void treatsOccupiedTilesAsBlockedExceptTheGoal() {
        GameMap open = new GameMap(5, 3, List.of());
        Position blocker = new Position(2, 1);
        List<Position> path = new Pathfinder(open).findPath(new Position(0, 1), new Position(4, 1), blocker::equals);

        assertFalse(path.contains(blocker));
        assertEquals(new Position(4, 1), path.get(path.size() - 1));

        List<Position> toBlocker = new Pathfinder(open).findPath(new Position(0, 1), blocker, blocker::equals);
        assertEquals(blocker, toBlocker.get(toBlocker.size() - 1));
    }

    private static void assertContinuousAndWalkable(GameMap map, Position start, List<Position> path) {
        Position previous = start;
        for (Position step : path) {
            assertTrue(map.isWalkable(step), step + " is not walkable");
            assertEquals(1, previous.distanceTo(step), "jump from " + previous + " to " + step);
            previous = step;
        }
    }
}
