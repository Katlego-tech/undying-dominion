package com.undyingdominion.net;

import com.undyingdominion.TestSocket;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios run against a real server over a real WebSocket, as the Robot Worlds acceptance tests
 * ran against a real socket server. Each scenario gets its own server on a free port.
 */
class GameServerAcceptanceTest {

    private static final int MAX_PLAYERS = 3;

    private GameServer server;
    private final List<TestSocket> clients = new ArrayList<>();

    @BeforeEach
    void startServer() {
        Properties props = new Properties();
        props.setProperty("map.width", "20");
        props.setProperty("map.height", "12");
        props.setProperty("obstacle.ruins.1", "9,2,9,5");
        props.setProperty("obstacle.abyss.1", "14,8,15,9");
        GameEngine engine = new GameEngine(MapConfig.fromProperties(props), GameRules.defaults().maxNeutrals(0), new Random(1));
        // A drop is a removal here, as before rejoin existed; ReconnectAcceptanceTest covers the grace period.
        server = new GameServer(engine, MAX_PLAYERS, Duration.ZERO);
        server.start(0);
    }

    @AfterEach
    void stopServer() {
        clients.forEach(TestSocket::abort);
        server.stop();
    }

    @Test
    void joiningGetsAWelcomeAndThenTheWorld() {
        TestSocket vesper = connect();
        vesper.send("{\"command\":\"join\",\"name\":\"Vesper\"}");

        JSONObject welcome = vesper.awaitType("welcome", m -> true);
        assertEquals("Vesper", welcome.getString("name"));
        assertEquals(20, welcome.getJSONObject("map").getInt("width"));
        int me = welcome.getInt("playerId");

        JSONObject state = vesper.awaitType("state", m -> !m.getJSONObject("you").isNull("necromancerId"));
        assertTrue(hasUnit(state, "NECROMANCER", me));
    }

    @Test
    void aSummonedSkeletonAppearsInTheWorld() {
        TestSocket vesper = joined("Vesper");
        int me = vesper.playerId();

        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");

        vesper.awaitType("state", m -> hasUnit(m, "SKELETON_WARRIOR", me));
    }

    @Test
    void theWorldCannotHaveTwoNecromancersWithTheSameName() {
        joined("Corvin");
        TestSocket impostor = connect();

        impostor.send("{\"command\":\"join\",\"name\":\"corvin\"}");

        assertEquals("Too many of you in this world", impostor.awaitType("error", m -> true).getString("message"));
    }

    @Test
    void aFullWorldTurnsNewNecromancersAway() {
        for (int i = 0; i < MAX_PLAYERS; i++) joined("Necro" + i);
        TestSocket late = connect();

        late.send("{\"command\":\"join\",\"name\":\"Late\"}");

        assertEquals("No more space in this world", late.awaitType("error", m -> true).getString("message"));
    }

    @Test
    void commandsBeforeJoiningAreRefused() {
        TestSocket stranger = connect();

        stranger.send("{\"command\":\"summon\",\"unit\":\"LICH\"}");

        assertEquals("Join the Dominion first", stranger.awaitType("error", m -> true).getString("message"));
    }

    @Test
    void anUnknownCommandIsUnsupported() {
        TestSocket vesper = joined("Vesper");

        vesper.send("{\"command\":\"luanch\"}");

        assertEquals("Unsupported command", vesper.awaitType("error", m -> true).getString("message"));
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

    @Test
    void anyPlayerCanPauseTheWorldForEveryoneAndAnyoneCanResumeIt() {
        TestSocket vesper = joined("Vesper");
        TestSocket corvin = joined("Corvin");

        vesper.send("{\"command\":\"pause\"}");

        JSONObject paused = corvin.awaitType("state", m -> m.has("paused"));
        assertEquals("Vesper", paused.getJSONObject("paused").getString("by"));
        assertEquals("player", paused.getJSONObject("paused").getString("reason"));
        long frozenAt = paused.getLong("tick");
        assertEquals(frozenAt, corvin.awaitType("state", m -> true).getLong("tick"));
        assertEquals(frozenAt, corvin.awaitType("state", m -> true).getLong("tick"));

        corvin.send("{\"command\":\"resume\"}");

        vesper.awaitType("state", m -> !m.has("paused") && m.getLong("tick") > frozenAt);
    }

    @Test
    void readingTheStoryPausesASoloGame() {
        TestSocket vesper = joined("Vesper");

        vesper.send("{\"command\":\"reading\",\"open\":true}");
        JSONObject paused = vesper.awaitType("state", m -> m.has("paused"));
        assertEquals("story", paused.getJSONObject("paused").getString("reason"));

        vesper.send("{\"command\":\"reading\",\"open\":false}");
        vesper.awaitType("state", m -> !m.has("paused") && m.getLong("tick") > paused.getLong("tick"));
    }

    @Test
    void readingTheStoryDoesNotPauseOthers() {
        TestSocket vesper = joined("Vesper");
        TestSocket corvin = joined("Corvin");
        long before = corvin.awaitType("state", m -> true).getLong("tick");

        vesper.send("{\"command\":\"reading\",\"open\":true}");

        JSONObject later = corvin.awaitType("state", m -> m.getLong("tick") > before + 5);
        assertFalse(later.has("paused"));
    }

    @Test
    void thePauseLiftsWhenWhoeverPausedLeaves() {
        TestSocket vesper = joined("Vesper");
        TestSocket corvin = joined("Corvin");
        vesper.send("{\"command\":\"pause\"}");
        long frozenAt = corvin.awaitType("state", m -> m.has("paused")).getLong("tick");

        vesper.abort();

        corvin.awaitType("state", m -> !m.has("paused") && m.getLong("tick") > frozenAt);
    }

    @Test
    void aNecromancerCanJoinAPausedWorld() {
        TestSocket vesper = joined("Vesper");
        vesper.send("{\"command\":\"pause\"}");
        vesper.awaitType("state", m -> m.has("paused"));

        TestSocket corvin = joined("Corvin");

        JSONObject state = corvin.awaitType("state", m -> m.has("paused"));
        assertTrue(hasUnit(state, "NECROMANCER", corvin.playerId()));
    }

    @Test
    void theHostCanPauseAndResume() {
        TestSocket vesper = joined("Vesper");

        server.pauseByHost();
        assertEquals("host", vesper.awaitType("state", m -> m.has("paused")).getJSONObject("paused").getString("reason"));

        server.resume();
        vesper.awaitType("state", m -> !m.has("paused"));
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

    private TestSocket connect() {
        TestSocket client = TestSocket.open(server.port());
        clients.add(client);
        return client;
    }

    private TestSocket joined(String name) {
        TestSocket client = TestSocket.join(server.port(), name);
        clients.add(client);
        client.awaitType("welcome");
        return client;
    }
}
