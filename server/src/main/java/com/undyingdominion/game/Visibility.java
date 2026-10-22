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
 *
 * <p>A STEALTHED unit is left out even on a visible tile, unless one of the player's units stands
 * next to it, their commander sees through stealth and it's close enough, or a reveal covers it.
 */
public final class Visibility {

    /** Sight a player's ability grants over an area for a while, stealthed units included. */
    public record Reveal(int ownerId, Position centre, int radius, long untilTick) {}

    private final GameMap map;
    private final int ownerId;
    private final boolean[] visible;
    private final List<Unit> eyes = new ArrayList<>();
    private final List<Reveal> reveals = new ArrayList<>();
    private int seesStealth;

    private Visibility(GameMap map, int ownerId) {
        this.map = map;
        this.ownerId = ownerId;
        this.visible = new boolean[map.getWidth() * map.getHeight()];
    }

    /** Computes everything the units of {@code ownerId} can see. */
    public static Visibility of(GameMap map, Collection<Unit> units, int ownerId) {
        return of(map, units, ownerId, 0, List.of());
    }

    /**
     * As above, with the player's reveals, and how far their commander sees through stealth (0 if it
     * doesn't).
     */
    public static Visibility of(GameMap map, Collection<Unit> units, int ownerId, int seesStealth,
                                Collection<Reveal> reveals) {
        Visibility v = new Visibility(map, ownerId);
        v.seesStealth = seesStealth;
        for (Unit unit : units) {
            if (unit.isAlive() && unit.getOwnerId() == ownerId) {
                v.eyes.add(unit);
                v.reveal(unit.getPosition(), unit.getType().visionRange);
            }
        }
        for (Reveal reveal : reveals) {
            if (reveal.ownerId() != ownerId) continue;
            v.reveals.add(reveal);
            v.uncover(reveal.centre(), reveal.radius());
        }
        return v;
    }

    /** Whether this unit appears in the player's view: on a visible tile, and not hidden by stealth. */
    public boolean shows(Unit unit) {
        if (unit.getOwnerId() == ownerId) return true;
        if (!sees(unit.getPosition())) return false;
        return !unit.has(Status.STEALTHED) || detects(unit);
    }

    private boolean detects(Unit hidden) {
        Position at = hidden.getPosition();
        for (Unit eye : eyes) {
            int distance = eye.getPosition().distanceTo(at);
            if (distance <= 1) return true;
            if (seesStealth > 0 && eye.getType().isCommander() && distance <= seesStealth) return true;
        }
        for (Reveal reveal : reveals) {
            if (reveal.centre().distanceTo(at) <= reveal.radius()) return true;
        }
        return false;
    }

    /** Every tile within {@code radius}, walls or not. */
    private void uncover(Position centre, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (map.inBounds(centre.x() + dx, centre.y() + dy)) {
                    visible[(centre.y() + dy) * map.getWidth() + centre.x() + dx] = true;
                }
            }
        }
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
