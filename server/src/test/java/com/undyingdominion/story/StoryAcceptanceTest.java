package com.undyingdominion.story;

import com.undyingdominion.game.Unit;
import com.undyingdominion.net.GameServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** A campaign played over a real WebSocket: briefing, objective in the state, victory, next chapter. */
class StoryAcceptanceTest {

    private GameServer server;
    private WebSocket socket;

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

        BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
        socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), new WebSocket.Listener() {
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
                }).join();
        socket.sendText("{\"command\":\"join\",\"name\":\"Vesper\"}", true).join();

        JSONObject welcome = await(inbox, m -> m.getString("type").equals("welcome"));
        assertEquals(20, welcome.getJSONObject("map").getInt("width"));
        assertTrue(welcome.getJSONObject("unitTypes").getJSONObject("TEST_WARDEN").getBoolean("boss"));

        JSONObject briefing = await(inbox, m -> m.getString("type").equals("story"));
        assertEquals("briefing", briefing.getString("kind"));
        assertEquals("The Yard", briefing.getString("title"));

        JSONObject state = await(inbox, m -> m.getString("type").equals("state") && m.has("story"));
        assertEquals("Defeat the warden", state.getJSONObject("story").getString("objective"));
        socket.sendText("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}", true).join();
        await(inbox, m -> m.getString("type").equals("state") && m.getJSONObject("you").getInt("legion") == 1);

        server.runOnLoop(() -> server.engine().getUnits().stream()
                .filter(u -> u.getType().name().equals("TEST_WARDEN"))
                .forEach(u -> u.takeDamage(10_000, Unit.NEUTRAL + 1)));

        JSONObject victory = await(inbox, m -> m.getString("type").equals("story") && m.getString("kind").equals("victory"));
        assertEquals("The yard is quiet.", victory.getJSONArray("lines").getString(0));

        JSONObject nextWelcome = await(inbox, m -> m.getString("type").equals("welcome"));
        assertEquals(16, nextWelcome.getJSONObject("map").getInt("width"), "the second chapter's map");
        assertEquals(welcome.getInt("playerId"), nextWelcome.getInt("playerId"), "the same player carried over");
        JSONObject nextBriefing = await(inbox, m -> m.getString("type").equals("story"));
        assertEquals("The Hall", nextBriefing.getString("title"));
        JSONObject nextState = await(inbox, m -> m.getString("type").equals("state")
                && m.getJSONObject("story").getString("title").equals("The Hall"));
        assertEquals(1, nextState.getJSONObject("you").getInt("legion"), "the summoned skeleton came along");
    }

    private static JSONObject await(BlockingQueue<JSONObject> inbox, Predicate<JSONObject> match) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            JSONObject message = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && match.test(message)) return message;
        }
        return fail("no matching message within 8 seconds");
    }
}
