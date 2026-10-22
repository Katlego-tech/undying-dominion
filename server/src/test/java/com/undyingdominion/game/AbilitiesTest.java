package com.undyingdominion.game;

import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Obstacle;
import com.undyingdominion.world.Position;
import com.undyingdominion.world.Terrain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every effect kind and status of docs/design/characters.md §3, each on a small open map with the
 * commanders put exactly where the case needs them. Distances keep idle units out of each other's
 * aggro unless a case wants a fight.
 */
class AbilitiesTest {

    private final Roster roster = Roster.builtIn();
    private GameEngine engine;

    @BeforeEach
    void setUp() {
        GameRules rules = GameRules.defaults().maxNeutrals(0).soulRegenPerSecond(0).startingSoul(100).corpses(false);
        // A wall of ruins at x = 20, rows 0 to 10, for dashes over terrain.
        engine = new GameEngine(new GameMap(30, 30, List.of(new Obstacle(20, 0, 20, 10, Terrain.RUINS))), rules,
                new Random(7));
    }

    // ------------------------------------------------------------------ casting

    @Test
    void aCastSpendsItsCostStartsItsCooldownAndIsRefusedUntilReady() {
        Player morrow = join("Morrow", "HEXER", 5, 5);
        Player pell = join("Pell", "SEER", 10, 5);
        int hp = unit(pell).getHp();

        cast(morrow, "hexfire", unit(pell));

        assertEquals(hp - 22, unit(pell).getHp());
        assertEquals(88, morrow.getSoul());
        assertEquals(59, morrow.cooldownLeft("hexfire", engine.getTick()), "6 s from the cast, a tick ago");
        assertEquals(1, engine.getEvents().stream().filter(e -> e.kind() == GameEvent.Kind.CAST).count());

        cast(morrow, "hexfire", unit(pell));
        assertTrue(morrow.takeNotices().contains("Hexfire is ready in 6s."));
        assertEquals(88, morrow.getSoul(), "a refused cast costs nothing");

        pass(60);
        cast(morrow, "hexfire", unit(pell));
        assertEquals(76, morrow.getSoul(), "ready again");
    }

    @Test
    void eachRefusalNamesItsReasonAndSpendsNothing() {
        Player morrow = join("Morrow", "HEXER", 5, 5);
        Player pell = join("Pell", "SEER", 15, 5);
        Player ilsa = join("Ilsa", "GROVE_ELDER", 5, 12);

        cast(morrow, "hexfire", unit(pell));
        assertTrue(morrow.takeNotices().contains("Too far for Hexfire. Move within 5 tiles."));
        cast(morrow, "summoning-seal", engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(6, 5)));
        assertTrue(morrow.takeNotices().contains("Summoning Seal needs an enemy commander."));
        cast(ilsa, "mending-light", unit(morrow));
        assertTrue(ilsa.takeNotices().contains("Mending Light needs an ally."));

        morrow.setSoul(10);
        cast(morrow, "withering-curse", null, new Position(6, 6));
        assertTrue(morrow.takeNotices().contains("Not enough Mana for Withering Curse."));
        assertEquals(10, morrow.getSoul());
        assertEquals(0, morrow.cooldownLeft("withering-curse", engine.getTick()));

