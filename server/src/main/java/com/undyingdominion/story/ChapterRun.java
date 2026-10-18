package com.undyingdominion.story;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameEvent;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.story.Campaign.Chapter;
import com.undyingdominion.story.Campaign.Objective;
import com.undyingdominion.story.Campaign.Spawn;
import com.undyingdominion.story.Campaign.Trigger;
import com.undyingdominion.world.Position;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One chapter being played: places its creatures, fires its triggers, and judges its objective.
 * Lives on the game loop thread, beside the engine it drives.
 */
public class ChapterRun {

    public enum Outcome { PLAYING, WON, LOST }

    private static final int START_CLEARANCE = 6;

    private final Chapter chapter;
    private final GameEngine engine;
    private final Random random;
    private final int ticksPerSecond;
    private final long startTick;

    /** Every creature the chapter brought, so their deaths can be counted. */
    private final Set<Integer> creatures = new HashSet<>();
    private final Set<Trigger> fired = new HashSet<>();
    /** Types that have been on the field this chapter, so "all of them are dead" means something. */
    private final Set<String> seenTypes = new HashSet<>();
    private final List<String> pendingLines = new ArrayList<>();
    private int kills;
    private boolean bossSeen;
    private Outcome outcome = Outcome.PLAYING;

    public ChapterRun(Chapter chapter, GameEngine engine, Random random) {
        this.chapter = chapter;
        this.engine = engine;
        this.random = random;
        this.ticksPerSecond = engine.getRules().ticksPerSecond();
        this.startTick = engine.getTick();
        int[] start = chapter.startArea();
        engine.setStartArea(start[0], start[1], start[2], start[3]);
        placeAll(chapter.spawns());
    }

    /** A chapter carrying on from a capture. Its creatures are already on the field, so nothing is placed. */
    private ChapterRun(Chapter chapter, GameEngine engine, Random random, ChapterRunState state) {
        this.chapter = chapter;
        this.engine = engine;
        this.random = random;
        this.ticksPerSecond = engine.getRules().ticksPerSecond();
        this.startTick = state.startTick();
        creatures.addAll(state.creatures());
        for (int index : state.firedTriggers()) fired.add(chapter.triggers().get(index));
        seenTypes.addAll(state.seenTypes());
        pendingLines.addAll(state.pendingLines());
        kills = state.kills();
        bossSeen = state.bossSeen();
        outcome = state.outcome();
    }

    public static ChapterRun rebuild(Chapter chapter, GameEngine engine, Random random, ChapterRunState state) {
        return new ChapterRun(chapter, engine, random, state);
    }

    /** The chapter's progress. Sets become sorted lists, so equal progress captures equal. */
    public ChapterRunState capture() {
        List<Integer> firedTriggers = new ArrayList<>();
        List<Trigger> triggers = chapter.triggers();
        for (int i = 0; i < triggers.size(); i++) {
            if (fired.contains(triggers.get(i))) firedTriggers.add(i);
        }
        return new ChapterRunState(startTick, creatures.stream().sorted().toList(), List.copyOf(firedTriggers),
                seenTypes.stream().sorted().toList(), List.copyOf(pendingLines), kills, bossSeen, outcome);
    }

    /** Fixed placements go down first, so scattered creatures never take a boss's tile. */
    private void placeAll(List<Spawn> spawns) {
        for (Spawn spawn : spawns) placeFixed(spawn);
        for (Spawn spawn : spawns) placeScattered(spawn);
    }

    /** Called after every engine tick. */
    public Outcome update() {
        if (outcome != Outcome.PLAYING) return outcome;

        for (GameEvent event : engine.getEvents()) {
            if (event.kind() == GameEvent.Kind.DEATH && creatures.remove(event.a())) kills++;
        }
        for (Unit unit : engine.getUnits()) {
            if (unit.isAlive()) seenTypes.add(unit.getType().name());
        }
        for (Trigger trigger : chapter.triggers()) {
            if (!fired.contains(trigger) && due(trigger)) {
                fired.add(trigger);
                placeAll(trigger.spawns());
                pendingLines.addAll(trigger.say());
            }
        }

        if (objectiveMet()) {
            outcome = Outcome.WON;
        } else if (chapter.timeLimitSeconds() > 0 && elapsedSeconds() >= chapter.timeLimitSeconds()) {
            outcome = Outcome.LOST;
        }
        return outcome;
    }

