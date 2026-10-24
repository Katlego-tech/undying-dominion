package com.undyingdominion;

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
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Every mode on one server (docs/design/modes.md): a shared free-for-all and a private campaign world each. */
class HubAcceptanceTest {

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
    void aFreeForAllPlayerAndACampaignPlayerEachGetTheirOwnWorld() throws Exception {
        dominion = hub();
        TestSocket corvin = join(new JSONObject().put("command", "join").put("name", "Corvin"));
        TestSocket lin = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));

        JSONObject ffa = corvin.awaitType("welcome");
        JSONObject story = lin.awaitType("welcome");
        assertEquals(19, ffa.getJSONArray("roster").length(), "the free-for-all offers the commanders");
        assertEquals("NECROMANCER", story.getJSONObject("commander").getString("id"), "the campaign is the necromancer's");
        assertEquals(20, story.getJSONObject("map").getInt("width"), "the trial campaign's first chapter");
        assertEquals("briefing", lin.awaitType("story").getString("kind"));
        await("a campaign world running", () -> dominion.hub().campaignWorlds().size() == 1);
        assertEquals(1, dominion.server().joinedCount(), "the free-for-all holds only Corvin");
    }

    @Test
    void leavingACampaignAutosavesItForThePlayerAndClosesTheWorld() throws Exception {
        dominion = hub();
        TestSocket lin = join(new JSONObject().put("command", "join").put("name", "Lin Mo").put("mode", "campaign"));
        lin.awaitState(m -> true);
        await("the world running", () -> dominion.hub().campaignWorlds().size() == 1);
        lin.abort();

        await("the world closed", () -> dominion.hub().campaignWorlds().isEmpty());
        JSONArray saves = campaignMode().getJSONArray("saves");
        assertEquals("Lin-Mo-auto", saves.getJSONObject(0).getString("name"));
        assertEquals("The Yard", saves.getJSONObject(0).getString("chapterTitle"));
    }

    @Test
    void leaveToTitleAutosavesTheCampaignAndClosesItsWorldWithoutWaitingOutTheGrace() throws Exception {
        dominion = hub("--grace", "3600");
        TestSocket lin = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        lin.awaitState(m -> true);
        await("the world running", () -> dominion.hub().campaignWorlds().size() == 1);

        lin.send(new JSONObject().put("command", "leave"));

        assertEquals("Left the Dominion", lin.awaitClose());
        await("the world closed", () -> dominion.hub().campaignWorlds().isEmpty());
        assertEquals("Lin-auto", campaignMode().getJSONArray("saves").getJSONObject(0).getString("name"));
    }

    @Test
    void aSaveCanBeContinuedButNotByTwoPlayersAtOnce() throws Exception {
        dominion = hub();
        TestSocket first = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        first.awaitState(m -> true);
        first.abort();
        await("the autosave", () -> savesListed() == 1);

        TestSocket back = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign").put("save", "Lin-auto"));
        assertEquals(20, back.awaitType("welcome").getJSONObject("map").getInt("width"));
        back.awaitNotice("Resumed \"Lin-auto\": chapter 1, The Yard. You are Lin. Press P to carry on.");
        TestSocket twin = join(new JSONObject().put("command", "join").put("name", "Ilse").put("mode", "campaign").put("save", "Lin-auto"));
        assertEquals("\"Lin-auto\" is being played right now.", twin.awaitType("error").getString("message"));
    }

    @Test
    void theCampaignWorldsAreCappedAndMatchesAreNotOpenYet() throws Exception {
        dominion = hub("--max-campaigns", "1");
        join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign")).awaitType("welcome");
        TestSocket second = join(new JSONObject().put("command", "join").put("name", "Ilse").put("mode", "campaign"));
        assertEquals("All the campaign worlds are taken. Try again soon.", second.awaitType("error").getString("message"));
        second.send(new JSONObject().put("command", "join").put("name", "Ilse").put("mode", "match"));
        assertEquals("Matches are coming", second.awaitType("error").getString("message"));

        JSONArray modes = modes();
        assertEquals(List.of("free-for-all", "campaign", "match"),
                List.of(modes.getJSONObject(0).getString("id"), modes.getJSONObject(1).getString("id"), modes.getJSONObject(2).getString("id")));
        assertTrue(modes.getJSONObject(1).getBoolean("open"));
        assertFalse(modes.getJSONObject(2).getBoolean("open"));
    }

    @Test
    void aRefusedNameBuildsNoWorldAndSameNamedPlayersKeepTheirOwnAutosaves() throws Exception {
        dominion = hub();
        TestSocket blank = join(new JSONObject().put("command", "join").put("name", "   ").put("mode", "campaign"));
        blank.awaitType("error");
        assertTrue(dominion.hub().campaignWorlds().isEmpty(), "no world is left behind a refused join");

        TestSocket one = join(new JSONObject().put("command", "join").put("name", "Alex").put("mode", "campaign"));
        TestSocket two = join(new JSONObject().put("command", "join").put("name", "Alex").put("mode", "campaign"));
        one.awaitState(m -> true);
        two.awaitState(m -> true);
        one.abort();
        two.abort();
        await("both autosaves", () -> savesListed() == 2);
        JSONArray saves = campaignMode().getJSONArray("saves");
        assertEquals(java.util.Set.of("Alex-auto", "Alex-2-auto"),
                java.util.Set.of(saves.getJSONObject(0).getString("name"), saves.getJSONObject(1).getString("name")));
    }

    @Test
    void theBeaconOffersEveryModeAndCountsEveryoneOnTheServer() throws Exception {
        dominion = hub();
        TestSocket corvin = join(new JSONObject().put("command", "join").put("name", "Corvin"));
        TestSocket lin = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        corvin.awaitType("welcome");
        lin.awaitType("welcome");
        await("both players counted", () -> beacon().getInt("players") == 2);

        JSONObject beacon = beacon();
        assertEquals(List.of("free-for-all", "campaign"), beacon.getJSONArray("modes").toList());
        assertEquals(8 + 4, beacon.getInt("max"), "the free-for-all's seats and one per campaign world");
    }

    @Test
    void withoutACampaignTheBeaconOffersOnlyTheFreeForAll() throws Exception {
        dominion = raise("-p", "0", "--no-wild", "--no-beacon", "--db", dir.resolve("s.db").toString(),
                "--campaign-folder", dir.resolve("nothing-here").toString());
        JSONObject beacon = beacon();
        assertEquals(List.of("free-for-all"), beacon.getJSONArray("modes").toList());
        assertEquals(8, beacon.getInt("max"));
    }

    @Test
    void aSingleModeServersBeaconNamesItsOneMode() throws Exception {
        dominion = raise("-p", "0", "--no-beacon", "--db", dir.resolve("s.db").toString(), "--campaign", trialCampaign().toString());
        JSONObject beacon = beacon();
        assertEquals(List.of("campaign"), beacon.getJSONArray("modes").toList());
        assertEquals(8, beacon.getInt("max"));
    }

    @Test
    void withoutACampaignTheHubSaysSo() throws Exception {
        dominion = raise("-p", "0", "--no-wild", "--no-beacon", "--grace", "0", "--db", dir.resolve("s.db").toString(),
                "--campaign-folder", dir.resolve("nothing-here").toString());
        TestSocket lin = join(new JSONObject().put("command", "join").put("name", "Lin").put("mode", "campaign"));
        assertEquals("No campaign on this server", lin.awaitType("error").getString("message"));
        assertEquals("No campaign on this server", campaignMode().getString("reason"));
    }

    @Test
    void anOldFlagStillGivesASingleModeServer() throws Exception {
        dominion = raise("-p", "0", "--no-beacon", "--db", dir.resolve("s.db").toString(), "--campaign", trialCampaign().toString());
        JSONArray modes = modes();
        assertEquals(1, modes.length());
        assertEquals("campaign", modes.getJSONObject(0).getString("id"));
    }

    // --- helpers ---

    private Dominion hub(String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("-p", "0", "--no-wild", "--no-beacon", "--grace", "0",
                "--db", dir.resolve("saves.db").toString(), "--campaign-folder", trialCampaign().toString()));
        args.addAll(List.of(extra));
        return raise(args.toArray(String[]::new));
    }

    private Dominion raise(String... args) {
        try {
            return Dominion.raise(ServerOptions.parse(args));
        } catch (IllegalArgumentException | IOException e) {
            return fail("the server would not start: " + e.getMessage());
        }
    }

    private TestSocket join(JSONObject message) {
        TestSocket socket = TestSocket.open(dominion.server().port());
        sockets.add(socket);
        socket.send(message);
        return socket;
    }

    private JSONObject beacon() {
        return new JSONObject(dominion.beaconPayload("host"));
    }

    private JSONArray modes() throws Exception {
        String body = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + dominion.server().port() + "/modes")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        return new JSONObject(body).getJSONArray("modes");
    }

    private JSONObject campaignMode() throws Exception {
        JSONArray modes = modes();
        for (int i = 0; i < modes.length(); i++) {
            if (modes.getJSONObject(i).getString("id").equals("campaign")) return modes.getJSONObject(i);
        }
        return fail("no campaign entry");
    }

    private Path trialCampaign() throws Exception {
        Path source = Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent();
        Path campaign = dir.resolve("trial");
        if (Files.exists(campaign)) return campaign;
        Files.createDirectories(campaign.resolve("maps"));
        for (String file : List.of("campaign.json", "maps/yard.properties", "maps/hall.properties")) {
            Files.copy(source.resolve(file), campaign.resolve(file));
        }
        return campaign;
    }

    private static void await(String what, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("waited for " + what);
            Thread.sleep(25);
        }
    }

    private int savesListed() {
        try {
            return campaignMode().getJSONArray("saves").length();
        } catch (Exception e) {
            return -1;
        }
    }
}
