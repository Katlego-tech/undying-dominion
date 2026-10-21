package com.undyingdominion.story;

import com.undyingdominion.TestSocket;
import com.undyingdominion.game.Unit;
import com.undyingdominion.net.GameServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A campaign played over a real WebSocket: briefing, objective in the state, victory, next chapter. */
class StoryAcceptanceTest {

    private GameServer server;
    private TestSocket socket;

    @AfterEach
    void stop() {
        if (socket != null) socket.abort();
        if (server != null) server.stop();
    }

    @Test
    void aChapterIsBriefedWonAndFollowedByTheNext() throws Exception {
        StoryDirector director = new StoryDirector(Campaign.load(ChapterRunTest.folder()), new Random(4))
                .secondsBetweenChapters(1);
        server = new GameServer(director.firstEngine(), 4);
        server.start(0);
        director.attach(server);

        socket = TestSocket.join(server.port(), "Vesper");

        JSONObject welcome = socket.awaitType("welcome");
        assertEquals(20, welcome.getJSONObject("map").getInt("width"));
        assertTrue(welcome.getJSONObject("unitTypes").getJSONObject("TEST_WARDEN").getBoolean("boss"));

        JSONObject briefing = socket.awaitType("story");
        assertEquals("briefing", briefing.getString("kind"));
        assertEquals("The Yard", briefing.getString("title"));

        JSONObject state = socket.awaitType("state", m -> m.has("story"));
        assertEquals("Defeat the warden", state.getJSONObject("story").getString("objective"));
        socket.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        socket.awaitType("state", m -> m.getJSONObject("you").getInt("legion") == 1);

        server.runOnLoop(() -> server.engine().getUnits().stream()
                .filter(u -> u.getType().name().equals("TEST_WARDEN"))
                .forEach(u -> u.takeDamage(10_000, Unit.NEUTRAL + 1)));

        JSONObject victory = socket.awaitType("story", m -> m.getString("kind").equals("victory"));
        assertEquals("The yard is quiet.", victory.getJSONArray("lines").getString(0));

        JSONObject nextWelcome = socket.awaitType("welcome");
        assertEquals(16, nextWelcome.getJSONObject("map").getInt("width"), "the second chapter's map");
        assertEquals(welcome.getInt("playerId"), nextWelcome.getInt("playerId"), "the same player carried over");
        JSONObject nextBriefing = socket.awaitType("story");
        assertEquals("The Hall", nextBriefing.getString("title"));
        JSONObject nextState = socket.awaitState(m -> m.getJSONObject("story").getString("title").equals("The Hall"));
        assertEquals(1, nextState.getJSONObject("you").getInt("legion"), "the summoned skeleton came along");
    }
}
