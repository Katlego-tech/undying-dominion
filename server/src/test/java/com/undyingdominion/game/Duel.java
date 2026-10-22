package com.undyingdominion.game;

import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Position;

import java.util.List;
import java.util.Random;

/**
 * One balance duel (docs/design/characters.md §10): two commanders under {@link DuelBrain} on an open
 * 30×30 map with no wild creatures, at engine speed, scored as a two-player match (matches.md). Each
 * commander rises once, and a second fall puts it out and ends the duel. Otherwise, after five
 * game-minutes, the higher score wins: 5 for each enemy commander killed, 1 for any other enemy unit.
 * Equal scores go to what each side has left.
 */
public final class Duel {

    public static final int SIZE = 30;
    /** Five game-minutes at 10 ticks a second. */
    public static final int LIMIT_TICKS = 3000;
    public static final int RISES = 1;
    public static final int COMMANDER_POINTS = 5;
    public static final int UNIT_POINTS = 1;

    /**
     * {@code winner} is 0 for the first commander, 1 for the second, -1 for a dead even finish.
     * {@code ended} is true when a commander was put out before the clock ran down.
     */
    public record Result(int winner, boolean ended, long ticks, int pointsA, int pointsB) {}

    private Duel() {
    }

    /**
     * Fights {@code a} against {@code b}. The seed picks where each starts, a few tiles in from opposite
     * edges, and odd seeds swap the edges so neither always starts west.
     */
    public static Result fight(Commander a, Commander b, long seed) {
        GameRules rules = GameRules.defaults().maxNeutrals(0).corpses(false);
        GameEngine engine = new GameEngine(new GameMap(SIZE, SIZE, List.of()), rules, new Random(seed));
        Random starts = new Random(seed * 31 + 7);
        Player first = join(engine, "A", a);
        Player second = join(engine, "B", b);
        boolean swap = seed % 2 != 0;
        place(engine, first, start(starts, swap));
        place(engine, second, start(starts, !swap));
        DuelBrain brainA = new DuelBrain(engine, first, second);
        DuelBrain brainB = new DuelBrain(engine, second, first);

        while (engine.getTick() < LIMIT_TICKS) {
            brainA.act();
            brainB.act();
            engine.tick();
            boolean aOut = first.getDeaths() > RISES, bOut = second.getDeaths() > RISES;
            if (aOut || bOut) {
                int winner = aOut && bOut ? -1 : aOut ? 1 : 0;
                return new Result(winner, true, engine.getTick(), points(first, second), points(second, first));
            }
        }
        int pointsA = points(first, second), pointsB = points(second, first);
        int winner = pointsA != pointsB ? (pointsA > pointsB ? 0 : 1) : tiebreak(engine, first, second);
        return new Result(winner, false, engine.getTick(), pointsA, pointsB);
    }

    /** 5 for each time the rival's commander fell, 1 for every other kill. */
    static int points(Player player, Player rival) {
        int commanderKills = rival.getDeaths();
        return COMMANDER_POINTS * commanderKills + UNIT_POINTS * (player.getKills() - commanderKills);
    }

    private static int tiebreak(GameEngine engine, Player first, Player second) {
        double a = value(engine, first), b = value(engine, second);
        return a > b ? 0 : b > a ? 1 : -1;
    }

    /** A tile 3 to 7 in from the west or east edge, anywhere down the map but its last 3 rows. */
    private static Position start(Random random, boolean east) {
        int in = 3 + random.nextInt(5);
        return new Position(east ? SIZE - 1 - in : in, 3 + random.nextInt(SIZE - 6));
    }

    /**
     * What a side has left: its commander's health share counts as 200, each retinue unit its
     * summon cost times its health share, plus unspent energy.
     */
    static double value(GameEngine engine, Player player) {
        double value = player.getSoul();
        for (Unit unit : engine.getUnits()) {
            if (!unit.isAlive() || unit.getOwnerId() != player.getId()) continue;
            double share = (double) unit.getHp() / unit.getType().maxHp;
            value += unit.getType().isCommander() ? 200 * share : Math.max(0, unit.getType().summonCost) * share;
        }
        return value;
    }

    private static Player join(GameEngine engine, String name, Commander commander) {
        int id = engine.allocatePlayerId();
        engine.submit(new Command.Join(id, name, commander));
        engine.tick();
        return engine.getPlayer(id);
    }

    private static void place(GameEngine engine, Player player, Position at) {
        Unit unit = engine.commanderOf(player);
        Position spot = engine.freeTileNear(at, 3);
        engine.place(unit, spot);
        unit.orderIdle();
    }
}
