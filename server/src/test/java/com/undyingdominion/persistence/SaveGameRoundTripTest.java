package com.undyingdominion.persistence;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.EngineState;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.PlayerState;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.Campaign.Chapter;
import com.undyingdominion.story.ChapterRun;
import com.undyingdominion.story.ChapterRunState;
import com.undyingdominion.story.StoryDirector;
import com.undyingdominion.story.StoryState;
import com.undyingdominion.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The invariants of docs/design/persistence.md §3: what is captured comes back exactly, and plays on identically. */
class SaveGameRoundTripTest {

    private Campaign campaign;
    private Chapter yard;
    private Random random;
    private GameEngine engine;
    private ChapterRun run;

    /** A chapter a few seconds in: a player with a legion on the march, creatures placed, corpses likely. */
    @BeforeEach
    void playTheYardForAWhile() throws Exception {
        campaign = Campaign.load(Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent());
        yard = campaign.chapters().get(0);
        random = new Random(4);
        engine = new GameEngine(campaign.loadMap(yard), rules(yard), random);
        run = new ChapterRun(yard, engine, random);

        int playerId = engine.allocatePlayerId();
        engine.submit(new Command.Join(playerId, "Vesper"));
        play(1);
        for (int i = 0; i < 3; i++) engine.submit(new Command.Summon(playerId, UnitType.SKELETON_WARRIOR));
        play(1);
        List<Integer> legion = engine.getUnits().stream()
                .filter(u -> u.getOwnerId() == playerId).map(u -> u.getId()).toList();
        engine.submit(new Command.Move(playerId, legion, new Position(14, 5), true));
        play(60);
    }

    @Test
    void captureRebuildCaptureIsEqual() {
        EngineState captured = engine.capture();
        GameEngine rebuilt = GameEngine.rebuild(engine.getMap(), engine.getRules(), new Random(), captured);
        assertEquals(captured, rebuilt.capture());
    }

    @Test
    void aRebuiltEnginePlaysOnExactlyLikeTheOriginal() {
        EngineState captured = engine.capture();
        Random copy = RandomState.restore(RandomState.of(random));
        GameEngine rebuilt = GameEngine.rebuild(engine.getMap(), engine.getRules(), copy, captured);
        ChapterRun rebuiltRun = ChapterRun.rebuild(yard, rebuilt, copy, run.capture());

        for (int i = 0; i < 200; i++) {
            engine.tick();
            run.update();
            rebuilt.tick();
            rebuiltRun.update();
        }
        assertNotEquals(captured.tick(), engine.capture().tick(), "the world moved on");
        assertEquals(engine.capture(), rebuilt.capture());
        assertEquals(run.capture(), rebuiltRun.capture());
    }

    @Test
    void aRebuiltChapterPlacesNoCreaturesAgain() {
        int before = engine.getUnits().size();
        GameEngine rebuilt = GameEngine.rebuild(engine.getMap(), engine.getRules(), new Random(), engine.capture());
        ChapterRun.rebuild(yard, rebuilt, new Random(), run.capture());
        assertEquals(before, rebuilt.getUnits().size());
    }

    @Test
    void soulIsRestoredAfterTheLevelThatCapsIt() {
        PlayerState strong = new PlayerState(7, "Vesper", "#a78bfa", null, -1, 180, 1500, 5, 9, 2);
        EngineState captured = engine.capture();
        EngineState withStrong = new EngineState(captured.tick(), 8, captured.nextUnitId(), captured.nextCorpseId(),
                captured.nextTrapId(), captured.startArea(), List.of(strong), List.of(), List.of(), List.of());

        Player restored = GameEngine.rebuild(engine.getMap(), engine.getRules(), new Random(), withStrong).getPlayer(7);
        assertEquals(5, restored.getLevel());
        assertEquals(180, restored.getSoul(), "a level-1 cap of 100 must not clip it");
    }

    @Test
    void aStoryDirectorResumesFromItsCapture() {
        StoryDirector director = new StoryDirector(campaign, random);
        director.firstEngine();
        StoryState story = director.capture();

        StoryDirector resumed = StoryDirector.resume(campaign, new Random(), story, engine.capture());
        assertEquals(story, resumed.capture());
        assertEquals(engine.capture(), resumed.firstEngine().capture());
    }

    @Test
    void theJsonFormKeepsEveryField() {
        ChapterRunState chapter = run.capture();
        SaveGame save = new SaveGame(SaveGame.FORMAT, "crypt", Instant.parse("2026-10-18T09:12:44Z"),
                "/somewhere/trial", campaign.title(), "9f2c", RandomState.of(random),
                new StoryState(0, -1, 0, chapter), engine.capture());

        assertEquals(save, SaveGameCodec.fromJson(SaveGameCodec.toJson(save)));
    }

    @Test
    void aSerialisedRandomCarriesOnFromTheSamePoint() {
        Random copy = RandomState.restore(RandomState.of(random));
        for (int i = 0; i < 10; i++) assertEquals(random.nextLong(), copy.nextLong());
    }

    private void play(int ticks) {
        for (int i = 0; i < ticks; i++) {
            engine.tick();
            run.update();
        }
    }

    private static GameRules rules(Chapter chapter) {
        return GameRules.defaults().maxNeutrals(0).startingSoul(chapter.startingSoul());
    }
}
