package com.undyingdominion.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameMapTest {

    @Test
    void ruinsBlockSightButAbyssAndSwampDoNot() {
        GameMap map = new GameMap(9, 3, List.of(
                new Obstacle(2, 0, 2, 0, Terrain.RUINS),
                new Obstacle(2, 1, 2, 1, Terrain.ABYSS),
                new Obstacle(2, 2, 2, 2, Terrain.SWAMP)));

        assertFalse(map.hasLineOfSight(new Position(0, 0), new Position(5, 0)));
        assertTrue(map.hasLineOfSight(new Position(0, 1), new Position(5, 1)));
        assertTrue(map.hasLineOfSight(new Position(0, 2), new Position(5, 2)));
    }

    @Test
    void theBlockingTileItselfCanBeSeen() {
        GameMap map = new GameMap(5, 1, List.of(new Obstacle(3, 0, 3, 0, Terrain.RUINS)));
        assertTrue(map.hasLineOfSight(new Position(0, 0), new Position(3, 0)));
    }

    @Test
    void outsideTheMapIsNeverWalkable() {
        GameMap map = new GameMap(3, 3, List.of());
        assertFalse(map.isWalkable(-1, 0));
        assertFalse(map.isWalkable(3, 3));
        assertTrue(map.isWalkable(2, 2));
    }

    @Test
    void readsTheRobotWorldsObstacleFormat() {
        Properties props = new Properties();
        props.setProperty("map.width", "20");
        props.setProperty("map.height", "10");
        props.setProperty("obstacle.ruins.1", "2, 3, 4, 5");
        props.setProperty("obstacle.abyss.1", "10,1,10,1");

        GameMap map = MapConfig.fromProperties(props);

        assertEquals(20, map.getWidth());
        assertEquals(Terrain.RUINS, map.terrainAt(3, 4));
        assertEquals(Terrain.ABYSS, map.terrainAt(10, 1));
        assertEquals(Terrain.GROUND, map.terrainAt(0, 0));
    }

    @Test
    void rejectsAnObstacleWithoutFourCoordinates() {
        Properties props = new Properties();
        props.setProperty("map.width", "5");
        props.setProperty("map.height", "5");
        props.setProperty("obstacle.ruins.1", "1,1");

        assertThrows(IllegalArgumentException.class, () -> MapConfig.fromProperties(props));
    }

    @Test
    void theDefaultMapHasAtLeastOneObstacleAndOnePit() throws IOException {
        GameMap map = MapConfig.load("classpath:/maps/default.properties");

        assertTrue(map.getObstacles().stream().anyMatch(o -> o.terrain() == Terrain.RUINS));
        assertTrue(map.getObstacles().stream().anyMatch(o -> o.terrain() == Terrain.ABYSS));
    }
}
