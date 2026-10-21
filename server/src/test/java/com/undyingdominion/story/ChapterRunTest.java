package com.undyingdominion.story;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.story.Campaign.Chapter;
import com.undyingdominion.story.Campaign.Objective;
import com.undyingdominion.story.Campaign.Spawn;
import com.undyingdominion.story.Campaign.Trigger;
import com.undyingdominion.world.Position;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterRunTest {

    private static Campaign campaign;

    @BeforeAll
    static void load() throws IOException, URISyntaxException {
        campaign = Campaign.load(folder());
    }

    static Path folder() throws URISyntaxException {
        return Path.of(ChapterRunTest.class.getResource("/campaigns/trial/campaign.json").toURI()).getParent();
    }

    private static GameEngine engineFor(Chapter chapter) throws IOException {
        return new GameEngine(campaign.loadMap(chapter), GameRules.defaults().maxNeutrals(0), new Random(2));
    }

    @Test
    void loadsChaptersAndRegistersTheirCreatures() {
        assertEquals("A Trial Campaign", campaign.title());
        assertEquals(2, campaign.chapters().size());
        UnitType warden = UnitType.valueOf("TEST_WARDEN");
        assertTrue(warden.boss);
        assertEquals(5, warden.level);
        assertTrue(UnitType.valueOf("TEST_SAPLING").stationary);
    }

    @Test
    void placesTheChaptersCreaturesAndRaisesNecromancersInTheStartArea() throws IOException {
        Chapter yard = campaign.chapters().get(0);
        GameEngine engine = engineFor(yard);
        new ChapterRun(yard, engine, new Random(2));
        engine.submit(new Command.Join(engine.allocatePlayerId(), "Vesper"));
        engine.tick();

        assertEquals(6, engine.getUnits().stream().filter(u -> u.getOwnerId() == Unit.NEUTRAL).count());
        Position necromancer = engine.getUnit(engine.getPlayer(1).getNecromancerId()).getPosition();
        assertTrue(necromancer.x() <= 3 && necromancer.y() <= 3, "rose at " + necromancer);
    }

    @Test
    void triggersFireOnceAndTheBossWinsTheChapter() throws IOException {
        Chapter yard = campaign.chapters().get(0);
        GameEngine engine = engineFor(yard);
        ChapterRun run = new ChapterRun(yard, engine, new Random(2));

        engine.getUnits().stream().filter(u -> u.getType().name().equals("TEST_RAT")).toList()
                .forEach(u -> u.takeDamage(1000, 9));
        for (int i = 0; i < 12; i++) {
            engine.tick();
            run.update();
        }
        assertEquals(List.of("The rats are gone."), run.takeLines());
        assertEquals(1, engine.getUnits().stream().filter(u -> u.getType().name().equals("TEST_RAT")).count(),
                "the trigger brought one more rat, and only once");

        Unit warden = engine.getUnits().stream().filter(u -> u.getType().name().equals("TEST_WARDEN")).findFirst().orElseThrow();
        warden.takeDamage(150, 9);
        engine.tick();
        run.update();
        assertEquals(List.of("The warden staggers."), run.takeLines());
        assertEquals(ChapterRun.Outcome.PLAYING, run.outcome());

        warden.takeDamage(1000, 9);
        engine.tick();
        assertEquals(ChapterRun.Outcome.WON, run.update());
    }

    @Test
    void aClearedTriggerWaitsForItsCreatureToAppear() throws IOException {
        Chapter hall = campaign.chapters().get(1);
        Chapter waiting = new Chapter("w", "W", "", hall.mapFile(), hall.startArea(), 80, List.of(),
                new Campaign.Objective(Campaign.Objective.Kind.SURVIVE, "Hold", null, 0, 60), List.of(),
                List.of(new Campaign.Trigger(Campaign.Trigger.When.TYPE_CLEARED, 0, "TEST_WARDEN", 0, List.of(),
                        List.of("The warden is dead."))), List.of(), 0);
        GameEngine engine = engineFor(hall);
        ChapterRun run = new ChapterRun(waiting, engine, new Random(2));
        for (int i = 0; i < 30; i++) {
            engine.tick();
            run.update();
        }
        assertTrue(run.takeLines().isEmpty(), "no warden has appeared, so none has died");
    }

    @Test
    void stationaryCreaturesNeverMove() throws IOException {
        Chapter yard = campaign.chapters().get(0);
        GameEngine engine = engineFor(yard);
        new ChapterRun(yard, engine, new Random(2));
        Unit sapling = engine.getUnits().stream().filter(u -> u.getType().stationary).findFirst().orElseThrow();
        engine.spawn(UnitType.SKELETON_WARRIOR, 1, new Position(12, 7));

        for (int i = 0; i < 100; i++) engine.tick();

        assertEquals(new Position(15, 7), sapling.getPosition());
    }

    @Test
    void survivingLongEnoughWinsAndRunningOutOfTimeLoses() throws IOException {
        Chapter hall = campaign.chapters().get(1);
        GameEngine engine = engineFor(hall);
        ChapterRun run = new ChapterRun(hall, engine, new Random(2));
        for (int i = 0; i < 29; i++) {
            engine.tick();
            assertEquals(ChapterRun.Outcome.PLAYING, run.update());
        }
        engine.tick();
        assertEquals(ChapterRun.Outcome.WON, run.update());

        Chapter timed = new Chapter("timed", "Timed", "", hall.mapFile(), hall.startArea(), 80, List.of(),
                new Campaign.Objective(Campaign.Objective.Kind.SURVIVE, "Hold", null, 0, 60), List.of(), List.of(),
                List.of(), 1);
        GameEngine second = engineFor(hall);
        ChapterRun lost = new ChapterRun(timed, second, new Random(2));
        for (int i = 0; i < 10; i++) second.tick();
        assertEquals(ChapterRun.Outcome.LOST, lost.update());
    }

    @Test
    void aCreaturePlacedInsideAWallIsRejectedWhenLoading(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("maps"));
        Files.copy(folder().resolve("maps/yard.properties"), dir.resolve("maps/yard.properties"));
        Files.writeString(dir.resolve("campaign.json"), """
                {"title": "Broken", "chapters": [{"id": "x", "title": "X", "map": "maps/yard.properties",
                  "start": {"area": [1, 1, 2, 2]}, "objective": {"kind": "SURVIVE", "seconds": 5, "text": "t"},
                  "spawns": [{"unit": "WOLF", "at": [[9, 0]]}]}]}
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Campaign.load(dir));
        assertTrue(e.getMessage().contains("unwalkable"));
    }

    @Test
    void theLastFewCreaturesOfAClearingChapterAreShownAndNoSooner() throws IOException {
        Chapter clearing = clearing(List.of());
        GameEngine engine = engineFor(clearing);
        ChapterRun run = new ChapterRun(clearing, engine, new Random(2));
        engine.tick();
        run.update();
        List<Unit> rats = engine.getUnits().stream().filter(u -> u.getOwnerId() == Unit.NEUTRAL).toList();
        assertEquals(8, rats.size());
        assertTrue(run.stragglers().isEmpty(), "eight left: still a hunt");

        rats.subList(0, 3).forEach(u -> u.takeDamage(1000, 9));
        engine.tick();
        run.update();

        List<Position> expected = rats.subList(3, 8).stream().map(Unit::getPosition).sorted(BY_TILE).toList();
        assertEquals(expected, run.stragglers().stream().sorted(BY_TILE).toList(), "five left: shown where they are");
    }

    @Test
    void stragglersStayHiddenWhileTimedWavesAreStillToCome() throws IOException {
        Trigger wave = new Trigger(Trigger.When.AT_SECOND, 60, null, 0,
                List.of(new Spawn("TEST_RAT", 2, new int[] {11, 1, 18, 10}, List.of())), List.of());
        Chapter clearing = clearing(List.of(wave));
        GameEngine engine = engineFor(clearing);
        ChapterRun run = new ChapterRun(clearing, engine, new Random(2));
        engine.getUnits().stream().filter(u -> u.getOwnerId() == Unit.NEUTRAL).limit(4).forEach(u -> u.takeDamage(1000, 9));
        engine.tick();
        run.update();

        assertTrue(run.stragglers().isEmpty(), "four left, but a wave is still coming");
    }

    @Test
    void onlyAClearingChapterShowsStragglers() throws IOException {
        Chapter yard = campaign.chapters().get(0);
        GameEngine engine = engineFor(yard);
        ChapterRun run = new ChapterRun(yard, engine, new Random(2));
        engine.tick();
        run.update();

        assertTrue(engine.getUnits().stream().filter(u -> u.getOwnerId() == Unit.NEUTRAL).count() <= 6);
        assertTrue(run.stragglers().isEmpty(), "a boss chapter is about the boss, not the stragglers");
    }

    private static final java.util.Comparator<Position> BY_TILE =
            java.util.Comparator.comparingInt(Position::y).thenComparingInt(Position::x);

    /** The yard's map with eight rats to clear, and whatever triggers the test needs. */
    private static Chapter clearing(List<Trigger> triggers) {
        Chapter yard = campaign.chapters().get(0);
        return new Chapter("clearing", "The Clearing", "A test", yard.mapFile(), yard.startArea(), 90, List.of(),
                new Objective(Objective.Kind.CLEAR_ALL, "Clear the yard", null, 0, 0),
                List.of(new Spawn("TEST_RAT", 8, new int[] {11, 1, 18, 10}, List.of())), triggers, List.of(), 0);
    }
}
