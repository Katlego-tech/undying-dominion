package com.undyingdominion.game;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.TestSocket;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Playing as a commander from the PvP roster, over real WebSockets through the real startup path.
 * One test per acceptance row of docs/design/characters.md §10 (SPEC US6), using only the wire.
 * The match-lobby row waits for matches (T030), which test the pick there.
 */
class CommanderAcceptanceTest {

    @TempDir
    Path dir;

    private Dominion dominion;
    private final List<TestSocket> sockets = new ArrayList<>();

    @AfterEach
    void stop() {
        sockets.forEach(TestSocket::abort);
        if (dominion != null) dominion.close();
    }

    @Test
    void joiningTheFreeForAllFieldsTheChosenCommander() {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket corvin = joinAs("Corvin", "BULWARK");

        JSONObject welcome = corvin.awaitType("welcome");
        assertEquals("BULWARK", welcome.getJSONObject("commander").getString("id"));
        JSONArray roster = welcome.getJSONArray("roster");
        assertEquals(19, roster.length(), "the 19 PvP commanders");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < roster.length(); i++) ids.add(roster.getJSONObject(i).getString("id"));
        assertEquals(19, ids.size(), "no commander listed twice");
        assertFalse(ids.contains("NECROMANCER"), "the necromancer is campaign-only");

