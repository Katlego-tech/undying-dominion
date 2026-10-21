package com.undyingdominion.story;

import com.undyingdominion.game.EngineState;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.net.GameServer;
import com.undyingdominion.story.Campaign.Chapter;
import com.undyingdominion.world.Position;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Plays a campaign on a server: one chapter at a time, telling the story as it goes. Briefs players as
 * they arrive, passes on what the chapter's triggers say, and when a chapter is won, moves everyone
 * on to the next map. A chapter that is lost starts again.
 */
public class StoryDirector {

    private static final Logger log = LoggerFactory.getLogger(StoryDirector.class);
    private int secondsBetweenChapters = 10;
    private static final int SECONDS_BEFORE_RETRY = 6;

    private final Campaign campaign;
    private final Random random;
    private GameServer server;

    private volatile int index;
    private ChapterRun run;
    private long moveOnAtTick = -1;
    private int moveOnTo;
    /** The engine rebuilt from a save, handed out by {@link #firstEngine()} instead of a fresh one. */
    private GameEngine resumed;

    private final List<IntConsumer> chapterListeners = new CopyOnWriteArrayList<>();

    public StoryDirector(Campaign campaign, Random random) {
        this.campaign = campaign;
        this.random = random;
    }

    /** A campaign carrying on from a save, in the chapter it was saved in. */
    public static StoryDirector resume(Campaign campaign, Random random, StoryState story, EngineState engine) {
        StoryDirector director = new StoryDirector(campaign, random);
        Chapter chapter = campaign.chapters().get(story.chapterIndex());
        try {
            director.resumed = GameEngine.rebuild(campaign.loadMap(chapter), rulesFor(chapter), random, engine);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        director.index = story.chapterIndex();
        director.moveOnAtTick = story.moveOnAtTick();
        director.moveOnTo = story.moveOnTo();
        director.run = ChapterRun.rebuild(chapter, director.resumed, random, story.run());
        return director;
    }

    /** Where the campaign has got to. Loop thread only, once attached. */
    public StoryState capture() {
        return new StoryState(index, moveOnAtTick, moveOnTo, run.capture());
    }

    /** The engine for the first chapter, before any server exists; or, after {@link #resume}, the rebuilt one. */
    public GameEngine firstEngine() {
        if (resumed != null) return resumed;
        GameEngine engine = engineFor(0);
        run = new ChapterRun(campaign.chapters().get(0), engine, random);
        return engine;
    }

    /** Starts directing once the server is up. */
    public void attach(GameServer server) {
        this.server = server;
        server.addTickListener(engine -> update());
        server.addJoinListener(playerId -> server.sendTo(playerId, message("briefing", current().briefing())));
        publishStatus();
        log.info("Campaign \"{}\" begins with chapter 1: {}", campaign.title(), current().title());
    }

    public Campaign campaign() { return campaign; }

    /** The generator shared with every chapter's engine, so a save can carry its exact state. */
    public Random random() { return random; }

    /** How long the victory screen stays up before the next chapter. Ten seconds unless changed. */
    public StoryDirector secondsBetweenChapters(int seconds) {
        this.secondsBetweenChapters = seconds;
        return this;
    }

    public Chapter current() {
        return campaign.chapters().get(index);
    }

    public int chapterNumber() {
        return index + 1;
    }

    // ------------------------------------------------------------------ game loop thread

    private void update() {
        GameEngine engine = server.engine();
        if (moveOnAtTick >= 0 && engine.getTick() >= moveOnAtTick) {
            begin(moveOnTo);
            return;
        }

        ChapterRun.Outcome before = run.outcome();
        ChapterRun.Outcome now = run.update();
        List<String> lines = run.takeLines();
        if (!lines.isEmpty()) server.broadcast(message("say", lines));

        if (before == ChapterRun.Outcome.PLAYING && now == ChapterRun.Outcome.WON) {
            boolean last = index == campaign.chapters().size() - 1;
            log.info("Chapter {} won: {}", chapterNumber(), current().title());
            server.broadcast(message(last ? "finale" : "victory", current().victory()));
            if (!last) {
                moveOnTo = index + 1;
                moveOnAtTick = engine.getTick() + (long) secondsBetweenChapters * engine.getRules().ticksPerSecond();
            }
        } else if (before == ChapterRun.Outcome.PLAYING && now == ChapterRun.Outcome.LOST) {
            log.info("Chapter {} lost; starting it again", chapterNumber());
            server.broadcast(message("defeat", List.of("Time has run out. The chapter begins again.")));
            moveOnTo = index;
            moveOnAtTick = engine.getTick() + (long) SECONDS_BEFORE_RETRY * engine.getRules().ticksPerSecond();
        }
        publishStatus();
    }

    private void begin(int chapterIndex) {
        moveOnAtTick = -1;
        GameEngine next = engineFor(chapterIndex);
        index = chapterIndex;
        run = new ChapterRun(current(), next, random);
        server.replaceEngine(next);
        server.broadcast(message("briefing", current().briefing()));
        publishStatus();
        log.info("Chapter {} begins: {}", chapterNumber(), current().title());
        for (IntConsumer listener : chapterListeners) listener.accept(chapterNumber());
    }

    /**
     * Calls {@code listener} with the chapter's number each time a chapter begins after the first:
     * one won and moved on from, one lost and started again, or one the host jumped to. Game loop
     * thread. The players' joins into the new world are queued, not applied: they land with the next
     * tick or admit, which is why the autosave waits for {@code runAfterStep}.
     */
    public void onChapterBegins(IntConsumer listener) {
        chapterListeners.add(listener);
    }

    private GameEngine engineFor(int chapterIndex) {
        Chapter chapter = campaign.chapters().get(chapterIndex);
        try {
            return new GameEngine(campaign.loadMap(chapter), rulesFor(chapter), random);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GameRules rulesFor(Chapter chapter) {
        return GameRules.defaults().maxNeutrals(0).startingSoul(chapter.startingSoul());
    }

    private void publishStatus() {
        server.setStory(new JSONObject()
                .put("campaign", campaign.title())
                .put("chapter", chapterNumber())
                .put("chapters", campaign.chapters().size())
                .put("title", current().title())
                .put("source", current().source())
                .put("objective", current().objective().text())
                .put("progress", run.progress())
                .put("outcome", run.outcome().name())
                .put("stragglers", stragglers()));
    }

    /** The last few creatures of a clearing chapter, as {@code [[x, y], ...]}, for the minimap. */
    private JSONArray stragglers() {
        JSONArray tiles = new JSONArray();
        for (Position p : run.stragglers()) tiles.put(new JSONArray().put(p.x()).put(p.y()));
        return tiles;
    }

    private String message(String kind, List<String> lines) {
        Chapter chapter = current();
        return new JSONObject()
                .put("type", "story")
                .put("kind", kind)
                .put("campaign", campaign.title())
                .put("chapter", chapterNumber())
                .put("chapters", campaign.chapters().size())
                .put("title", chapter.title())
                .put("source", chapter.source())
                .put("objective", chapter.objective().text())
                .put("lines", new JSONArray(lines))
                .toString();
    }

    /** Jumps straight to a chapter, counting from 1. Must run on the game loop thread. */
    public void jumpTo(int chapterNumber) {
        if (chapterNumber < 1 || chapterNumber > campaign.chapters().size()) {
            throw new IllegalArgumentException("There are chapters 1 to " + campaign.chapters().size());
        }
        begin(chapterNumber - 1);
    }

    /** A one-line summary for the server dashboard. */
    public String headline() {
        ChapterRun current = run;
        return "Chapter " + chapterNumber() + "/" + campaign.chapters().size() + " · " + current().title()
                + " · " + current().objective().text() + " · " + (current == null ? "" : current.progress());
    }
}
