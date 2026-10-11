package com.undyingdominion.world;

/** A rectangle of non-ground terrain, inclusive on both corners. */
public record Obstacle(int x1, int y1, int x2, int y2, Terrain terrain) {

    public Obstacle {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        x1 = minX; x2 = maxX;
        y1 = minY; y2 = maxY;
    }

    public boolean covers(int x, int y) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2;
    }
}
