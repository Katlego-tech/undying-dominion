package com.undyingdominion.game;

import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Position;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What one player can see this tick: the fog of war.
 *
 * <p>Robot Worlds' rule is kept, that sight is limited by range and stopped by ruins (its mountains).
 * The server decides what each player sees and sends nothing else, so a modified client cannot see
 * through the fog.
 */
public final class Visibility {

    private final GameMap map;
    private final boolean[] visible;
    private final List<Unit> eyes = new ArrayList<>();

    private Visibility(GameMap map) {
        this.map = map;
        this.visible = new boolean[map.getWidth() * map.getHeight()];
    }

    /** Computes everything the units of {@code ownerId} can see. */
    public static Visibility of(GameMap map, Collection<Unit> units, int ownerId) {
        Visibility v = new Visibility(map);
        for (Unit unit : units) {
            if (unit.isAlive() && unit.getOwnerId() == ownerId) {
                v.eyes.add(unit);
                v.reveal(unit.getPosition(), unit.getType().visionRange);
            }
        }
        return v;
    }

    private void reveal(Position eye, int radius) {
        int limit = radius * radius + radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy > limit) continue;
                int x = eye.x() + dx, y = eye.y() + dy;
                if (!map.inBounds(x, y)) continue;
                int idx = y * map.getWidth() + x;
                if (visible[idx]) continue;
                if (map.hasLineOfSight(eye, new Position(x, y))) visible[idx] = true;
            }
        }
    }

    public boolean sees(Position p) {
        return map.inBounds(p) && visible[p.y() * map.getWidth() + p.x()];
    }

    public boolean sees(int x, int y) {
        return map.inBounds(x, y) && visible[y * map.getWidth() + x];
    }

    /**
     * Whether a trap is known to this player: their own always, an enemy's only when one of their
     * units stands within its trap detection range.
     */
    public boolean knowsTrap(Trap trap, int ownerId) {
        if (trap.ownerId() == ownerId) return true;
        for (Unit eye : eyes) {
            if (eye.getPosition().distanceTo(trap.position()) <= eye.getType().trapDetectionRange()) return true;
        }
        return false;
    }

    /** The visible tiles packed one bit per tile, row by row, lowest bit first. */
    public byte[] toBits() {
        byte[] bits = new byte[(visible.length + 7) / 8];
        for (int i = 0; i < visible.length; i++) {
            if (visible[i]) bits[i >> 3] |= (byte) (1 << (i & 7));
        }
        return bits;
    }
}
