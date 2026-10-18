package com.undyingdominion.persistence;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Saving a solo campaign and resuming it in a fresh server, played over real WebSockets through the
 * real startup path (docs/design/persistence.md §9).
 */
class SaveGameAcceptanceTest {

    @TempDir
    Path dir;

    private Path campaign;
    private String db;
    private Dominion dominion;
    private final List<Client> clients = new ArrayList<>();

    @BeforeEach
    void copyTheTrialCampaign() throws Exception {
        Path source = Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent();
        campaign = dir.resolve("trial");
        Files.createDirectories(campaign.resolve("maps"));
        Files.copy(source.resolve("campaign.json"), campaign.resolve("campaign.json"));
        Files.copy(source.resolve("maps/yard.properties"), campaign.resolve("maps/yard.properties"));
        Files.copy(source.resolve("maps/hall.properties"), campaign.resolve("maps/hall.properties"));
        db = dir.resolve("saves.db").toString();
    }

    @AfterEach
    void stop() {
        clients.forEach(Client::close);
        if (dominion != null) dominion.close();
    }

    @Test
    void aSavedSoloCampaignResumesExactlyWhereItStopped() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        Client vesper = join("Vesper");
        int playerId = vesper.await("the welcome", m -> m.getString("type").equals("welcome")).getInt("playerId");
        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        vesper.await("a summoned skeleton", m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
        vesper.await("two seconds of play", m -> isState(m) && m.getLong("tick") >= 20);

        vesper.send("{\"command\":\"pause\"}");
        vesper.await("the world paused", m -> isState(m) && m.has("paused"));
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        JSONObject before = vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.close();
        dominion.close();
        dominion = raise("-p", "0", "--resume", "crypt", "--db", db);

        Client someone = join("Someone");
        JSONObject welcome = someone.await("the welcome", m -> m.getString("type").equals("welcome"));
        assertEquals("Vesper", welcome.getString("name"), "the saved necromancer's name is kept");
        assertEquals(playerId, welcome.getInt("playerId"), "the saved player's id is reused");

        JSONObject after = someone.awaitNotice(
                "Resumed \"crypt\": chapter 1, The Yard. You are Vesper. Press P to carry on.");
        assertEquals("player", after.getJSONObject("paused").getString("reason"), "the world waits for the player");
        assertEquals("Vesper", after.getJSONObject("paused").getString("by"));

        assertEquals(before.getLong("tick"), after.getLong("tick"));
        for (String field : List.of("soul", "level", "xp", "legion")) {
            assertEquals(before.getJSONObject("you").getInt(field), after.getJSONObject("you").getInt(field), field);
        }
        assertEquals(project(before.getJSONArray("units"), "id", "type", "owner", "x", "y", "hp", "order"),
                project(after.getJSONArray("units"), "id", "type", "owner", "x", "y", "hp", "order"));
        assertEquals(project(before.getJSONArray("corpses"), "id", "type", "x", "y"),
                project(after.getJSONArray("corpses"), "id", "type", "x", "y"));
        assertEquals(project(before.getJSONArray("traps"), "id", "owner", "x", "y"),
                project(after.getJSONArray("traps"), "id", "owner", "x", "y"));
        assertEquals(before.getJSONObject("story").getString("progress"),
                after.getJSONObject("story").getString("progress"));
    }

    @Test
    void savingAFreeForAllIsRefused() throws Exception {
        dominion = raise("-p", "0", "--db", db, "--seed", "4", "--no-wild");
        Client vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saving only works in a story campaign");
    }

    @Test
    void savingWithTwoPlayersIsRefused() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        Client vesper = join("Vesper");
        Client corvin = join("Corvin");
        corvin.await("the welcome", m -> m.getString("type").equals("welcome"));
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saving only works with one player in the world");
    }

    @Test
    void aTakenNameIsRefusedUntilSavedAgainWithForce() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        Client vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        vesper.await("a summoned skeleton", m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("A save called \"crypt\" already exists. Save again with force to replace it");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\",\"force\":true}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.close();
        dominion.close();
        dominion = raise("-p", "0", "--resume", "crypt", "--db", db);
        Client back = join("Vesper");
        back.await("the newer save, with its skeleton",
                m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
    }

    @Test
    void anUnsupportedSaveNameIsRefused() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        Client vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"../x\"}");
        vesper.awaitNotice("Save names are 1 to 32 letters, digits, - or _");
    }

    @Test
    void resumingAnUnknownSaveRefusesToStart() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Dominion.raise(ServerOptions.parse(new String[] {"-p", "0", "--resume", "nosuch", "--db", db})));
        assertEquals("No save called \"nosuch\"", refused.getMessage());
    }

    @Test
    void resumingASaveWhoseCampaignChangedRefusesToStart() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        Client vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");
        vesper.close();
        dominion.close();
        dominion = null;

        Files.writeString(campaign.resolve("campaign.json"), Files.readString(campaign.resolve("campaign.json")) + "\n");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Dominion.raise(ServerOptions.parse(new String[] {"-p", "0", "--resume", "crypt", "--db", db})));
        assertEquals("The campaign has changed since this save was made", refused.getMessage());
    }

    @Test
    void resumeCannotBeCombinedWithACampaignOrAMap() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[] {"--resume", "crypt", "--campaign", campaign.toString()}));
        assertEquals("--resume takes its world from the save; drop --campaign and --map", refused.getMessage());
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

    private Client join(String name) {
        Client client = new Client(dominion.server().port());
        clients.add(client);
        client.send("{\"command\":\"join\",\"name\":\"" + name + "\"}");
        return client;
    }

    private static boolean isState(JSONObject message) {
        return message.getString("type").equals("state");
    }

    /** The named fields of every element, as text, so two snapshots compare field for field. */
    private static List<String> project(JSONArray items, String... fields) {
        List<String> projected = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            StringBuilder line = new StringBuilder();
            for (String field : fields) line.append(field).append('=').append(item.opt(field)).append(' ');
            projected.add(line.toString().trim());
        }
        return projected;
    }

    /** One player's WebSocket, with everything the server sends queued for the test to read. */
    private static final class Client {

        private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
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

        /** The state that carries {@code text} among the player's notices. */
        JSONObject awaitNotice(String text) throws InterruptedException {
            return await("the notice: " + text, m -> isState(m)
                    && m.getJSONObject("you").getJSONArray("notices").toList().contains(text));
        }

        void close() {
            socket.abort();
        }
    }
}
