package com.undyingdominion.net;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rejoining after a dropped connection, played over real WebSockets through the real startup path.
 * One test per row of docs/design/reconnect.md §9, each a transition of the seat's state machine.
 */
class ReconnectAcceptanceTest {

    private static final String ENDED = "That session has ended. Join again";

    @TempDir
    Path dir;

    private Dominion dominion;
    private final List<Client> clients = new ArrayList<>();

    @AfterEach
    void stop() {
        clients.forEach(Client::close);
        if (dominion != null) dominion.close();
    }

    @Test
    void aRejoinWithinGraceTakesBackTheSameNecromancer() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        Client vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        String token = welcome.getString("token");
        assertTrue(token.matches("[A-Za-z0-9_-]{22}"), "a 22-character URL-safe token, got " + token);
        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        vesper.await("a summoned skeleton", m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
        vesper.send("{\"command\":\"pause\"}");
        JSONObject before = vesper.await("the world paused", m -> isState(m) && m.has("paused"));

        vesper.close();
        Client back = rejoin(token);

        JSONObject again = back.awaitType("welcome");
        assertEquals(welcome.getInt("playerId"), again.getInt("playerId"));
        assertEquals("Vesper", again.getString("name"));
        assertEquals(token, again.getString("token"), "the token never changes");
        JSONObject after = back.await("a state", ReconnectAcceptanceTest::isState);
        assertEquals(before.getLong("tick"), after.getLong("tick"), "the world held still");
        for (String field : List.of("necromancerId", "soul", "legion", "level")) {
            assertEquals(before.getJSONObject("you").getInt(field), after.getJSONObject("you").getInt(field), field);
        }
    }

    @Test
    void aRejoinAfterGraceIsRefusedAndTheSocketCanJoinAfresh() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild", "--grace", "1");
        Client vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        vesper.close();
        Thread.sleep(1_600);

        Client back = rejoin(welcome.getString("token"));
        assertEquals(ENDED, back.awaitType("error").getString("message"));

