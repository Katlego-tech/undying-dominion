package com.undyingdominion.net;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.world.MapConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scenarios run against a real server over a real WebSocket, as the Robot Worlds acceptance tests
 * ran against a real socket server. Each scenario gets its own server on a free port.
 */
class GameServerAcceptanceTest {

    private static final int MAX_PLAYERS = 3;

    private GameServer server;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() {
        Properties props = new Properties();
        props.setProperty("map.width", "20");
        props.setProperty("map.height", "12");
        props.setProperty("obstacle.ruins.1", "9,2,9,5");
        props.setProperty("obstacle.abyss.1", "14,8,15,9");
        GameEngine engine = new GameEngine(MapConfig.fromProperties(props), GameRules.defaults().maxNeutrals(0), new Random(1));
        server = new GameServer(engine, MAX_PLAYERS);
        server.start(0);
    }

    @AfterEach
    void stopServer() {
        clients.forEach(TestClient::abort);
        server.stop();
    }

    @Test
    void joiningGetsAWelcomeAndThenTheWorld() {
        TestClient vesper = connect();
        vesper.send("{\"command\":\"join\",\"name\":\"Vesper\"}");

        JSONObject welcome = vesper.await("welcome", m -> true);
        assertEquals("Vesper", welcome.getString("name"));
        assertEquals(20, welcome.getJSONObject("map").getInt("width"));
        int me = welcome.getInt("playerId");

        JSONObject state = vesper.await("state", m -> !m.getJSONObject("you").isNull("necromancerId"));
        assertTrue(hasUnit(state, "NECROMANCER", me));
    }

    @Test
    void aSummonedSkeletonAppearsInTheWorld() {
        TestClient vesper = joined("Vesper");
        int me = vesper.playerId;

        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");

        vesper.await("state", m -> hasUnit(m, "SKELETON_WARRIOR", me));
    }

    @Test
    void theWorldCannotHaveTwoNecromancersWithTheSameName() {
        joined("Corvin");
        TestClient impostor = connect();

        impostor.send("{\"command\":\"join\",\"name\":\"corvin\"}");

        assertEquals("Too many of you in this world", impostor.await("error", m -> true).getString("message"));
    }

    @Test
    void aFullWorldTurnsNewNecromancersAway() {
        for (int i = 0; i < MAX_PLAYERS; i++) joined("Necro" + i);
        TestClient late = connect();

        late.send("{\"command\":\"join\",\"name\":\"Late\"}");

        assertEquals("No more space in this world", late.await("error", m -> true).getString("message"));
    }

    @Test
    void commandsBeforeJoiningAreRefused() {
        TestClient stranger = connect();

        stranger.send("{\"command\":\"summon\",\"unit\":\"LICH\"}");

        assertEquals("Join the Dominion first", stranger.await("error", m -> true).getString("message"));
    }

    @Test
    void anUnknownCommandIsUnsupported() {
        TestClient vesper = joined("Vesper");

        vesper.send("{\"command\":\"luanch\"}");

        assertEquals("Unsupported command", vesper.await("error", m -> true).getString("message"));
    }

    @Test
    void theWebApiDescribesTheWorldButNotWhereAnyoneIs() throws Exception {
        joined("Vesper");
        HttpResponse<String> response = get("/api/world");

        assertEquals(200, response.statusCode());
        JSONObject world = new JSONObject(response.body());
        assertEquals(20, world.getInt("width"));
        assertEquals(2, world.getJSONArray("obstacles").length());
        assertFalse(world.has("units"));
    }

    @Test
    void theBrowserClientIsServed() throws Exception {
        HttpResponse<String> response = get("/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>Undying Dominion</title>"));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean hasUnit(JSONObject state, String type, int owner) {
        JSONArray units = state.getJSONArray("units");
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.getJSONObject(i);
            if (unit.getString("type").equals(type) && unit.getInt("owner") == owner) return true;
        }
        return false;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private TestClient connect() {
        TestClient client = new TestClient();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), client)
                .join();
        clients.add(client);
        return client;
    }

    private TestClient joined(String name) {
        TestClient client = connect();
        client.send(new JSONObject().put("command", "join").put("name", name).toString());
        client.playerId = client.await("welcome", m -> true).getInt("playerId");
        return client;
    }

    private static final class TestClient implements WebSocket.Listener {
        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;
        private int playerId;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                inbox.add(new JSONObject(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        void send(String json) {
            socket.sendText(json, true).join();
        }

        /** Waits for the first message of this type that matches, skipping everything else. */
        JSONObject await(String type, Predicate<JSONObject> match) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (System.nanoTime() < deadline) {
                    JSONObject message = inbox.poll(100, TimeUnit.MILLISECONDS);
                    if (message != null && type.equals(message.getString("type")) && match.test(message)) {
                        return message;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fail("No matching '" + type + "' message within 5 seconds");
        }

        void abort() {
            socket.abort();
        }
    }
}
