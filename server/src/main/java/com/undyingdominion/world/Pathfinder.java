package com.undyingdominion.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* over the tile grid with eight-way movement. Diagonal steps may not cut the corner of
 * an unwalkable tile. Swamp tiles cost more, so paths skirt bogs when there is a way round.
 */
public class Pathfinder {

    private static final int[][] STEPS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final GameMap map;
    private final int maxExpanded;

    public Pathfinder(GameMap map) {
        this(map, 4000);
    }

    public Pathfinder(GameMap map, int maxExpanded) {
        this.map = map;
        this.maxExpanded = maxExpanded;
    }

    /**
     * Finds a route from {@code start} to {@code goal}, excluding {@code start} itself.
     *
     * @param occupied extra tiles to avoid (other units). The goal is never treated as occupied,
     *                 so a unit can path towards an enemy standing on it.
     * @return the steps to take. If the goal cannot be reached, the route to the closest tile
     *         that can. Empty if no progress is possible.
     */
    public List<Position> findPath(Position start, Position goal, Predicate<Position> occupied) {
        if (start.equals(goal)) return List.of();

        int w = map.getWidth(), h = map.getHeight();
        int[] cost = new int[w * h];
        int[] parent = new int[w * h];
        boolean[] closed = new boolean[w * h];
        Arrays.fill(cost, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);

        int startIdx = index(start);
        cost[startIdx] = 0;
        PriorityQueue<int[]> open = new PriorityQueue<>((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
        open.add(new int[]{startIdx, heuristic(start, goal), heuristic(start, goal)});

        int best = startIdx;
        int bestH = heuristic(start, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxExpanded) {
            int[] node = open.poll();
            int idx = node[0];
            if (closed[idx]) continue;
            closed[idx] = true;
            expanded++;

            Position here = position(idx);
            if (here.equals(goal)) {
                best = idx;
                break;
            }
            int hHere = heuristic(here, goal);
            if (hHere < bestH) {
                bestH = hHere;
                best = idx;
            }

            for (int[] step : STEPS) {
                Position next = here.offset(step[0], step[1]);
                if (!passable(next, goal, occupied)) continue;
                if (step[0] != 0 && step[1] != 0
                        && (!map.isWalkable(here.x() + step[0], here.y()) || !map.isWalkable(here.x(), here.y() + step[1]))) {
                    continue;
                }
                int nextIdx = index(next);
                if (closed[nextIdx]) continue;
                int nextCost = cost[idx] + map.terrainAt(next).moveCostMultiplier;
                if (nextCost < cost[nextIdx]) {
                    cost[nextIdx] = nextCost;
                    parent[nextIdx] = idx;
                    int hNext = heuristic(next, goal);
                    open.add(new int[]{nextIdx, nextCost + hNext, hNext});
                }
            }
        }

        if (best == startIdx) return List.of();
        Deque<Position> path = new ArrayDeque<>();
        for (int idx = best; idx != startIdx; idx = parent[idx]) {
            path.addFirst(position(idx));
        }
        return new ArrayList<>(path);
    }

    private boolean passable(Position p, Position goal, Predicate<Position> occupied) {
        if (!map.inBounds(p) || !map.isWalkable(p)) return false;
        return p.equals(goal) || !occupied.test(p);
    }

    private static int heuristic(Position a, Position b) {
        return a.distanceTo(b);
    }

    private int index(Position p) {
        return p.y() * map.getWidth() + p.x();
    }

    private Position position(int idx) {
        return new Position(idx % map.getWidth(), idx / map.getWidth());
    }
}