        back.send("{\"command\":\"join\",\"name\":\"Vesper\"}");
        JSONObject fresh = back.awaitType("welcome");
        assertNotEquals(welcome.getInt("playerId"), fresh.getInt("playerId"), "a new necromancer");
        assertNotEquals(welcome.getString("token"), fresh.getString("token"));
    }

    @Test
    void aSecondWindowTakesTheSeatOver() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        Client first = join("Vesper");
        JSONObject welcome = first.awaitType("welcome");

        Client second = rejoin(welcome.getString("token"));

        assertEquals(welcome.getInt("playerId"), second.awaitType("welcome").getInt("playerId"));
        assertEquals("Taken up in another window", first.awaitClose());
        second.await("states for the new window", ReconnectAcceptanceTest::isState);
    }

    @Test
    void aPurgedPlayerCannotRejoin() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        Client vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");

        dominion.server().disconnect(welcome.getInt("playerId"), "Purged by the Dominion's keeper");
        assertEquals("Purged by the Dominion's keeper", vesper.awaitClose());

        Client back = rejoin(welcome.getString("token"));
        assertEquals(ENDED, back.awaitType("error").getString("message"));
    }

    @Test
    void aMadeUpTokenIsRefused() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        Client stranger = rejoin("q3V9cX0bTqKk1yH6m2wJ8A");
        assertEquals(ENDED, stranger.awaitType("error").getString("message"));
    }

    @Test
    void theWorldHoldsStillWhileEveryoneIsAway() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild");
        Client vesper = join("Vesper");
        String token = vesper.awaitType("welcome").getString("token");
        long last = vesper.await("a state", ReconnectAcceptanceTest::isState).getLong("tick");

        vesper.close();
        Thread.sleep(1_500);
        Client back = rejoin(token);

        long first = back.await("a state", ReconnectAcceptanceTest::isState).getLong("tick");
        assertTrue(first - last < 5, "fifteen ticks would have passed in 1.5 s; " + (first - last) + " did");
        back.await("the world running again", m -> isState(m) && m.getLong("tick") > first + 3);
    }

    @Test
    void aPauseHoldsWhileWhoeverPausedIsAway() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild", "--grace", "2");
        Client vesper = join("Vesper");
        Client corvin = join("Corvin");
        corvin.awaitType("welcome");
        vesper.send("{\"command\":\"pause\"}");
        long frozenAt = corvin.await("the pause", m -> isState(m) && m.has("paused")).getLong("tick");

        vesper.close();
        Thread.sleep(500);
        corvin.drain();
        JSONObject held = corvin.await("a state", ReconnectAcceptanceTest::isState);
        assertEquals("player", held.getJSONObject("paused").getString("reason"), "still Vesper's pause");
        assertEquals(frozenAt, held.getLong("tick"));

        corvin.await("the world running once the grace ends",
                m -> isState(m) && !m.has("paused") && m.getLong("tick") > frozenAt);
    }

    @Test
    void anAwayPlayerKeepsTheirPlaceInAFullWorld() throws Exception {
        dominion = raise("-p", "0", "--db", db(), "--seed", "4", "--no-wild", "-mc", "1");
        Client vesper = join("Vesper");
        String token = vesper.awaitType("welcome").getString("token");
        vesper.close();

        Client corvin = join("Corvin");
        assertEquals("No more space in this world", corvin.awaitType("error").getString("message"));

        corvin.send("{\"command\":\"rejoin\",\"token\":\"" + token + "\"}");
        assertEquals("Vesper", corvin.awaitType("welcome").getString("name"));
    }

    @Test
    void anAwayPlayerRejoinsIntoTheChapterTheCampaignMovedOnTo() throws Exception {
        dominion = raise("-p", "0", "--campaign", trialCampaign().toString(), "--db", db(), "--seed", "4");
        Client vesper = join("Vesper");
        JSONObject welcome = vesper.awaitType("welcome");
        int level = vesper.await("a state", ReconnectAcceptanceTest::isState).getJSONObject("you").getInt("level");
        vesper.close();

        dominion.server().runOnLoop(() -> dominion.story().jumpTo(2));
        Thread.sleep(300);
        Client back = rejoin(welcome.getString("token"));

        JSONObject again = back.awaitType("welcome");
        assertEquals(welcome.getInt("playerId"), again.getInt("playerId"));
        assertEquals(16, again.getJSONObject("map").getInt("width"), "the hall, chapter 2");
        JSONObject state = back.await("a state", ReconnectAcceptanceTest::isState);
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

    /** Starts a Dominion the way Main does. An option it doesn't know yet fails the test, not errors it. */
    private static Dominion raise(String... args) throws IOException {
        try {
            return Dominion.raise(ServerOptions.parse(args));
        } catch (IllegalArgumentException e) {
            return fail("the server would not start: " + e.getMessage());
        }
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

    private Client join(String name) {
        Client client = new Client(dominion.server().port());
        clients.add(client);
        client.send("{\"command\":\"join\",\"name\":\"" + name + "\"}");
        return client;
    }

    private Client rejoin(String token) {
        Client client = new Client(dominion.server().port());
        clients.add(client);
        client.send("{\"command\":\"rejoin\",\"token\":\"" + token + "\"}");
        return client;
    }

    private static boolean isState(JSONObject message) {
        return message.getString("type").equals("state");
    }

    /** One WebSocket, with everything the server sends queued and the reason it was closed kept. */
    private static final class Client {

        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final CompletableFuture<String> closed = new CompletableFuture<>();
        private final WebSocket socket;

        Client(int port) {
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                        private final StringBuilder partial = new StringBuilder();

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            partial.append(data);
                            if (last) {
                                inbox.add(new JSONObject(partial.toString()));
                                partial.setLength(0);
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                            closed.complete(reason);
                            return null;
                        }
                    }).join();
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        JSONObject await(String what, Predicate<JSONObject> match) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                JSONObject message = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && match.test(message)) return message;
            }
            return fail("waited 8 seconds for " + what);
        }

        JSONObject awaitType(String type) throws InterruptedException {
            return await("a " + type, m -> m.getString("type").equals(type));
        }

        /** The reason the server gave for closing this socket. */
        String awaitClose() throws Exception {
            return closed.get(8, TimeUnit.SECONDS);
        }

        /** Forgets everything received so far, so the next await sees only what comes after. */
        void drain() {
            inbox.clear();
        }

        /** Drops the socket without a close handshake, as a refreshed or killed tab does. */
        void close() {
            socket.abort();
        }
    }
}
