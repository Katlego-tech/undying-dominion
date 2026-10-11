package com.undyingdominion.world;

/** A tile coordinate. The origin is the top-left tile and y grows downwards. */
public record Position(int x, int y) {

    /** Tiles between two positions when diagonal steps are allowed (Chebyshev distance). */
    public int distanceTo(Position other) {
        return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
    }

    public Position offset(int dx, int dy) {
        return new Position(x + dx, y + dy);
    }
}
