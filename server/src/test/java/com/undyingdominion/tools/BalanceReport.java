package com.undyingdominion.tools;

import com.undyingdominion.game.Commander;
import com.undyingdominion.game.Duel;
import com.undyingdominion.game.Roster;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The full balance run (docs/design/characters.md §10), outside the gate: every pair of PvP
 * commanders duels over {@link #SEEDS} seeds, and target/balance.md gets the win-rate matrix and each
 * commander's overall rate. Run it with {@code mvn test -Pbalance}.
 *
 * <p>The target: every commander wins 40–60 % overall, and no pairing is beyond 25–75 %. A draw
 * counts half to each side.
 */
@Tag("balance")
class BalanceReport {

    /** 20 by default; {@code -Dbalance.seeds=60} for a steadier read of single pairings. */
    static final int SEEDS = Integer.getInteger("balance.seeds", 20);

    @Test
    void writeTheMatrix() throws IOException {
        List<Commander> roster = Roster.builtIn().pvp();
        int n = roster.size();
        double[][] wins = new double[n][n];
        List<int[]> pairs = IntStream.range(0, n).boxed()
                .flatMap(i -> IntStream.range(i + 1, n).mapToObj(j -> new int[] {i, j})).toList();
        pairs.parallelStream().forEach(pair -> {
            double a = 0;
            for (int seed = 1; seed <= SEEDS; seed++) {
                int winner = Duel.fight(roster.get(pair[0]), roster.get(pair[1]), seed).winner();
                a += winner == 0 ? 1 : winner == -1 ? 0.5 : 0;
            }
            wins[pair[0]][pair[1]] = a / SEEDS;
            wins[pair[1]][pair[0]] = 1 - a / SEEDS;
        });

        StringBuilder md = new StringBuilder("# Balance report\n\n");
        md.append(pairs.size()).append(" pairs × ").append(SEEDS).append(" seeds, under DuelBrain on an open ")
                .append(Duel.SIZE).append("×").append(Duel.SIZE).append(" map. Target: 40–60 % overall, no pairing beyond 25–75 %.\n\n");
        md.append("## Overall\n\n| Commander | Role | Win rate | |\n|---|---|---|---|\n");
        int outliers = 0;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int j = 0; j < n; j++) if (j != i) sum += wins[i][j];
            double rate = sum / (n - 1);
            boolean off = rate < 0.4 || rate > 0.6;
            if (off) outliers++;
            md.append(String.format("| %s | %s | %.0f %% | %s |%n", roster.get(i).id(), roster.get(i).role(), rate * 100, off ? "**off**" : ""));
        }
        int lopsided = 0;
        md.append("\n## Row wins against column (%)\n\n| |");
        for (Commander c : roster) md.append(' ').append(c.id(), 0, 4).append(" |");
        md.append("\n|---|").append("---|".repeat(n)).append('\n');
        for (int i = 0; i < n; i++) {
            md.append("| ").append(roster.get(i).id()).append(" |");
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    md.append(" – |");
                    continue;
                }
                double rate = wins[i][j];
                boolean off = rate < 0.25 || rate > 0.75;
                if (off && i < j) lopsided++;
                md.append(off ? " **" : " ").append(Math.round(rate * 100)).append(off ? "** |" : " |");
            }
            md.append('\n');
        }
        md.append(String.format("%n%d commanders outside 40–60 %%, %d of %d pairings beyond 25–75 %%.%n", outliers, lopsided, pairs.size()));
        Path out = Path.of("target", "balance.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println(md);
    }
}