    private boolean due(Trigger trigger) {
        return switch (trigger.when()) {
            case AT_SECOND -> elapsedSeconds() >= trigger.seconds();
            case BOSS_HP_BELOW -> engine.getUnits().stream()
                    .anyMatch(u -> u.getType().name().equals(trigger.unitType())
                            && u.getHp() * 100 < u.getType().maxHp * trigger.percent());
            case TYPE_CLEARED -> seenTypes.contains(trigger.unitType()) && aliveOfType(trigger.unitType()) == 0;
        };
    }

    private boolean objectiveMet() {
        Objective objective = chapter.objective();
        return switch (objective.kind()) {
            case CLEAR_ALL -> elapsedSeconds() >= 1 && pendingTimedTriggers() == 0 && wildAlive() == 0;
            case KILL_BOSS -> {
                long alive = aliveOfType(objective.unitType());
                if (alive > 0) bossSeen = true;
                yield bossSeen && alive == 0;
            }
            case KILL_COUNT -> kills >= objective.count();
            case SURVIVE -> elapsedSeconds() >= objective.seconds();
        };
    }

    /** A short line for the HUD saying how close the goal is. */
    public String progress() {
        Objective objective = chapter.objective();
        return switch (objective.kind()) {
            case CLEAR_ALL -> wildAlive() + " creatures remain";
            case KILL_BOSS -> aliveOfType(objective.unitType()) > 0 ? "The " + label(objective.unitType()) + " lives"
                    : bossSeen ? "Slain" : "Not yet found";
            case KILL_COUNT -> Math.min(kills, objective.count()) + " / " + objective.count() + " slain";
            case SURVIVE -> Math.max(0, objective.seconds() - elapsedSeconds()) + "s to hold out";
        } + (chapter.timeLimitSeconds() > 0 ? " · " + Math.max(0, chapter.timeLimitSeconds() - elapsedSeconds()) + "s left" : "");
    }

    /** Story lines released by triggers since the last call. */
    public List<String> takeLines() {
        List<String> lines = List.copyOf(pendingLines);
        pendingLines.clear();
        return lines;
    }

    public int elapsedSeconds() {
        return (int) ((engine.getTick() - startTick) / ticksPerSecond);
    }

    public Chapter chapter() { return chapter; }
    public Outcome outcome() { return outcome; }
    public int kills() { return kills; }

    // ------------------------------------------------------------------ helpers

    private void placeFixed(Spawn spawn) {
        UnitType type = UnitType.valueOf(spawn.unitType());
        for (int[] p : spawn.at()) {
            Position position = new Position(p[0], p[1]);
            if (engine.isFree(position)) {
                creatures.add(engine.spawn(type, Unit.NEUTRAL, position).getId());
                seenTypes.add(type.name());
            }
        }
    }

    private void placeScattered(Spawn spawn) {
        UnitType type = UnitType.valueOf(spawn.unitType());
        if (spawn.area() == null) return;
        int[] a = spawn.area();
        int placed = 0;
        for (int attempt = 0; attempt < spawn.count() * 40 && placed < spawn.count(); attempt++) {
            Position position = new Position(a[0] + random.nextInt(a[2] - a[0] + 1), a[1] + random.nextInt(a[3] - a[1] + 1));
            if (engine.isFree(position) && !nearStart(position)) {
                creatures.add(engine.spawn(type, Unit.NEUTRAL, position).getId());
                seenTypes.add(type.name());
                placed++;
            }
        }
    }

    /** Living units of a type, or of any of several types written as {@code A,B,C}. */
    /** Scattered creatures keep their distance from where the necromancers rise. */
    private boolean nearStart(Position p) {
        int[] s = chapter.startArea();
        int dx = Math.max(0, Math.max(s[0] - p.x(), p.x() - s[2]));
        int dy = Math.max(0, Math.max(s[1] - p.y(), p.y() - s[3]));
        return Math.max(dx, dy) < START_CLEARANCE;
    }

    private long aliveOfType(String typeNames) {
        Set<String> names = Set.of(typeNames.split(","));
        return engine.getUnits().stream().filter(u -> u.isAlive() && names.contains(u.getType().name())).count();
    }

    private long wildAlive() {
        return engine.getUnits().stream().filter(u -> u.isAlive() && u.getOwnerId() == Unit.NEUTRAL).count();
    }

    private long pendingTimedTriggers() {
        return chapter.triggers().stream()
                .filter(t -> t.when() == Trigger.When.AT_SECOND && !t.spawns().isEmpty() && !fired.contains(t))
                .count();
    }

    private static String label(String typeNames) {
        String[] names = typeNames.split(",");
        return names.length == 1 ? UnitType.valueOf(names[0]).label : "foes";
    }
}
