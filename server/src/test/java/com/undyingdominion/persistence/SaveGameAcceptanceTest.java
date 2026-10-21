package com.undyingdominion.persistence;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.TestSocket;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private final List<TestSocket> clients = new ArrayList<>();

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
        clients.forEach(TestSocket::abort);
        if (dominion != null) dominion.close();
    }

    @Test
    void aSavedSoloCampaignResumesExactlyWhereItStopped() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        int playerId = vesper.await("the welcome", m -> m.getString("type").equals("welcome")).getInt("playerId");
        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        vesper.await("a summoned skeleton", m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
        vesper.await("two seconds of play", m -> isState(m) && m.getLong("tick") >= 20);

        vesper.send("{\"command\":\"pause\"}");
        vesper.await("the world paused", m -> isState(m) && m.has("paused"));
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        JSONObject before = vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.abort();
        dominion.close();
        dominion = raise("-p", "0", "--resume", "crypt", "--db", db);

        TestSocket someone = join("Someone");
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
    void eachChapterBeginsWithAnAutosaveThatResumes() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        int playerId = vesper.awaitType("welcome").getInt("playerId");
        vesper.awaitState(m -> true);

        dominion.server().runOnLoop(() -> dominion.story().jumpTo(2));
        vesper.awaitNotice("Autosaved: chapter 2, The Hall.");
        vesper.abort();
        dominion.close();

        dominion = raise("-p", "0", "--resume", "autosave", "--db", db);
        TestSocket back = join("Anyone");
        JSONObject welcome = back.awaitType("welcome");
        assertEquals("Vesper", welcome.getString("name"));
        assertEquals(playerId, welcome.getInt("playerId"));
        assertEquals(2, back.awaitState(m -> m.has("story")).getJSONObject("story").getInt("chapter"));
    }

    @Test
    void theAutosaveNameIsReservedForTheAutosave() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"autosave\"}");
        vesper.awaitNotice("That name is kept for the autosave. Choose another");
        assertTrue(dominion.saves().list().isEmpty());
    }

    @Test
    void thereIsNoAutosaveWithTwoPlayers() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        TestSocket corvin = join("Corvin");
        corvin.awaitType("welcome");
        vesper.awaitState(m -> true);

        dominion.server().runOnLoop(() -> dominion.story().jumpTo(2));
        vesper.awaitState(m -> m.has("story") && m.getJSONObject("story").getInt("chapter") == 2);
        vesper.collect(1_000);

        assertTrue(dominion.saves().list().isEmpty(), "saving is for a solo campaign, and so is autosaving");
    }

    @Test
    void savingAFreeForAllIsRefused() throws Exception {
        dominion = raise("-p", "0", "--db", db, "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saving only works in a story campaign");
    }

    @Test
    void savingWithTwoPlayersIsRefused() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        TestSocket corvin = join("Corvin");
        corvin.await("the welcome", m -> m.getString("type").equals("welcome"));
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saving only works with one player in the world");
    }

    @Test
    void aTakenNameIsRefusedUntilSavedAgainWithForce() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        vesper.await("a summoned skeleton", m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("A save called \"crypt\" already exists. Save again with force to replace it");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\",\"force\":true}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");

        vesper.abort();
        dominion.close();
        dominion = raise("-p", "0", "--resume", "crypt", "--db", db);
        TestSocket back = join("Vesper");
        back.await("the newer save, with its skeleton",
                m -> isState(m) && m.getJSONObject("you").getInt("legion") == 1);
    }

    @Test
    void anUnsupportedSaveNameIsRefused() throws Exception {
        dominion = raise("-p", "0", "--campaign", campaign.toString(), "--db", db, "--seed", "4");
        TestSocket vesper = join("Vesper");
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
        TestSocket vesper = join("Vesper");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");
        vesper.abort();
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

    private TestSocket join(String name) {
        TestSocket client = TestSocket.join(dominion.server().port(), name);
        clients.add(client);
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
}
