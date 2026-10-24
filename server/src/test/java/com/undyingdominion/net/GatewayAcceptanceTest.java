package com.undyingdominion.net;

import com.undyingdominion.TestSocket;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.world.GameMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One address, several worlds (docs/design/modes.md §3): the Gateway routes each socket by its first message. */
class GatewayAcceptanceTest {

    private GameServer wide;
    private GameServer narrow;
    private Gateway gateway;
    private final List<TestSocket> sockets = new ArrayList<>();

    @BeforeEach
    void start() {
        // Two worlds told apart by their maps: 20 wide is the free-for-all, 12 wide stands in for a campaign.
        wide = world(20);
        narrow = world(12).mode("campaign");
        gateway = new Gateway(new Gateway.Router() {
            @Override public GameServer forJoin(JSONObject join) throws ProtocolException {
                String mode = join.optString("mode", "free-for-all");
                if (mode.equals("free-for-all")) return wide;
                if (mode.equals("campaign")) return narrow;
                throw new ProtocolException("No such mode: " + mode);
            }
            @Override public List<GameServer> worlds() { return List.of(wide, narrow); }
            @Override public GameServer lobby()        { return wide; }
            @Override public JSONObject modes() {
                return new JSONObject().put("modes", new JSONArray().put(wide.modeEntry()).put(narrow.modeEntry()));
            }
        });
        gateway.start(0);
    }

    @AfterEach
    void stop() {
        sockets.forEach(TestSocket::abort);
        wide.stop();
        narrow.stop();
        gateway.stop();
    }

    @Test
    void eachPlayerLandsInTheWorldTheirJoinAsksFor() {
        TestSocket corvin = open();
        corvin.send(new JSONObject().put("command", "join").put("name", "Corvin"));
        TestSocket lin = open();
        lin.send(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));

        assertEquals(20, corvin.awaitType("welcome").getJSONObject("map").getInt("width"), "no mode: the free-for-all");
        assertEquals(12, lin.awaitType("welcome").getJSONObject("map").getInt("width"));
        assertEquals(1, wide.joinedCount());
        assertEquals(1, narrow.joinedCount());
    }

    @Test
    void aRejoinTokenFindsItsOwnWorld() {
        TestSocket lin = open();
        lin.send(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        String token = lin.awaitType("welcome").getString("token");
        lin.abort();

        TestSocket back = open();
        back.send(new JSONObject().put("command", "rejoin").put("token", token));
        assertEquals(12, back.awaitType("welcome").getJSONObject("map").getInt("width"));
    }

    @Test
    void aRefusedJoinCanTryAnotherWorldOnTheSameSocket() {
        TestSocket lin = open();
        lin.send(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "match"));
        assertEquals("No such mode: match", lin.awaitType("error").getString("message"));

        lin.send(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        assertEquals(12, lin.awaitType("welcome").getJSONObject("map").getInt("width"));
        lin.awaitState(m -> true);
    }

    @Test
    void theModesListEveryWorldAndHowFullItIs() throws Exception {
        open().send(new JSONObject().put("command", "join").put("name", "Corvin"));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (wide.joinedCount() < 1 && System.nanoTime() < deadline) Thread.sleep(20);

        JSONArray modes = new JSONObject(get("/modes")).getJSONArray("modes");
        assertEquals("free-for-all", modes.getJSONObject(0).getString("id"));
        assertEquals(1, modes.getJSONObject(0).getInt("players"));
        assertEquals("campaign", modes.getJSONObject(1).getString("id"));
        assertEquals(0, modes.getJSONObject(1).getInt("players"));
    }

    private GameServer world(int width) {
        GameEngine engine = new GameEngine(new GameMap(width, 10, List.of()), GameRules.defaults().maxNeutrals(0), new Random(3));
        GameServer world = new GameServer(engine, 4, Duration.ofSeconds(30));
        world.startLoop();
        return world;
    }

    private TestSocket open() {
        TestSocket socket = TestSocket.open(gateway.port());
        sockets.add(socket);
        return socket;
    }

    private String get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
