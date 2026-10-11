package com.undyingdominion.world;

import java.util.List;

/** The static battlefield: its size and terrain. Units live in the game layer, not here. */
public class GameMap {
    private final int width;
    private final int height;
    private final List<Obstacle> obstacles;
    private final Terrain[][] tiles;

    public GameMap(int width, int height, List<Obstacle> obstacles) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Map must be at least 1 x 1, got " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
        this.obstacles = List.copyOf(obstacles);
        this.tiles = new Terrain[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles[x][y] = Terrain.GROUND;
            }
        }
        // Later obstacles paint over earlier ones.
        for (Obstacle o : this.obstacles) {
            for (int x = Math.max(0, o.x1()); x <= Math.min(width - 1, o.x2()); x++) {
                for (int y = Math.max(0, o.y1()); y <= Math.min(height - 1, o.y2()); y++) {
                    tiles[x][y] = o.terrain();
                }
            }
        }
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    public boolean inBounds(Position p) {
        return inBounds(p.x(), p.y());
    }

    public Terrain terrainAt(int x, int y) {
        return inBounds(x, y) ? tiles[x][y] : Terrain.RUINS;
    }

    public Terrain terrainAt(Position p) {
        return terrainAt(p.x(), p.y());
    }

    public boolean isWalkable(int x, int y) {
        return terrainAt(x, y).walkable;
    }

    public boolean isWalkable(Position p) {
        return isWalkable(p.x(), p.y());
    }

    /**
     * Whether a missile or a glance can travel from {@code a} to {@code b} without crossing ruins.
     * Walks a Bresenham line; the end points themselves never block.
     */
    public boolean hasLineOfSight(Position a, Position b) {
        int x = a.x(), y = a.y();
        int dx = Math.abs(b.x() - x), dy = -Math.abs(b.y() - y);
        int sx = x < b.x() ? 1 : -1, sy = y < b.y() ? 1 : -1;
        int err = dx + dy;
        while (x != b.x() || y != b.y()) {
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
            if ((x != b.x() || y != b.y()) && terrainAt(x, y).blocksSight) {
                return false;
            }
        }
        return true;
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }
    public List<Obstacle> getObstacles() { return obstacles; }
}
