package com.undyingdominion.net;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.TestSocket;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rejoining after a dropped connection, played over real WebSockets through the real startup path.
 * One test per row of docs/design/reconnect.md §9, each a transition of the seat's state machine.
 * The grace is timed by a clock the test moves on, so no test waits for it to run out.
 */
class ReconnectAcceptanceTest {

    private static final String ENDED = "That session has ended. Join again";

    @TempDir
    Path dir;

    /** Starts at an odd value so nothing relies on the clock reading zero. */
    private final AtomicLong clock = new AtomicLong(1_000_000_007L);
    private Dominion dominion;
    private final List<TestSocket> sockets = new ArrayList<>();

    @AfterEach
    void stop() {
        sockets.forEach(TestSocket::abort);
        if (dominion != null) dominion.close();
    }

    @Test
    void aRejoinWithinGraceTakesBackTheSameNecromancer() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        String token = welcome.getString("token");
        assertTrue(token.matches("[A-Za-z0-9_-]{22}"), "a 22-character URL-safe token, got " + token);
        String retinue = welcome.getJSONObject("commander").getJSONArray("retinue").getJSONObject(0).getString("name");
        vesper.send(new JSONObject().put("command", "summon").put("unit", retinue));
        vesper.awaitState(m -> m.getJSONObject("you").getInt("legion") == 1);
        vesper.send("{\"command\":\"pause\"}");
        JSONObject before = vesper.awaitState(m -> m.has("paused"));

        vesper.abort();
        awaitAway(vesper);
        passSeconds(29);
        TestSocket back = rejoin(token);

