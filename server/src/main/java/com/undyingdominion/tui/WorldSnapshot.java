package com.undyingdominion.tui;

import com.undyingdominion.game.Corpse;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Trap;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.world.GameMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * An immutable copy of the world for the dashboard. Built on the game loop thread, read on the
 * terminal thread, so the dashboard never touches the live engine.
 */
public record WorldSnapshot(long tick, double tickMillis, GameMap map, String story, List<PlayerRow> players,
                            List<UnitDot> units, List<CorpseDot> corpses, List<TrapDot> traps) {

    public record PlayerRow(int id, String name, String color, int level, int xp, int soul, int soulMax,
                            int legion, int legionCap, int raised, int kills, int deaths, boolean alive,
                            boolean away) {}

    public record UnitDot(int id, UnitType type, int owner, int x, int y, int hp) {}

    public record CorpseDot(int x, int y, UnitType type) {}

    public record TrapDot(int x, int y, int owner) {}

    public static final WorldSnapshot EMPTY = new WorldSnapshot(0, 0, null, null, List.of(), List.of(), List.of(), List.of());

    public static WorldSnapshot of(GameEngine engine, double tickMillis) {
        return of(engine, tickMillis, null);
    }

    /** {@code story} is a one-line campaign summary for the header, or null. */
    public static WorldSnapshot of(GameEngine engine, double tickMillis, String story) {
        return of(engine, tickMillis, story, playerId -> false);
    }

    /** As above, marking the players {@code away} says have lost their connection. */
    public static WorldSnapshot of(GameEngine engine, double tickMillis, String story, IntPredicate away) {
        List<PlayerRow> players = new ArrayList<>();
        for (Player p : engine.getPlayers()) {
            players.add(new PlayerRow(p.getId(), p.getName(), p.getColor(), p.getLevel(), p.getXp(), p.getSoul(),
                    p.soulMax(), engine.legionSizeOf(p), p.legionCap(), engine.raisedSizeOf(p), p.getKills(), p.getDeaths(),
                    p.getNecromancerId() != null, away.test(p.getId())));
        }
        List<UnitDot> units = new ArrayList<>();
        for (Unit u : engine.getUnits()) {
            units.add(new UnitDot(u.getId(), u.getType(), u.getOwnerId(), u.getPosition().x(), u.getPosition().y(), u.getHp()));
        }
        List<CorpseDot> corpses = new ArrayList<>();
        for (Corpse c : engine.getCorpses()) {
            corpses.add(new CorpseDot(c.position().x(), c.position().y(), c.type()));
        }
        List<TrapDot> traps = new ArrayList<>();
        for (Trap t : engine.getTraps()) {
            traps.add(new TrapDot(t.position().x(), t.position().y(), t.ownerId()));
        }
        return new WorldSnapshot(engine.getTick(), tickMillis, engine.getMap(), story, List.copyOf(players), List.copyOf(units),
                List.copyOf(corpses), List.copyOf(traps));
    }

    public String colorOf(int owner) {
        if (owner == Unit.NEUTRAL) return "#b4a48c";
        for (PlayerRow p : players) {
            if (p.id() == owner) return p.color();
        }
        return "#8a8580";
    }

    public long undeadCount() {
        return units.stream().filter(u -> u.owner() != Unit.NEUTRAL).count();
    }

    public long wildCount() {
        return units.stream().filter(u -> u.owner() == Unit.NEUTRAL).count();
    }
}
