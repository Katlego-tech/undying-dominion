package com.undyingdominion.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The balance smoke test in the gate (docs/design/characters.md §10). Every commander duels the first
 * three of four references from different roles that aren't itself (the Bulwark, the Hexer, the
 * Berserker, the Seer), three times each, under
 * {@link DuelBrain}, scored as a two-player match (see {@link Duel}). Every duel finishes inside five
 * game-minutes, and no commander loses all nine. It catches a broken kit, which loses to everyone;
 * one hard counter is allowed, and the full matrix is BalanceReport's job.
 */
class BalanceDuelTest {

    private static final List<String> REFERENCES = List.of("BULWARK", "HEXER", "BERSERKER", "SEER");
    private static final int OPPONENTS = 3;
    private static final int DUELS = 3;

    @Test
    void noCommanderLosesEveryDuelToTheReferences() {
        Roster roster = Roster.builtIn();
        List<String> table = new ArrayList<>();
        List<String> brokenKits = new ArrayList<>();

        for (Commander commander : roster.pvp()) {
            int wins = 0, played = 0, opponents = 0;
            StringBuilder line = new StringBuilder(String.format("%-14s", commander.id()));
            for (String id : REFERENCES) {
                Commander reference = roster.get(id);
                if (reference == commander || opponents == OPPONENTS) continue;
                opponents++;
                line.append("  vs ").append(id, 0, 4).append(' ');
                for (int seed = 1; seed <= DUELS; seed++) {
                    Duel.Result result = Duel.fight(commander, reference, seed);
                    assertTrue(result.ticks() <= Duel.LIMIT_TICKS, "a duel ran past five game-minutes");
                    played++;
                    if (result.winner() == 0) wins++;
                    line.append(result.winner() == 0 ? 'W' : result.winner() == 1 ? 'L' : 'D');
                }
            }
            table.add(line + String.format("  %d/%d", wins, played));
            if (wins == 0) brokenKits.add(commander.id());
        }

        System.out.println("Duels against the references:");
        table.forEach(row -> System.out.println("  " + row));
        assertTrue(brokenKits.isEmpty(), "won none of their duels against the references: " + brokenKits);
    }
}