        JSONObject again = back.awaitType("welcome");
        assertEquals(welcome.getInt("playerId"), again.getInt("playerId"));
        assertEquals("Vesper", again.getString("name"));
        assertEquals(token, again.getString("token"), "the token never changes");
        JSONObject after = back.awaitState(m -> true);
        assertEquals(before.getLong("tick"), after.getLong("tick"), "the world held still");
        for (String field : List.of("necromancerId", "soul", "legion", "level")) {
            assertEquals(before.getJSONObject("you").get(field), after.getJSONObject("you").get(field), field);
        }
    }

    @Test
    void aRejoinAfterGraceIsRefusedAndTheSocketCanJoinAfresh() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        vesper.abort();
        awaitAway(vesper);
        passSeconds(30);

        TestSocket back = rejoin(welcome.getString("token"));
        assertEquals(ENDED, back.awaitType("error").getString("message"));

        back.send("{\"command\":\"join\",\"name\":\"Vesper\"}");
        JSONObject fresh = back.awaitType("welcome");
        assertNotEquals(welcome.getInt("playerId"), fresh.getInt("playerId"), "a new necromancer");
        assertNotEquals(welcome.getString("token"), fresh.getString("token"));
    }

    @Test
    void leavingGivesUpTheSeatAtOnceWithoutWaitingOutTheGrace() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        TestSocket watcher = join("Corvin");
        watcher.awaitType("welcome");
        vesper.send("{\"command\":\"pause\"}");
        watcher.awaitState(m -> m.has("paused"));

        vesper.send("{\"command\":\"leave\"}");

        assertEquals("Left the Dominion", vesper.awaitClose());
        watcher.awaitState(m -> !m.has("paused"));
        TestSocket back = rejoin(welcome.getString("token"));
        assertEquals(ENDED, back.awaitType("error").getString("message"), "no grace is held for a player who left");
    }

    @Test
    void leavingBeforeJoiningIsRefused() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket stranger = TestSocket.open(dominion.server().port());
        sockets.add(stranger);

        stranger.send("{\"command\":\"leave\"}");

        assertEquals("Join the Dominion first", stranger.awaitType("error").getString("message"));
    }

    @Test
    void aSecondWindowTakesTheSeatOver() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket first = join("Vesper");
        JSONObject welcome = first.awaitType("welcome");

        TestSocket second = rejoin(welcome.getString("token"));

        assertEquals(welcome.getInt("playerId"), second.awaitType("welcome").getInt("playerId"));
        assertEquals("Taken up in another window", first.awaitClose());
        second.awaitState(m -> true);
    }

    @Test
    void aPurgedPlayerCannotRejoin() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");

        dominion.server().disconnect(welcome.getInt("playerId"), "Purged by the Dominion's keeper");
        assertEquals("Purged by the Dominion's keeper", vesper.awaitClose());

        TestSocket back = rejoin(welcome.getString("token"));
        assertEquals(ENDED, back.awaitType("error").getString("message"));
    }

    @Test
    void aMadeUpTokenIsRefused() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket stranger = rejoin("q3V9cX0bTqKk1yH6m2wJ8A");
        assertEquals(ENDED, stranger.awaitType("error").getString("message"));
    }

    @Test
    void theWorldHoldsStillWhileEveryoneIsAway() throws InterruptedException {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        String token = vesper.awaitType("welcome").getString("token");
        vesper.awaitState(m -> true);

        vesper.abort();
        awaitAway(vesper);
        await("the away pause", () -> dominion.server().pause() != null
                && dominion.server().pause().getString("reason").equals("away"));
        long stoppedAt = dominion.engine().getTick();
        Thread.sleep(600);  // six ticks, were the world running
        assertEquals(stoppedAt, dominion.engine().getTick(), "no tick while everyone is away");

        TestSocket back = rejoin(token);
        long first = back.awaitState(m -> true).getLong("tick");
        back.awaitState(m -> m.getLong("tick") > first + 3);
    }

    @Test
    void aPauseHoldsWhileWhoeverPausedIsAwayAndOthersSeeThemAway() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        vesper.awaitType("welcome");
        TestSocket corvin = join("Corvin");
        corvin.awaitType("welcome");
        vesper.send("{\"command\":\"pause\"}");
        long frozenAt = corvin.awaitState(m -> m.has("paused")).getLong("tick");

        vesper.abort();
        awaitAway(vesper);
        JSONObject held = corvin.awaitState(m -> isAway(m, "Vesper"));
        assertEquals("player", held.getJSONObject("paused").getString("reason"), "still Vesper's pause");
        assertEquals(frozenAt, held.getLong("tick"));

        passSeconds(30);
        corvin.awaitState(m -> !m.has("paused") && m.getLong("tick") > frozenAt);
    }

    @Test
    void anAwayPlayerKeepsTheirPlaceInAFullWorld() {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild", "-mc", "1");
        TestSocket vesper = join("Vesper");
        String token = vesper.awaitType("welcome").getString("token");
        vesper.abort();
        awaitAway(vesper);

        TestSocket corvin = join("Corvin");
        assertEquals("No more space in this world", corvin.awaitType("error").getString("message"));

        corvin.send("{\"command\":\"rejoin\",\"token\":\"" + token + "\"}");
        assertEquals("Vesper", corvin.awaitType("welcome").getString("name"));
    }

    @Test
    void anAwayPlayerRejoinsIntoTheChapterTheCampaignMovedOnTo() throws Exception {
        dominion = raise("-p", "0", "--campaign", trialCampaign().toString(), "--db", db(), "--seed", "4");
        TestSocket vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        int level = vesper.awaitState(m -> true).getJSONObject("you").getInt("level");
        vesper.abort();
        awaitAway(vesper);

        dominion.server().runOnLoop(() -> dominion.story().jumpTo(2));
        await("the next chapter's map", () -> dominion.map().getWidth() == 16);
        TestSocket back = rejoin(welcome.getString("token"));

        JSONObject again = back.awaitType("welcome");
        assertEquals(welcome.getInt("playerId"), again.getInt("playerId"));
        assertEquals(16, again.getJSONObject("map").getInt("width"), "the hall, chapter 2");
        JSONObject state = back.awaitState(m -> true);
        assertEquals(2, state.getJSONObject("story").getInt("chapter"));
        assertEquals(level, state.getJSONObject("you").getInt("level"));
    }

    @Test
    void aGraceOutsideItsRangeIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[] {"--grace", "3601"}));
        assertEquals("--grace takes 0 to 3600 seconds", refused.getMessage());
    }

    // ------------------------------------------------------------------ helpers

    /** Starts a Dominion the way Main does, on the test's clock. An unknown option fails the test. */
    private Dominion raise(String... args) {
        try {
            return Dominion.raise(ServerOptions.parse(args), clock::get);
        } catch (IllegalArgumentException | IOException e) {
            return fail("the server would not start: " + e.getMessage());
        }
    }

    private void passSeconds(int seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    /** Waits until the server has noticed this socket's drop and is holding the player's place. */
    private void awaitAway(TestSocket socket) {
        int playerId = socket.playerId();
        await("the drop to be noticed", () -> dominion.server().isAway(playerId));
    }

    private static boolean isAway(JSONObject state, String name) {
        JSONArray players = state.getJSONArray("players");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.getJSONObject(i);
            if (player.getString("name").equals(name)) return player.getBoolean("away");
        }
        return false;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("waited 8 seconds for " + what);
    }

    private String db() {
        return dir.resolve("saves.db").toString();
    }

    private Path trialCampaign() throws Exception {
        Path source = Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent();
        Path campaign = dir.resolve("trial");
        Files.createDirectories(campaign.resolve("maps"));
        for (String file : List.of("campaign.json", "maps/yard.properties", "maps/hall.properties")) {
            Files.copy(source.resolve(file), campaign.resolve(file));
        }
        return campaign;
    }

    private TestSocket join(String name) {
        TestSocket socket = TestSocket.join(dominion.server().port(), name);
        sockets.add(socket);
        return socket;
    }

    private TestSocket rejoin(String token) {
        TestSocket socket = TestSocket.rejoin(dominion.server().port(), token);
        sockets.add(socket);
        return socket;
    }
}