        engine.applyStatus(unit(morrow), Status.SILENCED, 0, engine.getTick() + 30, unit(pell));
        cast(morrow, "hexfire", unit(pell));
        assertTrue(morrow.takeNotices().contains("You are silenced."));
    }

    @Test
    void steppingInFrontNeedsSomeoneElse() {
        Player dunstan = join("Dunstan", "BULWARK", 5, 5);

        cast(dunstan, "stand-in-front", unit(dunstan));

        assertTrue(dunstan.takeNotices().contains("Stand in Front needs an ally."));
        assertEquals(0, unit(dunstan).getShield());
        assertEquals(100, dunstan.getSoul());
    }

    @Test
    void aWallBetweenCasterAndTargetBlocksSight() {
        Player morrow = join("Morrow", "HEXER", 18, 5);
        Player pell = join("Pell", "SEER", 22, 5);

        cast(morrow, "hexfire", unit(pell));

        assertTrue(morrow.takeNotices().contains("You can't see that target."));
        assertEquals(100, morrow.getSoul());
    }

    @Test
    void theFallenCannotCast() {
        Player morrow = join("Morrow", "HEXER", 5, 5);
        unit(morrow).collapse();
        engine.tick();
        morrow.takeNotices();

        cast(morrow, "withering-curse", null, new Position(6, 6));

        assertTrue(morrow.takeNotices().contains("The fallen cannot use Withering Curse."));
    }

    // ------------------------------------------------------------------ effects

    @Test
    void damageIsRaisedByExposedAndByMightAndLoweredByWard() {
        Player morrow = join("Morrow", "HEXER", 5, 5);
        Player pell = join("Pell", "SEER", 10, 5);
        Unit target = unit(pell);

        cast(morrow, "hexfire", target);
        assertEquals(15, target.magnitude(Status.EXPOSED));
        int hp = target.getHp();
        engine.abilityDamage(unit(morrow), target, 20);
        assertEquals(hp - 23, target.getHp(), "20 + 15 %");

        engine.applyStatus(unit(morrow), Status.MIGHT, 50, engine.getTick() + 100, unit(morrow));
        engine.applyStatus(target, Status.WARD, 15, engine.getTick() + 100, target);
        hp = target.getHp();
        engine.abilityDamage(unit(morrow), target, 22);
        assertEquals(hp - 33, target.getHp(), "33 dealt, ward and exposure cancel");
    }

    @Test
    void healingNeverGoesAboveMaxAndAShieldSoaksDamageUntilItRunsOut() {
        Player elowen = join("Elowen", "GROVE_ELDER", 5, 5);
        Unit self = unit(elowen);
        int max = self.getType().maxHp;
        self.takeDamage(100, Unit.NEUTRAL);
        cast(elowen, "mending-light", self);
        assertEquals(max - 45, self.getHp(), "55 back of 100");
        pass(50);
        cast(elowen, "mending-light", self);
        assertEquals(max, self.getHp());

        Player calder = join("Calder", "WARD_MAGE", 15, 15);
        cast(calder, "energy-shield");
        assertEquals(70, unit(calder).getShield());
        unit(calder).takeDamage(60, Unit.NEUTRAL);
        assertEquals(unit(calder).getType().maxHp, unit(calder).getHp(), "all of it absorbed");
        assertEquals(10, unit(calder).getShield());
        pass(80);
        assertEquals(0, unit(calder).getShield(), "gone after 8 s");
    }

    @Test
    void theDamageOrderIsWardThenExposedThenTheShield() {
        Unit unit = new Unit(1, UnitType.SKELETON_WARRIOR, 1, new Position(0, 0), false);
        unit.apply(new StatusEffect(Status.WARD, 50, 100, 1));
        unit.apply(new StatusEffect(Status.EXPOSED, 10, 100, 2));
        unit.shield(10, 100);

        unit.takeDamage(40, 2);

        assertEquals(60 - 14, unit.getHp(), "40 × 60 % = 24, 10 of it absorbed");
        assertEquals(0, unit.getShield());
    }

    @Test
    void evadingMissesStrikesButNeverAbilityDamage() {
        Unit unit = new Unit(1, UnitType.SKELETON_WARRIOR, 1, new Position(0, 0), false);
        unit.apply(new StatusEffect(Status.EVADING, 100, 100, 1));

        assertFalse(unit.takeStrike(10, 2, new Random(1)));
        assertEquals(60, unit.getHp());
        unit.takeDamage(10, 2);
        assertEquals(50, unit.getHp());
    }

    @Test
    void statusesDontStackTheStrongerStays() {
        Unit unit = new Unit(1, UnitType.SKELETON_WARRIOR, 1, new Position(0, 0), false);
        unit.apply(new StatusEffect(Status.MIGHT, 20, 50, 1));
        unit.apply(new StatusEffect(Status.MIGHT, 10, 90, 1));
        assertEquals(20, unit.magnitude(Status.MIGHT));
        unit.apply(new StatusEffect(Status.MIGHT, 20, 80, 1));
        assertEquals(80, unit.status(Status.MIGHT).untilTick(), "equally strong: the later end");
        unit.expire(80);
        assertFalse(unit.has(Status.MIGHT));
    }

    @Test
    void aSummonEffectPlacesWhatFitsAndNamesTheRest() {
        Player varro = join("Varro", "WARLORD", 15, 15);
        for (int i = 0; i < 9; i++) engine.spawn(UnitType.valueOf("LEGIONARY"), varro.getId(), new Position(i, 25));

        cast(varro, "form-ranks");

        assertEquals(10, engine.legionSizeOf(varro));
        assertTrue(varro.takeNotices().contains("Your legion is full: 1 Levy could not answer."));
        assertEquals(75, varro.getSoul(), "the cast still counts");
    }

    @Test
    void sealedPlayersCanNeitherSummonNorCallAndSummonEffectsPlaceNothing() {
        Player varro = join("Varro", "WARLORD", 5, 5);
        Player morrow = join("Morrow", "HEXER", 10, 5);
        cast(morrow, "summoning-seal", unit(varro));
        assertTrue(unit(varro).has(Status.SEALED));
        assertTrue(unit(varro).has(Status.SILENCED));
        varro.takeNotices();

        engine.submit(new Command.Summon(varro.getId(), UnitType.valueOf("LEGIONARY")));
        engine.tick();
        assertTrue(varro.takeNotices().contains("You are sealed and cannot call."));

        pass(30);  // the silence lifts after 3 s, the seal holds for 12
        cast(varro, "form-ranks");
        assertTrue(varro.takeNotices().contains("You are sealed and cannot call."));
        assertEquals(0, engine.legionSizeOf(varro));
    }

    @Test
    void onlyTheRetinueAnswersASummon() {
        Player dunstan = join("Dunstan", "BULWARK", 5, 5);

        engine.submit(new Command.Summon(dunstan.getId(), UnitType.SKELETON_WARRIOR));
        engine.submit(new Command.Summon(dunstan.getId(), UnitType.valueOf("MILITIA")));
        engine.tick();

        assertTrue(dunstan.takeNotices().contains("Skeleton Warrior doesn't answer to Dunstan."));
        assertEquals(1, engine.legionSizeOf(dunstan));
        assertEquals(82, dunstan.getSoul());
    }

    @Test
    void aDashMovesTheCasterAndHitsWhatItPasses() {
        Player thessa = join("Thessa", "GROVE_KNIGHT", 5, 15);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(8, 16));

        cast(thessa, "charge", null, new Position(11, 15));

        assertEquals(new Position(11, 15), unit(thessa).getPosition());
        assertEquals(45 - 32, wolf.getHp());
    }

    @Test
    void aDashMidWalkDoesntSnapBackAlongTheOldPath() {
        Player thessa = join("Thessa", "GROVE_KNIGHT", 5, 25);
        engine.submit(new Command.Move(thessa.getId(), List.of(unit(thessa).getId()), new Position(25, 25), false));
        pass(6);
        Position before = unit(thessa).getPosition();

        cast(thessa, "charge", null, new Position(before.x() + 6, 25));
        Position landed = unit(thessa).getPosition();
        pass(3);

        assertTrue(unit(thessa).getPosition().x() >= landed.x(), "kept going from where it landed, not back from " + landed);
    }

    @Test
    void aDashOverTerrainCrossesWallsAndHides() {
        Player hollin = join("Hollin", "PAPER_BINDER", 16, 5);

        cast(hollin, "paper-wings", null, new Position(24, 5));

        assertEquals(new Position(24, 5), unit(hollin).getPosition());
        assertTrue(unit(hollin).has(Status.STEALTHED));
    }

    @Test
    void aPushDrivesEnemiesStraightAway() {
        Player calder = join("Calder", "WARD_MAGE", 10, 15);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(11, 15));
        engine.applyStatus(wolf, Status.ROOTED, 0, engine.getTick() + 50, null);  // so it can't walk back
        unit(calder).setAttackReadyTick(engine.getTick() + 100);  // only the ring lands

        cast(calder, "fire-ring");

        assertEquals(new Position(13, 15), wolf.getPosition());
        assertEquals(45 - 16, wolf.getHp());
    }

    @Test
    void aDecoyTauntsEnemiesAndCrumblesInTime() {
        Player hollin = join("Hollin", "PAPER_BINDER", 5, 20);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(12, 20));

        cast(hollin, "paper-double", null, new Position(10, 20));

        Unit decoy = engine.getUnits().stream().filter(u -> u.getType().name().equals("PAPER_DOUBLE")).findFirst().orElseThrow();
        assertEquals(decoy.getId(), wolf.status(Status.TAUNTED).sourceUnitId());
        pass(81);
        assertTrue(engine.getUnits().stream().noneMatch(u -> u.getType().name().equals("PAPER_DOUBLE")), "gone after 8 s");
    }

    @Test
    void tauntPullsAttacksButNotAMoveOrder() {
        Player thessa = join("Thessa", "GROVE_KNIGHT", 10, 10);
        Player garrick = join("Garrick", "BERSERKER", 12, 10);
        Unit decoy = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(12, 13));
        engine.submit(new Command.Attack(garrick.getId(), List.of(unit(garrick).getId()), decoy.getId()));
        engine.tick();

        cast(thessa, "taunt");
        pass(10);
        assertTrue(engine.getEvents().stream().noneMatch(e -> e.kind() == GameEvent.Kind.ATTACK && e.b() == decoy.getId()
                && e.a() == unit(garrick).getId()));
        assertTrue(unit(thessa).getHp() < 320, "the Berserker turned on the knight");

        engine.submit(new Command.Move(garrick.getId(), List.of(unit(garrick).getId()), new Position(20, 25), false));
        pass(10);
        assertTrue(unit(garrick).getPosition().distanceTo(unit(thessa).getPosition()) > 1, "a move order walks out");
    }

    @Test
    void unstoppableStripsAndBlocksControlButNotItsOwnSides() {
        Player lucan = join("Lucan", "SUNWARD", 5, 5);
        Player elowen = join("Elowen", "GROVE_ELDER", 10, 5);
        cast(elowen, "entangle", unit(lucan));
        assertTrue(unit(lucan).has(Status.ROOTED));

        cast(lucan, "unbreakable-aura");
        assertFalse(unit(lucan).has(Status.ROOTED));
        engine.applyStatus(unit(lucan), Status.SLOW, 50, engine.getTick() + 30, unit(elowen));
        assertFalse(unit(lucan).has(Status.SLOW));

        Player dunstan = join("Dunstan", "BULWARK", 20, 20);
        cast(dunstan, "iron-stance");
        assertTrue(unit(dunstan).has(Status.UNSTOPPABLE));
        assertTrue(unit(dunstan).has(Status.ROOTED), "his own stance roots him");
        assertEquals(45, unit(dunstan).magnitude(Status.WARD));
    }

    @Test
    void cleanseRemovesControlAndBurning() {
        Player varro = join("Varro", "WARLORD", 5, 5);
        Unit self = unit(varro);
        engine.applyStatus(self, Status.SLOW, 40, engine.getTick() + 50, null);
        engine.applyStatus(self, Status.BURNING, 3, engine.getTick() + 50, null);
        engine.applyStatus(self, Status.MIGHT, 10, engine.getTick() + 50, null);

        cast(varro, "rally");

        assertFalse(self.has(Status.SLOW));
        assertFalse(self.has(Status.BURNING));
        assertTrue(self.has(Status.MIGHT));
        assertEquals(30, self.magnitude(Status.HASTE));
    }

    @Test
    void aDelayedStatusArrivesLater() {
        Player garrick = join("Garrick", "BERSERKER", 5, 5);
        cast(garrick, "berserk");
        assertEquals(60, unit(garrick).magnitude(Status.MIGHT));

        pass(98);
        assertFalse(unit(garrick).has(Status.WEAKENED));
        pass(2);
        assertEquals(30, unit(garrick).magnitude(Status.WEAKENED));
    }

    @Test
    void empoweredDoublesTheNextStrikeOnly() {
        Player garrick = join("Garrick", "BERSERKER", 5, 5);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(6, 5));
        wolf.takeDamage(-955, Unit.NEUTRAL);  // a tougher wolf, so it survives to be hit twice
        unit(garrick).setAttackReadyTick(engine.getTick() + 5);
        cast(garrick, "berserk-strike");
        int hp = wolf.getHp();

        pass(5);
        assertEquals(hp - 32, wolf.getHp(), "16 × 200 %");
        assertFalse(unit(garrick).has(Status.EMPOWERED));
    }

    @Test
    void cleavingHitsTheEnemiesBesideTheTarget() {
        Player seren = join("Seren", "BRIGHT_BLADE", 5, 5);
        Unit first = engine.spawn(UnitType.KNIGHT, Unit.NEUTRAL, new Position(6, 5));
        Unit beside = engine.spawn(UnitType.KNIGHT, Unit.NEUTRAL, new Position(7, 5));
        unit(seren).setAttackReadyTick(engine.getTick() + 3);
        cast(seren, "holy-edge");

        engine.submit(new Command.Attack(seren.getId(), List.of(unit(seren).getId()), first.getId()));
        pass(3);

        assertEquals(95 - 19, first.getHp(), "14 + 40 %");
        assertEquals(95 - 9, beside.getHp());
    }

    @Test
    void hasteAndSlowChangeThePaceAndRootedHoldsStill() {
        Player pell = join("Pell", "SEER", 5, 5);
        Unit self = unit(pell);
        engine.applyStatus(self, Status.SLOW, 50, engine.getTick() + 100, null);
        engine.submit(new Command.Move(pell.getId(), List.of(self.getId()), new Position(15, 5), false));
        engine.tick();
        assertEquals(6, self.getMoveReadyTick() - (engine.getTick() - 1), "4 ticks, 50 % slower");

        Player elowen = join("Elowen", "GROVE_ELDER", 25, 25);
        engine.applyStatus(unit(elowen), Status.ROOTED, 0, engine.getTick() + 100, null);
        engine.submit(new Command.Move(elowen.getId(), List.of(unit(elowen).getId()), new Position(15, 25), false));
        pass(20);
        assertEquals(new Position(25, 25), unit(elowen).getPosition());
        assertEquals(Unit.Order.MOVE, unit(elowen).getOrder(), "it waits rather than giving up");
    }

    @Test
    void regenHealsAndBurningBurnsEverySecondCreditedToTheSource() {
        Player varro = join("Varro", "WARLORD", 5, 5);
        Player lucan = join("Lucan", "SUNWARD", 25, 25);
        Unit self = unit(varro);
        self.takeDamage(30, Unit.NEUTRAL);
        engine.applyStatus(self, Status.REGEN, 6, engine.getTick() + 50, self);
        engine.applyStatus(self, Status.BURNING, 5, engine.getTick() + 50, unit(lucan));
        int hp = self.getHp();

        pass(10);

        assertEquals(hp + 1, self.getHp(), "+6 −5");
        assertEquals(lucan.getId(), self.getLastHitByOwner());
    }

    @Test
    void aurasReachOwnUnitsNearbyAndRefreshEverySecond() {
        Player varro = join("Varro", "WARLORD", 5, 5);
        Unit near = engine.spawn(UnitType.valueOf("LEGIONARY"), varro.getId(), new Position(8, 5));
        Unit far = engine.spawn(UnitType.valueOf("LEGIONARY"), varro.getId(), new Position(15, 5));

        pass(20);

        assertEquals(5, near.magnitude(Status.MIGHT));
        assertFalse(far.has(Status.MIGHT));
        assertEquals(5, unit(varro).magnitude(Status.MIGHT));
    }

    @Test
    void anImmunePassiveKeepsItsStatusOff() {
        Player ignar = join("Ignar", "PYRE_CHAMPION", 5, 5);
        engine.applyStatus(unit(ignar), Status.BURNING, 5, engine.getTick() + 50, null);
        assertFalse(unit(ignar).has(Status.BURNING));
    }

    @Test
    void bonusesAgainstRetinuesAndCommanders() {
        Player morrow = join("Morrow", "HEXER", 5, 5);
        Player pell = join("Pell", "SEER", 10, 5);
        Unit guard = engine.spawn(UnitType.valueOf("ACOLYTE_GUARD"), pell.getId(), new Position(10, 7));

        cast(morrow, "hexfire", guard);

        assertEquals(60 - 26, guard.getHp(), "22 + 20 % against a retinue");
    }

    @Test
    void stealthHidesUntilStandingCloseADetectorOrARevealAndEndsOnACast() {
        Player vey = join("Vey", "SHADE", 10, 10);
        Player dunstan = join("Dunstan", "BULWARK", 14, 10);
        cast(vey, "stealth");
        Unit shade = unit(vey);

        assertFalse(engine.sightOf(dunstan).shows(shade));
        assertTrue(engine.sightOf(vey).shows(shade), "always to its owner");
        engine.place(unit(dunstan), new Position(11, 11));
        assertTrue(engine.sightOf(dunstan).shows(shade), "next to it");
        engine.place(unit(dunstan), new Position(25, 25));

        Player aurelle = join("Aurelle", "DAWN_RIDER", 14, 12);
        assertTrue(engine.sightOf(aurelle).shows(shade), "Light of Dawn within 4");

        Player pell = join("Pell", "SEER", 18, 18);
        assertFalse(engine.sightOf(pell).shows(shade));
        cast(pell, "foresight", null, new Position(12, 12));
        assertTrue(engine.sightOf(pell).shows(shade), "inside the reveal");

        cast(vey, "quickening-charm");
        assertFalse(shade.has(Status.STEALTHED), "casting gives it away");
    }

    @Test
    void stealthDoublesAFatalStrikeAndQuickeningReadiesTheRest() {
        Player vey = join("Vey", "SHADE", 10, 10);
        Unit knight = engine.spawn(UnitType.KNIGHT, Unit.NEUTRAL, new Position(11, 10));
        unit(vey).setAttackReadyTick(engine.getTick() + 100);  // only the ability lands
        cast(vey, "stealth");

        cast(vey, "fatal-strike", knight);
        assertEquals(95 - 52, knight.getHp(), "26 + 100 % from stealth");

        cast(vey, "quickening-charm");
        assertEquals(0, vey.cooldownLeft("stealth", engine.getTick()));
        assertEquals(0, vey.cooldownLeft("fatal-strike", engine.getTick()));
        assertTrue(vey.cooldownLeft("quickening-charm", engine.getTick()) > 0);
    }

    @Test
    void energyPassivesPayOverTimeAndOnKills() {
        Player hollin = join("Hollin", "PAPER_BINDER", 5, 5);
        hollin.setSoul(0);
        pass(40);
        assertEquals(2, hollin.getSoul(), "0.5 a second");

        Player garrick = join("Garrick", "BERSERKER", 20, 20);
        garrick.setSoul(0);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(21, 20));
        wolf.takeDamage(44, Unit.NEUTRAL);
        pass(3);
        assertEquals(UnitType.WOLF.soulValue + 5, garrick.getSoul());
    }

    @Test
    void aFreeForAllLeavesNoCorpses() {
        join("Garrick", "BERSERKER", 20, 20);
        Unit wolf = engine.spawn(UnitType.WOLF, Unit.NEUTRAL, new Position(21, 20));
        wolf.takeDamage(44, Unit.NEUTRAL);
        pass(3);
        assertNotNull(wolf);
        assertTrue(engine.getCorpses().isEmpty());
    }

    @Test
    void aPickWhileFieldedWaitsForTheRiseWhichClearsCooldowns() {
        Player pell = join("Pell", "SEER", 5, 5);
        cast(pell, "foresight", null, new Position(8, 8));
        engine.submit(new Command.Pick(pell.getId(), roster.get("SHADE")));
        engine.tick();
        assertTrue(pell.takeNotices().contains("You will rise as Vey, the Shade."));
        assertEquals("SEER", pell.commander().id());

        unit(pell).collapse();
        pass(70);

        assertEquals("SHADE", pell.commander().id());
        assertEquals("SHADE", unit(pell).getType().name());
        assertEquals(0, pell.cooldownLeft("stealth", engine.getTick()));
    }

    // ------------------------------------------------------------------ helpers

    private Player join(String name, String commander, int x, int y) {
        int id = engine.allocatePlayerId();
        engine.submit(new Command.Join(id, name, roster.get(commander)));
        engine.tick();
        Player player = engine.getPlayer(id);
        Unit unit = unit(player);
        Position at = new Position(x, y);
        Unit there = engine.getUnits().stream().filter(u -> u.getPosition().equals(at) && u != unit).findFirst().orElse(null);
        if (there != null) engine.place(there, new Position(29, 29 - id));
        engine.place(unit, at);
        unit.orderIdle();
        player.takeNotices();
        return player;
    }

    private Unit unit(Player player) {
        return engine.commanderOf(player);
    }

    private void cast(Player player, String ability) {
        cast(player, ability, null, null);
    }

    private void cast(Player player, String ability, Unit target) {
        cast(player, ability, target, null);
    }

    private void cast(Player player, String ability, Unit target, Position tile) {
        engine.submit(new Command.Cast(player.getId(), ability, target == null ? null : target.getId(), tile));
        engine.tick();
    }

    private void pass(int ticks) {
        for (int i = 0; i < ticks; i++) engine.tick();
    }
}
