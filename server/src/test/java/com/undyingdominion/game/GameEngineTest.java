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
    void corpsesRotAwayIfNobodyRaisesThem() {
        corpseOfWolfAt(3);

        for (int i = 0; i < engine.getRules().corpseDecayTicks() + 1; i++) engine.tick();

        assertTrue(engine.getCorpses().isEmpty());
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
