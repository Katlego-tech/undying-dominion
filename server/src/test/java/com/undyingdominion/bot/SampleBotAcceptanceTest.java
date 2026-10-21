package com.undyingdominion.bot;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The sample bot against a real server: it plays, it rejoins after a drop, and it joins afresh
 * after its place is gone (docs/design/bot.md §6). The server is only looked at, never driven,
 * except for the purge a host would do.
 */
class SampleBotAcceptanceTest {

    @TempDir
    Path dir;

    private Dominion dominion;
    private SampleBot bot;

    @AfterEach
    void stop() {
        if (bot != null) bot.close();
        if (dominion != null) dominion.close();
    }

    @Test
    void theBotBuildsALegion() throws Exception {
        start();
        await("a legion of two", () -> legion("Bonewright") >= 2, 20);
    }

    @Test
    void theBotRejoinsAsTheSamePlayerAfterADrop() throws Exception {
        start();
        await("the welcome", () -> bot.playerId() != null, 8);
        int before = bot.playerId();
        await("the server to see the bot", () -> dominion.server().joinedCount() == 1, 8);

        bot.abort();
        await("the drop to be noticed", () -> dominion.server().isAway(before), 8);
        await("the rejoin", () -> !dominion.server().isAway(before), 10);
        assertEquals(before, bot.playerId());
    }

    @Test
    void thePurgedBotJoinsAfresh() throws Exception {
        start();
        await("the welcome", () -> bot.playerId() != null, 8);
        int before = bot.playerId();
        await("the server to see the bot", () -> dominion.server().joinedCount() == 1, 8);

        dominion.server().disconnect(before, "Purged by the Dominion's keeper");
        await("a fresh join", () -> bot.playerId() != null && bot.playerId() != before
                && dominion.engine().findPlayer("Bonewright") != null, 15);
        assertNotEquals(before, bot.playerId());
    }

    private void start() throws Exception {
        String db = Files.createDirectories(dir).resolve("saves.db").toString();
        dominion = Dominion.raise(ServerOptions.parse(new String[] {"-p", "0", "--db", db, "--seed", "4"}));
        bot = new SampleBot(URI.create("ws://localhost:" + dominion.server().port() + "/ws"), "Bonewright");
        bot.start();
    }

    private int legion(String name) {
        GameEngine engine = dominion.engine();
        Player player = engine.findPlayer(name);
        return player == null ? 0 : engine.legionSizeOf(player);
    }

    private static void await(String what, BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("waited " + seconds + " seconds for " + what);
    }
}