        JSONObject state = corvin.awaitState(m -> commanderOf(m) != null);
        JSONObject you = state.getJSONObject("you");
        assertEquals("BULWARK", you.getString("commander"));
        assertEquals("Valor", you.getString("energy"));
        assertEquals("BULWARK", commanderOf(state).getString("type"));
        JSONObject cooldowns = you.getJSONObject("cooldowns");
        for (String ability : List.of("shield-bash", "stand-in-front", "iron-stance")) {
            assertEquals(0.0, cooldowns.getDouble(ability), ability + " starts ready");
        }
    }

    @Test
    void joiningWithoutACommanderGivesARandomOneAndSaysWhich() {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket vesper = join("Vesper");

        JSONObject welcome = vesper.awaitType("welcome");
        JSONObject commander = welcome.getJSONObject("commander");
        assertFalse(commander.getBoolean("campaignOnly"));
        vesper.awaitNotice("You command " + commander.getString("name") + ", " + commander.getString("title")
                + ". Pick another when you fall.");
    }

    @Test
    void theNecromancerWalksOnlyInTheCampaign() throws Exception {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket vesper = joinAs("Vesper", "NECROMANCER");
        assertEquals("The Necromancer walks only in the campaign", vesper.awaitType("error").getString("message"));
        vesper.send(join("Vesper", "SHADE"));
        assertEquals("SHADE", vesper.awaitType("welcome").getJSONObject("commander").getString("id"),
                "the socket stays open for another try");
        dominion.close();

        dominion = raise("-p", "0", "--campaign", trialCampaign().toString(), "--seed", "4");
        TestSocket hero = joinAs("Hero", "SHADE");
        JSONObject welcome = hero.awaitType("welcome");
        assertEquals("NECROMANCER", welcome.getJSONObject("commander").getString("id"));
        assertEquals(0, welcome.getJSONArray("roster").length(), "a campaign offers no roster");
        hero.send("{\"command\":\"pick\",\"commander\":\"SHADE\"}");
        assertEquals("The campaign is played as the Necromancer", hero.awaitType("error").getString("message"));
    }

    @Test
    void anUnknownPickIsRefusedAndAPickWhileFieldedWaitsForTheNextRise() {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket vesper = joinAs("Vesper", "BULWARK");
        vesper.awaitType("welcome");

        vesper.send("{\"command\":\"pick\",\"commander\":\"WIZARD\"}");
        assertEquals("No commander called WIZARD", vesper.awaitType("error").getString("message"));

        vesper.send("{\"command\":\"pick\",\"commander\":\"SEER\"}");
        JSONObject state = vesper.awaitNotice("You will rise as Pell, the Seer.");
        assertEquals("SEER", state.getJSONObject("you").getString("next"));
        assertEquals("BULWARK", state.getJSONObject("you").getString("commander"), "still the Bulwark until a fall");
    }

    @Test
    void aCastLandsSpendsItsCostAndStartsItsCooldown() throws IOException {
        dominion = raise("-p", "0", "-m", smallMap(6, 3), "--seed", "4", "--no-wild");
        TestSocket morrow = joinAs("Morrow", "HEXER");
        TestSocket corvin = joinAs("Corvin", "BULWARK");
        corvin.awaitType("welcome");
        int target = corvin.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        JSONObject before = morrow.awaitState(m -> commanderOf(m) != null && unit(m, target) != null);

        morrow.send(cast("hexfire").put("target", target));

        JSONObject after = morrow.awaitState(m -> hasCast(m, "hexfire"));
        JSONObject event = castEvent(after, "hexfire");
        assertEquals(target, event.getInt("b"));
        double cooldown = after.getJSONObject("you").getJSONObject("cooldowns").getDouble("hexfire");
        assertTrue(cooldown > 5 && cooldown <= 6, "Hexfire cools down for 6 s, got " + cooldown);
        assertTrue(after.getJSONObject("you").getInt("soul") <= before.getJSONObject("you").getInt("soul") - 10,
                "12 mana spent, less at most one second's regeneration");
        corvin.awaitState(m -> hasStatus(commanderOf(m), "exposed"));
    }

    @Test
    void aCastOnCooldownOrWithoutEnergyIsRefusedAndCostsNothing() throws IOException {
        dominion = raise("-p", "0", "-m", smallMap(6, 3), "--seed", "4", "--no-wild");
        TestSocket morrow = joinAs("Morrow", "HEXER");
        TestSocket corvin = joinAs("Corvin", "BULWARK");
        corvin.awaitType("welcome");
        int target = corvin.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        morrow.awaitState(m -> unit(m, target) != null);

        morrow.send(cast("hexfire").put("target", target));
        morrow.send(cast("hexfire").put("target", target));
        awaitNotice(morrow, "Hexfire is ready in \\d+s\\.");

        morrow.send("{\"command\":\"summon\",\"unit\":\"CURSED_ARCHER\"}");
        morrow.send("{\"command\":\"summon\",\"unit\":\"CURSED_ARCHER\"}");
        morrow.awaitState(m -> m.getJSONObject("you").getInt("legion") == 2);
        morrow.send(cast("summoning-seal").put("target", target));
        JSONObject refused = morrow.awaitNotice("Not enough Mana for Summoning Seal.");
        assertEquals(0.0, refused.getJSONObject("you").getJSONObject("cooldowns").getDouble("summoning-seal"),
                "a refused cast doesn't start the cooldown");
    }

    @Test
    void aCastOutOfRangeIsRefused() {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket morrow = joinAs("Morrow", "HEXER");
        TestSocket corvin = joinAs("Corvin", "BULWARK");
        corvin.awaitType("welcome");
        int target = corvin.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        morrow.awaitState(m -> commanderOf(m) != null);

        morrow.send(cast("hexfire").put("target", target));
        morrow.awaitNotice("Too far for Hexfire. Move within 5 tiles.");
    }

    @Test
    void aSealedCommanderCanNeitherCastNorCall() throws IOException {
        dominion = raise("-p", "0", "-m", smallMap(6, 3), "--seed", "4", "--no-wild");
        TestSocket morrow = joinAs("Morrow", "HEXER");
        TestSocket ilsa = joinAs("Ilsa", "HEXER");
        ilsa.awaitType("welcome");
        int ilsaCommander = ilsa.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        int morrowCommander = morrow.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");

        morrow.send(cast("summoning-seal").put("target", ilsaCommander));
        ilsa.awaitState(m -> hasStatus(commanderOf(m), "silenced") && hasStatus(commanderOf(m), "sealed"));
        ilsa.send(cast("hexfire").put("target", morrowCommander));
        ilsa.awaitNotice("You are silenced.");
        ilsa.send("{\"command\":\"summon\",\"unit\":\"HEXBOUND_THRALL\"}");
        ilsa.awaitNotice("You are sealed and cannot call.");
    }

    @Test
    void onlyTheCommandersRetinueAnswers() {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        TestSocket corvin = joinAs("Corvin", "BULWARK");
        corvin.awaitState(m -> commanderOf(m) != null);

        corvin.send("{\"command\":\"summon\",\"unit\":\"SHIELDBEARER\"}");
        corvin.awaitState(m -> m.getJSONObject("you").getInt("legion") == 1);
        corvin.send("{\"command\":\"summon\",\"unit\":\"SKELETON_WARRIOR\"}");
        corvin.awaitNotice("Skeleton Warrior doesn't answer to Dunstan.");
    }

    @Test
    void stealthHidesTheShadeFromRivalsButNotFromTheDawnRider() throws IOException {
        dominion = raise("-p", "0", "-m", smallMap(14, 3), "--seed", "4", "--no-wild");
        TestSocket corvin = joinAs("Corvin", "BULWARK");
        TestSocket vey = joinAs("Vey", "SHADE");
        TestSocket aurelle = joinAs("Aurelle", "DAWN_RIDER");
        // Out of each other's aggro, so nobody starts a fight: Corvin sees Vey 5 tiles off, and
        // Aurelle is 4 tiles from Vey, inside Light of Dawn.
        int corvinId = walkCommanderTo(corvin, 0, 1);
        int veyId = walkCommanderTo(vey, 5, 1);
        walkCommanderTo(aurelle, 9, 1);
        assertNotNull(awaitLong(corvin, "Corvin to see Vey", m -> unit(m, veyId) != null, 5));

        vey.send(cast("stealth"));

        vey.awaitState(m -> hasStatus(unit(m, veyId), "stealthed"));
        corvin.awaitState(m -> unit(m, corvinId) != null && unit(m, veyId) == null);
        aurelle.awaitState(m -> unit(m, veyId) != null);
    }

    @Test
    void aFallenCommanderCanRiseAsAnother() throws IOException {
        dominion = raise("-p", "0", "-m", smallMap(6, 3), "--seed", "4", "--no-wild");
        TestSocket pell = joinAs("Pell", "SEER");
        TestSocket garrick = joinAs("Garrick", "BERSERKER");
        pell.awaitType("welcome");
        int victim = pell.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        int attacker = garrick.awaitState(m -> commanderOf(m) != null && unit(m, victim) != null)
                .getJSONObject("you").getInt("necromancerId");

        garrick.send(new JSONObject().put("command", "attack").put("units", new JSONArray().put(attacker))
                .put("target", victim));
        garrick.send(cast("berserk"));
        awaitLong(pell, "the Seer to fall", m -> m.getJSONObject("you").isNull("necromancerId"), 40);

        pell.send("{\"command\":\"pick\",\"commander\":\"SHADE\"}");
        pell.awaitNotice("You rise as Vey, the Shade.");
        JSONObject risen = awaitLong(pell, "the rise", m -> commanderOf(m) != null, 15);
        assertEquals("SHADE", risen.getJSONObject("you").getString("commander"));
        assertEquals("SHADE", commanderOf(risen).getString("type"));
        JSONObject cooldowns = risen.getJSONObject("you").getJSONObject("cooldowns");
        for (String ability : cooldowns.keySet()) {
            assertEquals(0.0, cooldowns.getDouble(ability), ability + " is ready after a rise");
        }
    }

    @Test
    void theRosterIsServedBeforeJoiningAndIsEmptyInACampaign() throws Exception {
        dominion = raise("-p", "0", "--seed", "4", "--no-wild");
        JSONObject free = getJson("/roster");
        JSONArray roster = free.getJSONArray("roster");
        assertEquals(19, roster.length(), "the join form shows the 19 before there is a socket");
        JSONObject types = free.getJSONObject("unitTypes");
        for (int i = 0; i < roster.length(); i++) {
            JSONArray retinue = roster.getJSONObject(i).getJSONArray("retinue");
            for (int j = 0; j < retinue.length(); j++) {
                String type = retinue.getJSONObject(j).getString("name");
                assertTrue(types.has(type), type + " is described for the kit card");
            }
        }
        dominion.close();

        dominion = raise("-p", "0", "--campaign", trialCampaign().toString(), "--seed", "4");
        JSONObject campaign = getJson("/roster");
        assertEquals(0, campaign.getJSONArray("roster").length());
        assertTrue(campaign.getJSONObject("unitTypes").isEmpty(), "a campaign's form stays the plain name box");
    }

    // --- helpers ---

    private JSONObject getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + dominion.server().port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return new JSONObject(response.body());
    }

    private Dominion raise(String... args) {
        try {
            return Dominion.raise(ServerOptions.parse(args));
        } catch (IllegalArgumentException | IOException e) {
            return fail("the server would not start: " + e.getMessage());
        }
    }

    /** An open map with no obstacles, small enough that every commander spawns within reach. */
    private String smallMap(int width, int height) throws IOException {
        Path map = dir.resolve("open-" + width + "x" + height + ".properties");
        Files.writeString(map, "map.width=" + width + "\nmap.height=" + height + "\n");
        return map.toString();
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

    private TestSocket joinAs(String name, String commander) {
        TestSocket socket = TestSocket.open(dominion.server().port());
        sockets.add(socket);
        socket.send(join(name, commander));
        return socket;
    }

    private static JSONObject join(String name, String commander) {
        return new JSONObject().put("command", "join").put("name", name).put("commander", commander);
    }

    private static JSONObject cast(String ability) {
        return new JSONObject().put("command", "cast").put("ability", ability);
    }

    /** Sends the player's commander to a tile and waits for it to arrive. Returns its unit id. */
    private static int walkCommanderTo(TestSocket socket, int x, int y) {
        int id = socket.awaitState(m -> commanderOf(m) != null).getJSONObject("you").getInt("necromancerId");
        socket.send(new JSONObject().put("command", "move").put("units", new JSONArray().put(id)).put("x", x).put("y", y));
        awaitLong(socket, "the commander to reach " + x + "," + y, m -> {
            JSONObject unit = unit(m, id);
            return unit != null && unit.getInt("x") == x && unit.getInt("y") == y;
        }, 15);
        return id;
    }

    /** The player's own commander unit in this state, or null while it is down. */
    private static JSONObject commanderOf(JSONObject state) {
        JSONObject you = state.getJSONObject("you");
        return you.isNull("necromancerId") ? null : unit(state, you.getInt("necromancerId"));
    }

    private static JSONObject unit(JSONObject state, int id) {
        JSONArray units = state.getJSONArray("units");
        for (int i = 0; i < units.length(); i++) {
            if (units.getJSONObject(i).getInt("id") == id) return units.getJSONObject(i);
        }
        return null;
    }

    private static boolean hasStatus(JSONObject unit, String status) {
        if (unit == null || !unit.has("statuses")) return false;
        JSONArray statuses = unit.getJSONArray("statuses");
        for (int i = 0; i < statuses.length(); i++) {
            if (statuses.getJSONObject(i).getString("id").equals(status)) return true;
        }
        return false;
    }

    private static JSONObject castEvent(JSONObject state, String ability) {
        JSONArray events = state.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (event.getString("kind").equals("CAST") && ability.equals(event.optString("ability"))) return event;
        }
        return null;
    }

    private static boolean hasCast(JSONObject state, String ability) {
        return castEvent(state, ability) != null;
    }

    /** The state with a notice matching this pattern, for notices that carry a changing number. */
    private static JSONObject awaitNotice(TestSocket socket, String pattern) {
        return socket.await("a notice matching " + pattern, m -> TestSocket.isState(m)
                && m.getJSONObject("you").getJSONArray("notices").toList().stream()
                        .anyMatch(n -> n.toString().matches(pattern)));
    }

    /** As {@link TestSocket#awaitState}, for what takes longer than its eight seconds: a walk, a fight. */
    private static JSONObject awaitLong(TestSocket socket, String what, Predicate<JSONObject> match, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            for (JSONObject message : socket.collect(200)) {
                if (TestSocket.isState(message) && match.test(message)) return message;
            }
        }
        return fail("waited " + seconds + " seconds for " + what);
    }
}
