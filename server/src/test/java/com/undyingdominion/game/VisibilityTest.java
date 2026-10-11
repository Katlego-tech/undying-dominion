package com.undyingdominion.game;

import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Obstacle;
import com.undyingdominion.world.Position;
import com.undyingdominion.world.Terrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityTest {

    /** A wall of ruins at x = 10 across the whole height. */
    private final GameMap map = new GameMap(20, 10, List.of(new Obstacle(10, 0, 10, 9, Terrain.RUINS)));

    private Unit unit(int id, UnitType type, int owner, int x, int y) {
        return new Unit(id, type, owner, new Position(x, y), false);
    }

    @Test
    void seesAsFarAsItsVisionRangeAndNoFurther() {
        Unit warrior = unit(1, UnitType.SKELETON_WARRIOR, 1, 2, 5);
        Visibility sight = Visibility.of(map, List.of(warrior), 1);

        int range = UnitType.SKELETON_WARRIOR.visionRange;
        assertTrue(sight.sees(2 + range, 5));
        assertFalse(sight.sees(2 + range + 1, 5));
    }

    @Test
    void ruinsBlockSightButCanThemselvesBeSeen() {
        Unit warrior = unit(1, UnitType.SKELETON_WARRIOR, 1, 8, 5);
        Visibility sight = Visibility.of(map, List.of(warrior), 1);

        assertTrue(sight.sees(10, 5), "the wall itself is visible");
        assertFalse(sight.sees(11, 5), "nothing behind it is");
    }

    @Test
    void onlyThePlayersOwnUnitsGiveSight() {
        Unit enemy = unit(1, UnitType.SKELETON_WARRIOR, 2, 2, 5);
        Visibility sight = Visibility.of(map, List.of(enemy), 1);

        assertFalse(sight.sees(2, 5));
    }

    @Test
    void enemyTrapsAreOnlyNoticedWithinAQuarterOfVision() {
        Unit warrior = unit(1, UnitType.SKELETON_WARRIOR, 1, 2, 5);
        Visibility sight = Visibility.of(map, List.of(warrior), 1);
        int detection = UnitType.SKELETON_WARRIOR.visionRange / 4;

        assertTrue(sight.knowsTrap(new Trap(1, 2, new Position(2 + detection, 5)), 1));
        assertFalse(sight.knowsTrap(new Trap(2, 2, new Position(2 + detection + 1, 5)), 1));
        assertTrue(sight.knowsTrap(new Trap(3, 1, new Position(18, 9)), 1), "your own traps are always known");
    }

    @Test
    void packsOneBitPerTile() {
        Unit warrior = unit(1, UnitType.SKELETON_WARRIOR, 1, 0, 0);
        byte[] bits = Visibility.of(map, List.of(warrior), 1).toBits();

        assertEquals((20 * 10 + 7) / 8, bits.length);
        assertEquals(1, bits[0] & 1, "the unit's own tile is visible");
    }
}
