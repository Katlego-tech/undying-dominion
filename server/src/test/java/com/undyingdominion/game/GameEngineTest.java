package com.undyingdominion.game;

import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameEngineTest {

    private static final int SIZE = 30;

    private GameEngine engine;
    private Player vesper;

    @BeforeEach
    void setUp() {
        GameRules rules = GameRules.defaults().maxNeutrals(0).soulRegenPerSecond(0).startingSoul(100);
        engine = new GameEngine(new GameMap(SIZE, SIZE, List.of()), rules, new Random(7));
        vesper = join("Vesper");
    }

    // ------------------------------------------------------------------ joining and summoning

    @Test
    void joiningRaisesANecromancerWithStartingSoul() {
        Unit necromancer = necromancerOf(vesper);

        assertNotNull(necromancer);
        assertEquals(UnitType.NECROMANCER, necromancer.getType());
        assertEquals(100, vesper.getSoul());
        assertEquals(0, engine.legionSizeOf(vesper));
    }

    @Test
    void summoningSpendsSoulAndPlacesTheSkeletonBesideTheNecromancer() {
        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_WARRIOR));
        engine.tick();

        List<Unit> warriors = owned(vesper, UnitType.SKELETON_WARRIOR);
        assertEquals(1, warriors.size());
        assertEquals(80, vesper.getSoul());
        assertTrue(warriors.get(0).getPosition().distanceTo(necromancerOf(vesper).getPosition()) <= 2);
    }

    @Test
    void dismissingFreesTheLegionSlotsAndLeavesNoCorpse() {
        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_WARRIOR));
        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_WARRIOR));
        engine.tick();
        List<Unit> warriors = owned(vesper, UnitType.SKELETON_WARRIOR);
        assertEquals(2, engine.legionSizeOf(vesper));
        int soul = vesper.getSoul();
        vesper.takeNotices();

        engine.submit(new Command.Dismiss(vesper.getId(), List.of(warriors.get(0).getId())));
        engine.tick();

        assertEquals(1, engine.legionSizeOf(vesper), "the slot is free");
        assertNull(engine.getUnit(warriors.get(0).getId()));
        assertTrue(engine.getCorpses().isEmpty(), "a dismissed unit crumbles to nothing");
        assertEquals(soul, vesper.getSoul(), "no refund");
        assertEquals(0, vesper.getKills(), "and nobody is credited");
        assertTrue(vesper.takeNotices().contains("Dismissed 1 from your legion."));
    }

    @Test
    void dismissingSparesTheNecromancerAndOtherPlayersUnits() {
        Player corvin = join("Corvin");
        engine.submit(new Command.Summon(corvin.getId(), UnitType.SKELETON_WARRIOR));
        engine.tick();
        Unit theirs = owned(corvin, UnitType.SKELETON_WARRIOR).get(0);
        Unit mine = necromancerOf(vesper);

        engine.submit(new Command.Dismiss(vesper.getId(), List.of(mine.getId(), theirs.getId())));
        engine.tick();

        assertNotNull(engine.getUnit(mine.getId()), "a necromancer can't dismiss itself");
        assertNotNull(engine.getUnit(theirs.getId()), "nor anyone else's legion");
    }

    @Test
    void dismissingWorksOnUnitsThatAreMending() {
        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_WARRIOR));
        engine.tick();
        Unit warrior = owned(vesper, UnitType.SKELETON_WARRIOR).get(0);
        warrior.takeDamage(10, 0);
        engine.submit(new Command.Mend(vesper.getId(), List.of(warrior.getId())));
        engine.tick();
        assertTrue(warrior.isRooted());

        engine.submit(new Command.Dismiss(vesper.getId(), List.of(warrior.getId())));
        engine.tick();

        assertEquals(0, engine.legionSizeOf(vesper), "a rooted unit, like a tree that can't move, can still be let go");
    }

    // ------------------------------------------------------------------ guarding a post

    /** An owner that never joined: its wardens stand still and can't fight back, so they make clean targets. */
    private static final int DUMMIES = 99;

    @Test
    void anIdleUnitFightsNearItsPostThenWalksBack() {
        Position post = guardColumn(20);
        Unit warrior = engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), post);
        Unit dummy = engine.spawn(UnitType.GRAVE_WARDEN, DUMMIES, post.offset(0, -4));

        runUntil(() -> !dummy.isAlive(), 400);
        assertTrue(warrior.getPosition().distanceTo(post) >= 2, "it went out to fight");
        runUntil(() -> warrior.getPosition().distanceTo(post) <= 1, 300);

        assertTrue(warrior.getPosition().distanceTo(post) <= 1, "and came back to its post, now at " + warrior.getPosition());
    }

    @Test
    void anIdleUnitDoesNotChainFromFightToFightAwayFromItsPost() {
        Position post = guardColumn(20);
        Unit warrior = engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), post);
        Unit near = engine.spawn(UnitType.GRAVE_WARDEN, DUMMIES, post.offset(0, -4));
        // Within the warrior's reach of where the first fight ends, but beyond its reach of the post.
        Unit far = engine.spawn(UnitType.GRAVE_WARDEN, DUMMIES, post.offset(0, -8));

        runUntil(() -> !near.isAlive(), 400);
        for (int i = 0; i < 300; i++) engine.tick();

        assertTrue(far.isAlive(), "it didn't chase on to the next enemy");
        assertTrue(warrior.getPosition().distanceTo(post) <= 1, "it went home instead, now at " + warrior.getPosition());
    }

    @Test
    void stoppingAUnitMakesWhereItStandsItsPost() {
        Position start = guardColumn(20);
        Unit warrior = engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), start);
        Position there = start.offset(0, -6);
        engine.submit(new Command.Move(vesper.getId(), List.of(warrior.getId()), there, false));
        runUntil(() -> warrior.getPosition().equals(there), 200);

        Unit dummy = engine.spawn(UnitType.GRAVE_WARDEN, DUMMIES, there.offset(0, -3));
        runUntil(() -> !dummy.isAlive(), 400);
        runUntil(() -> warrior.getPosition().distanceTo(there) <= 1, 300);

        assertTrue(warrior.getPosition().distanceTo(there) <= 1, "it guards where it was sent, not where it was summoned");
    }

    /** A tile in a column well away from Vesper's necromancer, so it stays out of the fight. */
    private Position guardColumn(int y) {
        return new Position(necromancerOf(vesper).getPosition().x() < SIZE / 2 ? SIZE - 4 : 3, y);
    }

    private void runUntil(java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) engine.tick();
    }

    @Test
    void summoningWithoutEnoughSoulIsRefused() {
        vesper.setSoul(10);
        vesper.takeNotices();

        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_ARCHER));
        engine.tick();

        assertTrue(owned(vesper, UnitType.SKELETON_ARCHER).isEmpty());
        assertEquals(10, vesper.getSoul());
        assertTrue(vesper.takeNotices().stream().anyMatch(n -> n.startsWith("Not enough soul energy")));
    }

    @Test
    void theLegionCannotOutgrowItsCap() {
        Position necromancer = necromancerOf(vesper).getPosition();
        for (int i = 0; i < vesper.legionCap(); i++) {
            spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 4 + i % 5, 4 + i / 5);
        }
        vesper.takeNotices();

        engine.submit(new Command.Summon(vesper.getId(), UnitType.SKELETON_WARRIOR));
        engine.tick();

        assertEquals(vesper.legionCap(), engine.legionSizeOf(vesper));
        assertTrue(vesper.takeNotices().stream().anyMatch(n -> n.startsWith("Your legion is full")));
    }

    // ------------------------------------------------------------------ the dead

    @Test
    void killingAWildCreatureLeavesACorpseAndPaysSoulAndXp() {
        vesper.setSoul(50);
        Position necromancer = necromancerOf(vesper).getPosition();
        spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 6, 0);
        Unit wolf = spawnNear(UnitType.WOLF, Unit.NEUTRAL, necromancer, 7, 0);
        Position wolfAt = wolf.getPosition();

        tickUntil(() -> engine.getUnit(wolf.getId()) == null, 300);

        Corpse corpse = engine.getCorpses().iterator().next();
        assertEquals(UnitType.WOLF, corpse.type());
        assertEquals(wolfAt, corpse.position());
        assertEquals(1, vesper.getKills());
        assertEquals(UnitType.WOLF.xpValue, vesper.getXp());
        assertEquals(50 + UnitType.WOLF.soulValue, vesper.getSoul());
    }

    @Test
    void raisingACorpseNearTheNecromancerAddsItToTheLegion() {
        Corpse corpse = corpseOfWolfAt(2);
        vesper.setSoul(50);

        engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        engine.tick();

        List<Unit> wolves = owned(vesper, UnitType.WOLF);
        assertEquals(1, wolves.size());
        assertTrue(wolves.get(0).isRaised());
        assertEquals(50 - UnitType.WOLF.raiseCost, vesper.getSoul());
        assertTrue(engine.getCorpses().isEmpty());
    }

    @Test
    void aCorpseTooFarFromTheNecromancerCannotBeRaised() {
        Corpse corpse = corpseOfWolfAt(engine.getRules().raiseRange() + 2);
        vesper.takeNotices();

        engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        engine.tick();

        assertTrue(owned(vesper, UnitType.WOLF).isEmpty());
        assertTrue(vesper.takeNotices().stream().anyMatch(n -> n.startsWith("Too far away")));
    }

    @Test
    void corpsesStayUntilSomeoneUsesThem() {
        Corpse corpse = corpseOfWolfAt(3);

        for (int i = 0; i < 3_000; i++) engine.tick();  // five minutes

        assertEquals(List.of(corpse), List.copyOf(engine.getCorpses()), "the dead wait for a necromancer");
    }

    @Test
    void pastTheCapTheOldestCorpsesCrumbleFirst() {
        engine.getRules().maxCorpses(3);
        List<Integer> kept = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Unit wolf = spawnNear(UnitType.WOLF, Unit.NEUTRAL, necromancerOf(vesper).getPosition(), 3 + i, 3);
            wolf.takeDamage(10_000, vesper.getId());
            engine.tick();
            kept.add(engine.getCorpses().stream().mapToInt(Corpse::id).max().orElseThrow());
        }

        assertEquals(kept.subList(2, 5), engine.getCorpses().stream().map(Corpse::id).toList(), "the newest three remain");
    }

    @Test
    void raisedUnitsTakeNoLegionSpace() {
        Position necromancer = necromancerOf(vesper).getPosition();
        for (int i = 0; i < vesper.legionCap(); i++) {
            spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 4 + i % 5, 4 + i / 5);
        }
        Corpse corpse = corpseOfWolfAt(2);
        vesper.setSoul(50);

        engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        engine.tick();

        assertEquals(1, owned(vesper, UnitType.WOLF).size(), "raised even with every summoning slot taken");
        assertEquals(vesper.legionCap(), engine.legionSizeOf(vesper), "the cap counts what was summoned");
        assertEquals(1, engine.raisedSizeOf(vesper));
    }

    @Test
    void aRaisedUnitReturnsToTheDeadWhenItsTimeRunsOut() {
        engine.getRules().raisedSeconds(1, 0);
        Corpse corpse = corpseOfWolfAt(2);
        vesper.setSoul(50);
        engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        engine.tick();
        assertEquals(1, engine.raisedSizeOf(vesper));
        vesper.takeNotices();

        for (int i = 0; i < engine.getRules().ticksPerSecond() + 1; i++) engine.tick();

        assertEquals(0, engine.raisedSizeOf(vesper), "its time is up");
        assertTrue(engine.getCorpses().isEmpty(), "it returns to the dead without leaving a second corpse");
        assertTrue(vesper.takeNotices().contains("Your raised " + UnitType.WOLF.label + " returns to the dead."));
    }

    @Test
    void theStrongerTheNecromancerTheLongerTheRaisedLast() {
        engine.getRules().raisedSeconds(120, 30);
        vesper.restoreProgress(4, 0, 0, 0);
        Corpse corpse = corpseOfWolfAt(2);
        vesper.setSoul(50);
        engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        engine.tick();

        Unit wolf = owned(vesper, UnitType.WOLF).get(0);
        long expected = (120 + 30 * 4) * engine.getRules().ticksPerSecond();
        assertEquals(expected, wolf.getExpiresAtTick() - (engine.getTick() - 1), "two minutes, and thirty seconds a level");
    }

    @Test
    void unitsWalkStraightAcrossCorpses() {
        Corpse corpse = corpseOfWolfAt(3);
        Position past = corpse.position().offset(1, 0);
        Unit warrior = engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), corpse.position().offset(-1, 0));
        engine.submit(new Command.Move(vesper.getId(), List.of(warrior.getId()), past, false));

        tickUntil(() -> warrior.getPosition().equals(past), 100);

        assertEquals(past, warrior.getPosition(), "the dead don't block the living");
    }

    @Test
    void raisedUnitsCarryIntoTheNextWorldBeyondTheCap() {
        GameRules rules = GameRules.defaults().maxNeutrals(0);
        GameEngine next = new GameEngine(new GameMap(SIZE, SIZE, List.of()), rules, new Random(7));
        next.submit(new Command.Join(vesper.getId(), "Vesper"));
        List<UnitType> summoned = java.util.Collections.nCopies(vesper.legionCap(), UnitType.SKELETON_WARRIOR);
        next.submit(new Command.Restore(vesper.getId(), 1, 0, 0, 0, summoned, List.of(UnitType.WOLF, UnitType.WOLF)));
        next.tick();

        Player there = next.getPlayer(vesper.getId());
        assertEquals(there.legionCap(), next.legionSizeOf(there));
        assertEquals(2, next.raisedSizeOf(there), "the raised come along, and stay raised");
        assertTrue(next.getUnits().stream().filter(u -> u.getType() == UnitType.WOLF).allMatch(Unit::isRaised));
    }

    @Test
    void fiveWarriorsStandingTogetherFuseIntoASkeletonKing() {
        Position necromancer = necromancerOf(vesper).getPosition();
        List<Integer> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 4 + i % 3, 4 + i / 3).getId());
        }

        engine.submit(new Command.Fuse(vesper.getId(), ids));
        engine.tick();

        assertTrue(owned(vesper, UnitType.SKELETON_WARRIOR).isEmpty());
        assertEquals(1, owned(vesper, UnitType.SKELETON_KING).size());
        assertEquals(100 - engine.getRules().fuseSoulCost(), vesper.getSoul());
    }

    @Test
    void raisedWarriorsCannotBeFusedIntoAPermanentKing() {
        Position necromancer = necromancerOf(vesper).getPosition();
        for (int i = 0; i < 5; i++) {
            spawnNear(UnitType.SKELETON_WARRIOR, 99, necromancer, 2 + i % 3, 2 + i / 3).takeDamage(10_000, vesper.getId());
        }
        engine.tick();
        vesper.setSoul(200);
        for (Corpse corpse : List.copyOf(engine.getCorpses())) {
            engine.submit(new Command.Raise(vesper.getId(), corpse.id()));
        }
        engine.tick();
        List<Integer> raised = owned(vesper, UnitType.SKELETON_WARRIOR).stream().map(Unit::getId).toList();
        assertEquals(5, raised.size());
        vesper.takeNotices();

        engine.submit(new Command.Fuse(vesper.getId(), raised));
        engine.tick();

        assertTrue(owned(vesper, UnitType.SKELETON_KING).isEmpty(), "a king would be permanent and counted, escaping both limits");
        assertEquals(5, engine.raisedSizeOf(vesper));
        assertTrue(vesper.takeNotices().contains("Only summoned Skeleton Warriors can be fused. The raised are held only for a while."));
    }

    @Test
    void fourWarriorsAreNotEnoughToFuse() {
        Position necromancer = necromancerOf(vesper).getPosition();
        List<Integer> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 4 + i, 4).getId());
        }
        vesper.takeNotices();

        engine.submit(new Command.Fuse(vesper.getId(), ids));
        engine.tick();

        assertEquals(4, owned(vesper, UnitType.SKELETON_WARRIOR).size());
        assertTrue(vesper.takeNotices().stream().anyMatch(n -> n.startsWith("Select at least 5")));
    }

    @Test
    void whenTheNecromancerFallsItsLegionCrumblesAndItRisesAgainLater() {
        Player corvin = join("Corvin");
        Position necromancer = necromancerOf(vesper).getPosition();
        spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancer, 1, 1);
        spawnNear(UnitType.SKELETON_ARCHER, vesper.getId(), necromancer, 1, 2);

        necromancerOf(vesper).takeDamage(10_000, corvin.getId());
        engine.tick();

        assertNull(vesper.getNecromancerId());
        assertEquals(0, engine.legionSizeOf(vesper));
        assertEquals(1, vesper.getDeaths());
        assertEquals(1, corvin.getKills(), "only the necromancer counts; its collapsing legion pays nobody");
        assertEquals(2, engine.getCorpses().size(), "the legion leaves corpses for anyone to raise");

        for (int i = 0; i < engine.getRules().respawnTicks(); i++) engine.tick();
        assertNotNull(necromancerOf(vesper));
    }

    @Test
    void corpseExplosionHurtsEveryEnemyAroundTheCorpse() {
        Corpse corpse = corpseOfWolfAt(3);
        Unit near = engine.spawn(UnitType.KNIGHT, Unit.NEUTRAL, corpse.position().offset(1, 0));
        Unit far = spawnNear(UnitType.KNIGHT, Unit.NEUTRAL, corpse.position(), 6, 4);
        // On the far side of the corpse from the knight, out of its reach this tick.
        Unit own = engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), corpse.position().offset(-1, 0));
        vesper.setSoul(50);

        engine.submit(new Command.Explode(vesper.getId(), corpse.id()));
        engine.tick();

        int blast = UnitType.WOLF.maxHp * engine.getRules().explodeDamagePercent() / 100;
        assertTrue(engine.getCorpses().isEmpty());
        assertEquals(50 - engine.getRules().explodeSoulCost(), vesper.getSoul());
        assertTrue(near.getHp() <= UnitType.KNIGHT.maxHp - blast, "the knight beside it was caught");
        assertEquals(UnitType.KNIGHT.maxHp, far.getHp());
        assertEquals(UnitType.SKELETON_WARRIOR.maxHp, own.getHp(), "your own units are spared");
    }

    @Test
    void aCorpseTooFarAwayCannotBeDetonated() {
        Corpse corpse = corpseOfWolfAt(engine.getRules().raiseRange() + 2);
        vesper.takeNotices();

        engine.submit(new Command.Explode(vesper.getId(), corpse.id()));
        engine.tick();

        assertEquals(1, engine.getCorpses().size());
        assertTrue(vesper.takeNotices().stream().anyMatch(n -> n.startsWith("Too far away")));
    }

    // ------------------------------------------------------------------ mend, from Robot Worlds' repair

    @Test
    void mendingRootsTheUnitThenRestoresItFully() {
        Unit warrior = spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancerOf(vesper).getPosition(), 4, 4);
        warrior.takeDamage(35, Unit.NEUTRAL);

        engine.submit(new Command.Mend(vesper.getId(), List.of(warrior.getId())));
        engine.tick();
        Position rootedAt = warrior.getPosition();
        engine.submit(new Command.Move(vesper.getId(), List.of(warrior.getId()), rootedAt.offset(3, 0), false));
        for (int i = 1; i < engine.getRules().mendTicks(); i++) engine.tick();

        assertEquals(Unit.Order.MEND, warrior.getOrder(), "a mending unit ignores orders until it is done");
        assertEquals(rootedAt, warrior.getPosition());
        assertEquals(25, warrior.getHp());

        engine.tick();
        assertEquals(Unit.Order.IDLE, warrior.getOrder());
        assertEquals(UnitType.SKELETON_WARRIOR.maxHp, warrior.getHp());
    }

    // ------------------------------------------------------------------ traps, from Robot Worlds' mines

    @Test
    void aWardenLaysATrapThenStepsForwardOffIt() {
        Unit warden = spawnNear(UnitType.GRAVE_WARDEN, vesper.getId(), necromancerOf(vesper).getPosition(), 5, 5);
        Position laidAt = warden.getPosition();

        layTrapAndWait(warden);

        Trap trap = engine.getTraps().iterator().next();
        assertEquals(laidAt, trap.position());
        assertEquals(laidAt.offset(0, -1), warden.getPosition(), "a launched unit faces north, so it steps north");
        assertEquals(UnitType.GRAVE_WARDEN.maxHp, warden.getHp());
    }

    @Test
    void aWardenThatCannotStepForwardSpringsItsOwnTrap() {
        Unit warden = spawnNear(UnitType.GRAVE_WARDEN, vesper.getId(), necromancerOf(vesper).getPosition(), 5, 5);
        Position laidAt = warden.getPosition();
        engine.spawn(UnitType.SKELETON_WARRIOR, vesper.getId(), laidAt.offset(0, -1));

        layTrapAndWait(warden);

        assertTrue(engine.getTraps().isEmpty());
        assertEquals(laidAt, warden.getPosition());
        assertEquals(UnitType.GRAVE_WARDEN.maxHp - engine.getRules().trapDamage(), warden.getHp());
    }

    @Test
    void aUnitLayingATrapTakesDoubleDamage() {
        Unit warden = spawnNear(UnitType.GRAVE_WARDEN, vesper.getId(), necromancerOf(vesper).getPosition(), 5, 5);
        engine.submit(new Command.SetTrap(vesper.getId(), List.of(warden.getId())));
        engine.tick();

        warden.takeDamage(10, Unit.NEUTRAL);

        assertEquals(UnitType.GRAVE_WARDEN.maxHp - 20, warden.getHp());
    }

    @Test
    void anEnemyThatWalksOntoATrapTakesItsDamage() {
        Unit warden = spawnNear(UnitType.GRAVE_WARDEN, vesper.getId(), necromancerOf(vesper).getPosition(), 5, 5);
        Position trapAt = warden.getPosition();
        layTrapAndWait(warden);

        Player corvin = join("Corvin");
        Unit intruder = spawnNear(UnitType.SKELETON_WARRIOR, corvin.getId(), trapAt, 3, 0);
        engine.submit(new Command.Move(corvin.getId(), List.of(intruder.getId()), trapAt, false));
        tickUntil(() -> intruder.getPosition().equals(trapAt), 100);
        engine.tick();

        assertTrue(engine.getTraps().isEmpty());
        assertEquals(UnitType.SKELETON_WARRIOR.maxHp - engine.getRules().trapDamage(), intruder.getHp());
    }

    @Test
    void onlyGraveWardensCanLayTraps() {
        Unit warrior = spawnNear(UnitType.SKELETON_WARRIOR, vesper.getId(), necromancerOf(vesper).getPosition(), 4, 4);
        vesper.takeNotices();

        engine.submit(new Command.SetTrap(vesper.getId(), List.of(warrior.getId())));
        engine.tick();

        assertEquals(Unit.Order.IDLE, warrior.getOrder());
        assertTrue(vesper.takeNotices().contains("Only Grave Wardens can lay traps."));
    }

    // ------------------------------------------------------------------ helpers

    private Player join(String name) {
        int id = engine.allocatePlayerId();
        engine.submit(new Command.Join(id, name));
        engine.tick();
        return engine.getPlayer(id);
    }

    private Unit necromancerOf(Player player) {
        Integer id = player.getNecromancerId();
        return id == null ? null : engine.getUnit(id);
    }

    private List<Unit> owned(Player player, UnitType type) {
        return engine.getUnits().stream()
                .filter(u -> u.getOwnerId() == player.getId() && u.getType() == type)
                .toList();
    }

    /**
     * Spawns a unit offset from {@code origin}, leaning towards the middle of the map so it never
     * falls off an edge, and sliding along if the tile is taken.
     */
    private Unit spawnNear(UnitType type, int owner, Position origin, int dx, int dy) {
        int sx = origin.x() < SIZE / 2 ? 1 : -1;
        int sy = origin.y() < SIZE / 2 ? 1 : -1;
        for (int slide = 0; slide < SIZE; slide++) {
            Position p = new Position(origin.x() + sx * (dx + slide), origin.y() + sy * dy);
            boolean taken = engine.getUnits().stream().anyMatch(u -> u.getPosition().equals(p));
            if (!taken && engine.getMap().inBounds(p)) return engine.spawn(type, owner, p);
        }
        throw new IllegalStateException("No room near " + origin);
    }

    private Corpse corpseOfWolfAt(int distance) {
        Unit wolf = spawnNear(UnitType.WOLF, Unit.NEUTRAL, necromancerOf(vesper).getPosition(), distance, 0);
        wolf.takeDamage(10_000, vesper.getId());
        engine.tick();
        assertFalse(engine.getCorpses().isEmpty());
        return engine.getCorpses().iterator().next();
    }

    private void layTrapAndWait(Unit warden) {
        engine.submit(new Command.SetTrap(vesper.getId(), List.of(warden.getId())));
        for (int i = 0; i <= engine.getRules().trapSetTicks(); i++) engine.tick();
    }

    private void tickUntil(java.util.function.BooleanSupplier done, int limit) {
        for (int i = 0; i < limit && !done.getAsBoolean(); i++) engine.tick();
        assertTrue(done.getAsBoolean(), "condition not reached in " + limit + " ticks");
    }
}
