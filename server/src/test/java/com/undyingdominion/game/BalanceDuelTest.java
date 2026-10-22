package com.undyingdominion.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balance smoke test in the gate (docs/design/characters.md §10): every commander duels the
 * Bulwark, the reference front line, three times under {@link DuelBrain}, scored as a two-player
 * match (see {@link Duel}). Every duel finishes inside five game-minutes, and no commander loses all
 * three. It catches a
 * broken kit, not a slightly strong one; the full matrix is BalanceReport's job.
 */
class BalanceDuelTest {

    private static final int DUELS = 3;

    @Test
    void noCommanderLosesEveryDuelToTheBulwark() {
        Roster roster = Roster.builtIn();
        Commander bulwark = roster.get("BULWARK");
        List<String> table = new ArrayList<>();
        List<String> brokenKits = new ArrayList<>();

        for (Commander commander : roster.pvp()) {
            if (commander == bulwark) continue;
            int wins = 0, losses = 0, draws = 0;
            StringBuilder duels = new StringBuilder();
            for (int seed = 1; seed <= DUELS; seed++) {
                Duel.Result result = Duel.fight(commander, bulwark, seed);
                assertTrue(result.ticks() <= Duel.LIMIT_TICKS, "a duel ran past five game-minutes");
                switch (result.winner()) {
                    case 0 -> wins++;
                    case 1 -> losses++;
                    default -> draws++;
                }
                duels.append(String.format(" %s %d-%d%s", result.winner() == 0 ? "W" : result.winner() == 1 ? "L" : "D",
                        result.pointsA(), result.pointsB(), result.ended() ? " out@" + result.ticks() / 10 + "s" : ""));
            }
            table.add(String.format("%-14s %d-%d-%d %s", commander.id(), wins, losses, draws, duels));
            if (losses == DUELS) brokenKits.add(commander.id());
        }

        System.out.println("Duels against the Bulwark (win-loss-draw):");
        table.forEach(line -> System.out.println("  " + line));
        assertTrue(brokenKits.isEmpty(), "lost all " + DUELS + " duels to the Bulwark: " + brokenKits);
    }
}
