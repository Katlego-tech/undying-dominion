package com.undyingdominion.game;

import com.undyingdominion.game.Unit.Order;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Pathfinder;
import com.undyingdominion.world.Position;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The authoritative simulation. Advances one fixed step per {@link #tick()}.
 *
 * <p>Only {@link #submit(Command)} and {@link #allocatePlayerId()} are safe to call from other
 * threads. Everything else belongs to the game loop thread.
 */
public class GameEngine {

    private static final String[] COLORS = {
            "#a78bfa", "#4ade80", "#f87171", "#fbbf24", "#22d3ee", "#f472b6", "#a3e635", "#fb923c"
    };
    private static final UnitType[] NEUTRAL_TYPES = { UnitType.WOLF, UnitType.WOLF, UnitType.KNIGHT };

    private final GameMap map;
    private final GameRules rules;
    private final Random random;
    private final Pathfinder pathfinder;

    private final Queue<Command> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);
    private int nextUnitId = 1;
    private int nextCorpseId = 1;
    private int nextTrapId = 1;
    private long tick;

    private final Map<Integer, Player> players = new LinkedHashMap<>();
    /** Where necromancers rise, or null for anywhere on the map. */
    private int[] startArea;
    private final Map<Integer, Unit> units = new LinkedHashMap<>();
    private final Map<Position, Unit> occupancy = new HashMap<>();
    private final Map<Integer, Corpse> corpses = new LinkedHashMap<>();
    /** Insertion order is age order, so the oldest trap of a player is found first. */
    private final Map<Position, Trap> traps = new LinkedHashMap<>();
    private final List<GameEvent> events = new ArrayList<>();

    public GameEngine(GameMap map, GameRules rules, Random random) {
        this.map = map;
        this.rules = rules;
        this.random = random;
        this.pathfinder = new Pathfinder(map);
    }

    /** Reserves an id for a player who is about to join. Safe from any thread. */
    public int allocatePlayerId() {
        return nextPlayerId.getAndIncrement();
    }

    /** Queues a command for the next tick. Safe from any thread. */
    public void submit(Command command) {
        inbox.add(command);
    }

    /**
     * Lets players in and out of a paused world without advancing it: joins, leaves and restores
     * are applied now, and every other command waits, in order, for the next tick.
     */
    public void admit() {
        List<Command> waiting = new ArrayList<>();
        Command command;
        while ((command = inbox.poll()) != null) {
            if (command instanceof Command.Join || command instanceof Command.Leave || command instanceof Command.Restore) {
                apply(command);
            } else {
                waiting.add(command);
            }
        }
        inbox.addAll(waiting);
    }

    /** Advances the world by one step. */
    public void tick() {
        events.clear();

        Command command;
        while ((command = inbox.poll()) != null) {
            apply(command);
        }

        respawnNecromancers();
        spawnNeutrals();
        regenerateSoul();

        for (Unit unit : new ArrayList<>(units.values())) {
            if (unit.isAlive()) update(unit);
        }

        returnTheRaised();
        reapTheDead();
        decayCorpses();
        tick++;
    }

    // ------------------------------------------------------------------ commands

    private void apply(Command command) {
        if (command instanceof Command.Join join) {
            join(join);
            return;
        }
        Player player = players.get(command.playerId());
        if (player == null) return;

        switch (command) {
            case Command.Leave leave -> leave(player);
            case Command.Restore restore -> restore(player, restore);
            case Command.Summon summon -> summon(player, summon.type());
            case Command.Move move -> move(player, move);
            case Command.Attack attack -> attack(player, attack);
            case Command.Stop stop -> commandable(player, stop.unitIds()).forEach(Unit::orderIdle);
            case Command.Raise raise -> raise(player, raise.corpseId());
            case Command.Explode explode -> explode(player, explode.corpseId());
            case Command.Fuse fuse -> fuse(player, fuse.unitIds());
            case Command.Mend mend -> mend(player, mend.unitIds());
            case Command.SetTrap setTrap -> setTrap(player, setTrap.unitIds());
            case Command.Dismiss dismiss -> dismiss(player, dismiss.unitIds());
            case Command.Join ignored -> { }
        }
    }

    private void join(Command.Join join) {
        if (players.containsKey(join.playerId())) return;
        String color = COLORS[(join.playerId() - 1) % COLORS.length];
        Player player = new Player(join.playerId(), join.name(), color, rules.startingSoul());
        players.put(player.getId(), player);
        spawnNecromancer(player);
        player.notice("Welcome to the Dominion, " + player.getName() + ".");
    }

    private void leave(Player player) {
        players.remove(player.getId());
        traps.values().removeIf(t -> t.ownerId() == player.getId());
        for (Unit unit : units.values()) {
            if (unit.getOwnerId() == player.getId()) unit.collapse();
        }
    }

    private void restore(Player player, Command.Restore restore) {
        player.restoreProgress(restore.level(), restore.xp(), restore.kills(), restore.deaths());
        Unit necromancer = necromancerOf(player);
        if (necromancer == null) return;
        for (UnitType type : restore.legion()) {
            if (legionSize(player) >= player.legionCap()) break;
            if (!placeNear(necromancer, type, player, false)) break;
        }
        for (UnitType type : restore.raised()) {
            if (!placeNear(necromancer, type, player, true)) break;
        }
    }

    private boolean placeNear(Unit necromancer, UnitType type, Player player, boolean raised) {
        Position spot = freeTileNear(necromancer.getPosition(), 4);
        if (spot == null) return false;
        addUnit(type, player.getId(), spot, raised);
        return true;
    }

    /** The types of a player's summoned legion, to carry into another world. */
    public List<UnitType> legionOf(int playerId) {
        return legionTypes(playerId, false);
    }

    /** The types of a player's raised legion, carried apart so they stay raised and outside the cap. */
    public List<UnitType> raisedOf(int playerId) {
        return legionTypes(playerId, true);
    }

    private List<UnitType> legionTypes(int playerId, boolean raised) {
        List<UnitType> legion = new ArrayList<>();
        for (Unit unit : units.values()) {
            if (unit.getOwnerId() == playerId && unit.isAlive() && unit.getType() != UnitType.NECROMANCER
                    && unit.isRaised() == raised) {
                legion.add(unit.getType());
            }
        }
        return legion;
    }

    private void summon(Player player, UnitType type) {
        if (!type.isSummonable()) {
            player.notice(type.label + " cannot be summoned.");
            return;
        }
        Unit necromancer = necromancerOf(player);
        if (necromancer == null) {
            player.notice("The dead cannot summon.");
            return;
        }
        if (legionSize(player) >= player.legionCap()) {
            player.notice("Your legion is full (" + player.legionCap() + ").");
            return;
        }
        if (player.getSoul() < type.summonCost) {
            player.notice("Not enough soul energy for a " + type.label + ".");
            return;
        }
        Position spot = freeTileNear(necromancer.getPosition(), 2);
        if (spot == null) {
            player.notice("No room to summon.");
            return;
        }
        player.spendSoul(type.summonCost);
        Unit unit = addUnit(type, player.getId(), spot, false);
        events.add(GameEvent.at(GameEvent.Kind.SUMMON, unit));
    }

    private void move(Player player, Command.Move move) {
        if (!map.inBounds(move.target())) return;
        List<Unit> movers = commandable(player, move.unitIds());
        if (movers.isEmpty()) return;

        movers.sort(Comparator.comparingInt(u -> u.getPosition().distanceTo(move.target())));
        List<Position> slots = formationSlots(move.target(), movers.size());
        for (int i = 0; i < movers.size(); i++) {
            Position slot = i < slots.size() ? slots.get(i) : move.target();
            movers.get(i).orderMove(slot, move.attackMove());
        }
    }

    private void attack(Player player, Command.Attack attack) {
        Unit target = units.get(attack.targetId());
        if (target == null || !target.isAlive()) return;
        for (Unit unit : commandable(player, attack.unitIds())) {
            if (unit.isHostileTo(target) && unit.getType().canAttack()) {
                unit.orderAttack(target.getId());
            } else {
                unit.orderMove(target.getPosition(), false);
            }
        }
    }

    private void raise(Player player, int corpseId) {
        Corpse corpse = corpses.get(corpseId);
        if (corpse == null) {
            player.notice("That corpse has already gone.");
            return;
        }
        Unit necromancer = necromancerOf(player);
        if (necromancer == null) {
            player.notice("The dead cannot raise the dead.");
            return;
        }
        if (necromancer.getPosition().distanceTo(corpse.position()) > rules.raiseRange()) {
            player.notice("Too far away to raise. Move within " + rules.raiseRange() + " tiles.");
            return;
        }
        if (player.getSoul() < corpse.type().raiseCost) {
            player.notice("Not enough soul energy to raise a " + corpse.type().label + ".");
            return;
        }
        Position spot = freeTileNear(corpse.position(), 2);
        if (spot == null) {
            player.notice("No room to raise it.");
            return;
        }
        player.spendSoul(corpse.type().raiseCost);
        corpses.remove(corpseId);
        Unit unit = addUnit(corpse.type(), player.getId(), spot, true);
        unit.setExpiresAtTick(tick + rules.raisedTicks(player.getLevel()));
        events.add(GameEvent.at(GameEvent.Kind.RAISE, unit));
    }

    /**
     * Corpse Explosion: the necromancer detonates a corpse within raising range, and every enemy within
     * the blast takes a share of the dead creature's life as damage.
     */
    private void explode(Player player, int corpseId) {
        Corpse corpse = corpses.get(corpseId);
        if (corpse == null) {
            player.notice("That corpse has already gone.");
            return;
        }
        Unit necromancer = necromancerOf(player);
        if (necromancer == null) {
            player.notice("The dead cannot detonate the dead.");
            return;
        }
        if (necromancer.getPosition().distanceTo(corpse.position()) > rules.raiseRange()) {
            player.notice("Too far away. Move within " + rules.raiseRange() + " tiles of the corpse.");
            return;
        }
        if (!player.spendSoul(rules.explodeSoulCost())) {
            player.notice("Corpse Explosion needs " + rules.explodeSoulCost() + " soul energy.");
            return;
        }
        corpses.remove(corpseId);
        int damage = Math.max(10, corpse.type().maxHp * rules.explodeDamagePercent() / 100);
        for (Unit unit : units.values()) {
            if (unit.isAlive() && unit.getOwnerId() != player.getId()
                    && unit.getPosition().distanceTo(corpse.position()) <= rules.explodeRadius()) {
                unit.takeDamage(damage, player.getId());
            }
        }
        events.add(new GameEvent(GameEvent.Kind.EXPLOSION, player.getId(), rules.explodeRadius(),
                corpse.position().x(), corpse.position().y(), 0, 0));
    }

    /**
     * Fuses summoned Skeleton Warriors into a Skeleton King. Raised warriors can't take part: the king
     * is summoned and permanent, so fusing the raised would turn units that take no legion room and
     * expire into one that counts and never does.
     */
    private void fuse(Player player, List<Integer> unitIds) {
        List<Unit> selected = ownedUnits(player, unitIds).stream()
                .filter(u -> u.getType() == UnitType.SKELETON_WARRIOR && !u.isRooted())
                .toList();
        List<Unit> warriors = selected.stream().filter(u -> !u.isRaised()).toList();
        if (warriors.size() < rules.fuseCount()) {
            player.notice(warriors.size() < selected.size()
                    ? "Only summoned Skeleton Warriors can be fused. The raised are held only for a while."
                    : "Select at least " + rules.fuseCount() + " Skeleton Warriors to fuse.");
            return;
        }
        Position centre = centroid(warriors);
        List<Unit> chosen = warriors.stream()
                .sorted(Comparator.comparingInt(u -> u.getPosition().distanceTo(centre)))
                .limit(rules.fuseCount())
                .toList();
        if (chosen.stream().anyMatch(u -> u.getPosition().distanceTo(centre) > rules.fuseRadius())) {
            player.notice("The warriors must stand together to fuse.");
            return;
        }
        if (player.getSoul() < rules.fuseSoulCost()) {
            player.notice("Fusion needs " + rules.fuseSoulCost() + " soul energy.");
            return;
        }

        player.spendSoul(rules.fuseSoulCost());
        for (Unit warrior : chosen) {
            removeUnit(warrior);
        }
        Position spot = freeTileNear(centre, rules.fuseRadius() + 1);
        if (spot == null) spot = chosen.get(0).getPosition();
        Unit king = addUnit(UnitType.SKELETON_KING, player.getId(), spot, false);
        events.add(GameEvent.at(GameEvent.Kind.FUSE, king));
    }

    private void mend(Player player, List<Integer> unitIds) {
        for (Unit unit : ownedUnits(player, unitIds)) {
            if (unit.isRooted() || unit.getHp() >= unit.getType().maxHp) continue;
            unit.orderMode(Order.MEND, tick + rules.mendTicks());
        }
    }

    private void setTrap(Player player, List<Integer> unitIds) {
        boolean anyWarden = false;
        for (Unit unit : ownedUnits(player, unitIds)) {
            if (!unit.getType().laysTraps() || unit.isRooted()) continue;
            anyWarden = true;
            if (traps.containsKey(unit.getPosition())) {
                player.notice("There is already a trap here.");
                continue;
            }
            unit.orderMode(Order.SET_TRAP, tick + rules.trapSetTicks());
        }
        if (!anyWarden) player.notice("Only Grave Wardens can lay traps.");
    }

    /**
     * Lets units go from the legion, rooted or not, so a raised creature that can't move doesn't hold
     * a slot for good. They crumble to nothing: no corpse to raise or explode, no soul back, no kill
     * for anyone. The necromancer can't be dismissed.
     */
    private void dismiss(Player player, List<Integer> unitIds) {
        int dismissed = 0;
        for (Unit unit : ownedUnits(player, unitIds)) {
            if (unit.getType() == UnitType.NECROMANCER) continue;
            events.add(GameEvent.at(GameEvent.Kind.DEATH, unit));
            removeUnit(unit);
            dismissed++;
        }
        if (dismissed > 0) player.notice("Dismissed " + dismissed + " from your legion.");
    }

    // ------------------------------------------------------------------ world upkeep

    private void spawnNecromancer(Player player) {
        Position spot = startArea == null ? randomFreeTile(6) : randomFreeTileIn(startArea);
        if (spot == null) spot = randomFreeTile(0);
        if (spot == null) {
            player.setRespawnAtTick(tick + 1);
            return;
        }
        Unit necromancer = addUnit(UnitType.NECROMANCER, player.getId(), spot, false);
        player.setNecromancerId(necromancer.getId());
        player.setRespawnAtTick(-1);
        events.add(GameEvent.at(GameEvent.Kind.SUMMON, necromancer));
    }

    private void respawnNecromancers() {
        for (Player player : players.values()) {
            if (player.getNecromancerId() == null && player.getRespawnAtTick() >= 0
                    && tick >= player.getRespawnAtTick()) {
                spawnNecromancer(player);
            }
        }
    }

    private void spawnNeutrals() {
        if (rules.maxNeutrals() <= 0 || tick % rules.neutralSpawnIntervalTicks() != 0) return;
        long wild = units.values().stream().filter(u -> u.getOwnerId() == Unit.NEUTRAL).count();
        if (wild >= rules.maxNeutrals()) return;

        Position spot = randomFreeTile(rules.neutralSpawnClearance());
        if (spot == null) return;
        UnitType type = NEUTRAL_TYPES[random.nextInt(NEUTRAL_TYPES.length)];
        Unit unit = addUnit(type, Unit.NEUTRAL, spot, false);
        unit.setNextWanderTick(tick + random.nextInt(60));
    }

    private void regenerateSoul() {
        if (tick % rules.ticksPerSecond() != 0) return;
        for (Player player : players.values()) {
            if (player.getNecromancerId() != null) player.addSoul(rules.soulRegenPerSecond());
        }
    }

    /** Removes everything that died this tick, pays out for kills and leaves corpses behind. */
    private void reapTheDead() {
        List<Unit> dead = units.values().stream().filter(u -> !u.isAlive()).toList();
        while (!dead.isEmpty()) {
            for (Unit unit : dead) {
                removeUnit(unit);
                events.add(GameEvent.at(GameEvent.Kind.DEATH, unit));
                creditKill(unit);

                if (unit.getType() == UnitType.NECROMANCER) {
                    necromancerFell(unit);
                } else if (unit.getType().isRaisable()) {
                    leaveCorpse(unit);
                }
            }
            // A fallen necromancer takes its legion with it, so go round again.
            dead = units.values().stream().filter(u -> !u.isAlive()).toList();
        }
    }

    private void creditKill(Unit victim) {
        int killerId = victim.getLastHitByOwner();
        if (killerId == Unit.NEUTRAL || killerId == victim.getOwnerId()) return;
        Player killer = players.get(killerId);
        if (killer == null) return;

        killer.addSoul(victim.getType().soulValue);
        killer.addKill();
        if (killer.addXp(victim.getType().xpValue)) {
            events.add(GameEvent.levelUp(killer));
            killer.notice("You reached level " + killer.getLevel() + ". Legion cap is now " + killer.legionCap() + ".");
        }
        if (victim.getType() == UnitType.NECROMANCER) {
            Player victimOwner = players.get(victim.getOwnerId());
            if (victimOwner != null) killer.notice("You slew " + victimOwner.getName() + ".");
        }
    }

    private void necromancerFell(Unit necromancer) {
        Player owner = players.get(necromancer.getOwnerId());
        if (owner == null) return;
        owner.setNecromancerId(null);
        owner.addDeath();
        owner.setSoul(owner.getSoul() / 2);
        owner.setRespawnAtTick(tick + rules.respawnTicks());
        owner.notice("You have fallen. Your legion crumbles to bone.");
        for (Unit unit : units.values()) {
            if (unit.getOwnerId() == owner.getId()) unit.collapse();
        }
    }

    /**
     * Raised units last only a while, longer for a stronger necromancer (GameRules#raisedTicks), then
     * return to the dead: no corpse, no credit, as with a dismissal. One without a time yet (from an
     * older save, or carried into a new chapter) is given a full one now.
     */
    private void returnTheRaised() {
        for (Unit unit : new ArrayList<>(units.values())) {
            if (!unit.isRaised() || !unit.isAlive()) continue;
            Player owner = players.get(unit.getOwnerId());
            if (unit.getExpiresAtTick() == Unit.NO_EXPIRY) {
                if (owner != null) unit.setExpiresAtTick(tick + rules.raisedTicks(owner.getLevel()));
                continue;
            }
            if (tick < unit.getExpiresAtTick()) continue;
            events.add(GameEvent.at(GameEvent.Kind.DEATH, unit));
            removeUnit(unit);
            if (owner != null) owner.notice("Your raised " + unit.getType().label + " returns to the dead.");
        }
    }

    /** The dead stay until raised or exploded. Past the cap the oldest crumble, the map keeping the newest. */
    private void leaveCorpse(Unit unit) {
        long decaysAt = rules.corpseDecayTicks() > 0 ? tick + rules.corpseDecayTicks() : Corpse.NEVER;
        Corpse corpse = new Corpse(nextCorpseId++, unit.getType(), unit.getPosition(), decaysAt);
        corpses.put(corpse.id(), corpse);
        while (corpses.size() > rules.maxCorpses()) {
            corpses.remove(corpses.keySet().iterator().next());  // insertion order: the oldest first
        }
    }

    private void decayCorpses() {
        corpses.values().removeIf(c -> c.decaysAtTick() <= tick);
    }

    // ------------------------------------------------------------------ unit behaviour

    private void update(Unit unit) {
        if (unit.isRooted()) {
            if (tick >= unit.getModeEndsTick()) finishMode(unit);
            return;
        }

        Unit target = unit.getTargetId() == null ? null : units.get(unit.getTargetId());
        if (target != null && !target.isAlive()) target = null;
        if (target != null && unit.isAutoTarget()
                && unit.getPosition().distanceTo(target.getPosition()) > unit.getType().aggroRange + 3) {
            target = null;
        }
        if (target == null && unit.getTargetId() != null) {
            if (unit.getOrder() == Order.ATTACK) {
                unit.orderIdle();
            } else {
                unit.clearTarget();
                unit.clearPath();
            }
        }

        switch (unit.getOrder()) {
            case IDLE -> {
                if (unit.getOwnerId() != Unit.NEUTRAL) {
                    guard(unit, target);
                    return;
                }
                if (target == null) target = acquire(unit);
                if (target != null) {
                    engage(unit, target, unit.getType().chasesWhenIdle);
                } else {
                    wander(unit);
                }
            }
            case MOVE -> followOrder(unit);
            case ATTACK_MOVE -> {
                if (target == null) target = acquire(unit);
                if (target != null) {
                    engage(unit, target, true);
                } else {
                    followOrder(unit);
                }
            }
            case ATTACK -> engage(unit, target, true);
            case MEND, SET_TRAP -> { }
        }
    }

    /**
     * A player's idle unit holds its post. It fights any enemy within its aggro range, but only while
     * that enemy is also within aggro range of the post. When the fight is over it walks back, so a
     * unit with long senses (a raised hunter) can't chain from fight to fight across the map and die
     * alone.
     */
    private void guard(Unit unit, Unit target) {
        Position post = unit.getPost();
        if (post == null) {
            post = unit.getPosition();
            unit.setPost(post);
        }
        int reach = unit.getType().aggroRange;
        if (target != null && target.getPosition().distanceTo(post) > reach) {
            unit.clearTarget();
            unit.clearPath();
            target = null;
        }
        if (target == null) target = acquire(unit, post);
        if (target != null) {
            engage(unit, target, unit.getType().chasesWhenIdle);
        } else if (unit.getPosition().distanceTo(post) > 1 && !stepToward(unit, post, false)) {
            unit.setPost(unit.getPosition());  // the way back is gone: guard here instead
        }
    }

    private void finishMode(Unit unit) {
        if (unit.getOrder() == Order.MEND) {
            unit.healFully();
            unit.orderIdle();
            events.add(GameEvent.at(GameEvent.Kind.MEND, unit));
        } else if (unit.getOrder() == Order.SET_TRAP) {
            unit.orderIdle();
            layTrap(unit);
        }
    }

    /**
     * Lays a trap underfoot and steps forward off it. If the way ahead is blocked, the warden is
     * left standing on its own trap and springs it, just as a robot that could not move off its
     * mine stepped on it.
     */
    private void layTrap(Unit warden) {
        Position here = warden.getPosition();
        if (traps.containsKey(here)) return;

        List<Trap> own = traps.values().stream().filter(t -> t.ownerId() == warden.getOwnerId()).toList();
        if (own.size() >= rules.maxTrapsPerPlayer()) {
            traps.remove(own.get(0).position());
        }
        Trap trap = new Trap(nextTrapId++, warden.getOwnerId(), here);
        traps.put(here, trap);
        events.add(GameEvent.at(GameEvent.Kind.TRAP_SET, warden));

        Position ahead = warden.ahead();
        if (map.inBounds(ahead) && map.isWalkable(ahead) && !occupancy.containsKey(ahead)) {
            moveUnit(warden, ahead);
        } else {
            springTrap(warden, trap);
        }
    }

    private void springTrap(Unit victim, Trap trap) {
        traps.remove(trap.position());
        victim.takeDamage(rules.trapDamage(), trap.ownerId());
        events.add(GameEvent.at(GameEvent.Kind.TRAP_SPRUNG, victim, trap.position()));
    }

    /** Picks the nearest visible enemy within aggro range, and remembers it as the unit's target. */
    private Unit acquire(Unit unit) {
        return acquire(unit, null);
    }

    /** As above, and when {@code post} is given, only an enemy also within aggro range of it. */
    private Unit acquire(Unit unit, Position post) {
        if (!unit.getType().canAttack()) return null;
        int aggro = unit.getType().aggroRange;
        Unit best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit other : units.values()) {
            if (!other.isAlive() || !unit.isHostileTo(other)) continue;
            int distance = unit.getPosition().distanceTo(other.getPosition());
            if (distance > aggro || distance >= bestDistance) continue;
            if (post != null && post.distanceTo(other.getPosition()) > aggro) continue;
            if (!map.hasLineOfSight(unit.getPosition(), other.getPosition())) continue;
            best = other;
            bestDistance = distance;
        }
        if (best != null) unit.acquire(best.getId());
        return best;
    }

    private void engage(Unit unit, Unit target, boolean chase) {
        int distance = unit.getPosition().distanceTo(target.getPosition());
        boolean inRange = distance <= unit.getType().range
                && (distance <= 1 || map.hasLineOfSight(unit.getPosition(), target.getPosition()));
        if (inRange) {
            unit.clearPath();
            if (tick >= unit.getAttackReadyTick()) strike(unit, target);
            return;
        }
        if (chase) stepToward(unit, target.getPosition(), true);
    }

    private void strike(Unit attacker, Unit target) {
        UnitType type = attacker.getType();
        events.add(GameEvent.attack(attacker, target));
        target.takeDamage(type.damage, attacker.getOwnerId());

        if (type.splashRadius > 0) {
            for (Unit other : units.values()) {
                if (other == target || !other.isAlive() || !attacker.isHostileTo(other)) continue;
                if (other.getPosition().distanceTo(target.getPosition()) <= type.splashRadius) {
                    other.takeDamage(type.damage / 2, attacker.getOwnerId());
                }
            }
        }
        attacker.setAttackReadyTick(tick + type.attackCooldown);
    }

    private void followOrder(Unit unit) {
        Position destination = unit.getDestination();
        if (destination == null || unit.getPosition().equals(destination)) {
            unit.orderIdle();
            return;
        }
        if (!stepToward(unit, destination, false)) {
            unit.orderIdle();
        }
    }

    private void wander(Unit unit) {
        if (unit.getType().stationary || tick < unit.getNextWanderTick()) return;
        unit.setNextWanderTick(tick + 50 + random.nextInt(100));
        Position here = unit.getPosition();
        Position spot = here.offset(random.nextInt(9) - 4, random.nextInt(9) - 4);
        if (map.inBounds(spot) && map.isWalkable(spot) && !occupancy.containsKey(spot)) {
            unit.orderMove(spot, true);
        }
    }

    /**
     * Takes at most one step towards {@code goal}. Units route around their own side's traps;
     * enemy traps are invisible to the pathfinder, which is the point of them.
     *
     * @return false when the unit has given up: no route exists, or it is next to a goal tile
     *         that someone else is standing on
     */
    private boolean stepToward(Unit unit, Position goal, boolean chasing) {
        if (unit.getType().stationary) return false;
        if (tick < unit.getMoveReadyTick()) return true;

        Predicate<Position> ownTrap = p -> {
            Trap trap = traps.get(p);
            return trap != null && trap.ownerId() == unit.getOwnerId();
        };

        boolean goalMoved = !goal.equals(unit.getPathGoal());
        boolean due = tick - unit.getPathComputedTick() >= 3;
        if (unit.path().isEmpty() ? (goalMoved || due) : (goalMoved && due)) {
            unit.setPath(pathfinder.findPath(unit.getPosition(), goal, ownTrap), goal, tick);
        }
        Position next = unit.path().peekFirst();
        if (next == null) return false;

        Unit blocker = occupancy.get(next);
        if (blocker != null && blocker != unit) {
            unit.incrementBlocked();
            if (unit.getBlockedTicks() < 3) return true;
            if (!chasing && next.equals(goal)) return false;

            List<Position> detour = pathfinder.findPath(unit.getPosition(), goal, p -> {
                Unit other = occupancy.get(p);
                return (other != null && other != unit) || ownTrap.test(p);
            });
            unit.setPath(detour, goal, tick);
            return !detour.isEmpty() || chasing;
        }

        unit.path().pollFirst();
        unit.resetBlocked();
        moveUnit(unit, next);
        return true;
    }

    /** Moves a unit one tile, charges its move cooldown, and springs any trap it lands on. */
    private void moveUnit(Unit unit, Position next) {
        occupancy.remove(unit.getPosition());
        unit.stepTo(next);
        occupancy.put(next, unit);
        unit.setMoveReadyTick(tick + (long) unit.getType().moveCooldown * map.terrainAt(next).moveCostMultiplier);

        Trap trap = traps.get(next);
        if (trap != null) springTrap(unit, trap);
    }

    // ------------------------------------------------------------------ helpers

    private Unit addUnit(UnitType type, int ownerId, Position position, boolean raised) {
        Unit unit = new Unit(nextUnitId++, type, ownerId, position, raised);
        units.put(unit.getId(), unit);
        occupancy.put(position, unit);
        return unit;
    }

    private void removeUnit(Unit unit) {
        units.remove(unit.getId());
        if (occupancy.get(unit.getPosition()) == unit) occupancy.remove(unit.getPosition());
    }

    private Unit necromancerOf(Player player) {
        Integer id = player.getNecromancerId();
        if (id == null) return null;
        Unit necromancer = units.get(id);
        return necromancer != null && necromancer.isAlive() ? necromancer : null;
    }

    /** Summoned legion members: what the legion cap limits. Raised ones don't count against it. */
    private int legionSize(Player player) {
        return countLegion(player, false);
    }

    private int countLegion(Player player, boolean raised) {
        int size = 0;
        for (Unit unit : units.values()) {
            if (unit.getOwnerId() == player.getId() && unit.getType() != UnitType.NECROMANCER && unit.isAlive()
                    && unit.isRaised() == raised) {
                size++;
            }
        }
        return size;
    }

    private List<Unit> ownedUnits(Player player, List<Integer> ids) {
        List<Unit> owned = new ArrayList<>();
        for (Integer id : ids) {
            Unit unit = units.get(id);
            if (unit != null && unit.isAlive() && unit.getOwnerId() == player.getId()) owned.add(unit);
        }
        return owned;
    }

    /**
     * Owned units that will take an order. Units that are mending or laying a trap are committed,
     * as a Robot Worlds robot could not move while it repaired or set a mine.
     */
    private List<Unit> commandable(Player player, List<Integer> ids) {
        List<Unit> units = ownedUnits(player, ids);
        units.removeIf(Unit::isRooted);
        return units;
    }

    private static Position centroid(List<Unit> group) {
        double x = 0, y = 0;
        for (Unit unit : group) {
            x += unit.getPosition().x();
            y += unit.getPosition().y();
        }
        return new Position((int) Math.round(x / group.size()), (int) Math.round(y / group.size()));
    }

    /** The nearest walkable, empty tile within {@code radius} of {@code centre}, searching ring by ring. */
    private Position freeTileNear(Position centre, int radius) {
        for (Position p : rings(centre, radius)) {
            if (map.isWalkable(p) && !occupancy.containsKey(p)) return p;
        }
        return null;
    }

    /** Walkable tiles around {@code target}, nearest first, one per unit in a group move. */
    private List<Position> formationSlots(Position target, int count) {
        List<Position> slots = new ArrayList<>();
        for (Position p : rings(target, 8)) {
            if (map.isWalkable(p)) slots.add(p);
            if (slots.size() == count) break;
        }
        return slots;
    }

    private List<Position> rings(Position centre, int radius) {
        List<Position> out = new ArrayList<>();
        for (int r = 0; r <= radius; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    Position p = centre.offset(dx, dy);
                    if (map.inBounds(p)) out.add(p);
                }
            }
        }
        return out;
    }

    private Position randomFreeTile(int clearanceFromNecromancers) {
        for (int attempt = 0; attempt < 500; attempt++) {
            Position p = new Position(random.nextInt(map.getWidth()), random.nextInt(map.getHeight()));
            if (!map.isWalkable(p) || occupancy.containsKey(p) || traps.containsKey(p)) continue;
            if (clearanceFromNecromancers > 0 && nearANecromancer(p, clearanceFromNecromancers)) continue;
            return p;
        }
        return null;
    }

    private Position randomFreeTileIn(int[] area) {
        for (int attempt = 0; attempt < 300; attempt++) {
            Position p = new Position(area[0] + random.nextInt(area[2] - area[0] + 1),
                    area[1] + random.nextInt(area[3] - area[1] + 1));
            if (isFree(p)) return p;
        }
        return null;
    }

    private boolean nearANecromancer(Position p, int distance) {
        for (Player player : players.values()) {
            Unit necromancer = necromancerOf(player);
            if (necromancer != null && necromancer.getPosition().distanceTo(p) < distance) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ saving

    /** Everything needed to rebuild this world, between ticks. Loop thread only. */
    public EngineState capture() {
        return new EngineState(tick, nextPlayerId.get(), nextUnitId, nextCorpseId, nextTrapId,
                startArea == null ? null : List.of(startArea[0], startArea[1], startArea[2], startArea[3]),
                players.values().stream().map(Player::capture).toList(),
                units.values().stream().map(Unit::capture).toList(),
                List.copyOf(corpses.values()),
                List.copyOf(traps.values()));
    }

    /** A world carrying on from a capture, on the map and rules it was captured with. */
    public static GameEngine rebuild(GameMap map, GameRules rules, Random random, EngineState state) {
        GameEngine engine = new GameEngine(map, rules, random);
        engine.tick = state.tick();
        engine.nextPlayerId.set(state.nextPlayerId());
        engine.nextUnitId = state.nextUnitId();
        engine.nextCorpseId = state.nextCorpseId();
        engine.nextTrapId = state.nextTrapId();
        List<Integer> area = state.startArea();
        if (area != null) engine.startArea = new int[]{area.get(0), area.get(1), area.get(2), area.get(3)};
        for (PlayerState player : state.players()) engine.players.put(player.id(), Player.restore(player));
        // Placed in the order they were created, as addUnit and stepTo would have filled the occupancy.
        for (UnitState saved : state.units()) {
            Unit unit = Unit.restore(saved);
            engine.units.put(unit.getId(), unit);
            engine.occupancy.put(unit.getPosition(), unit);
        }
        for (Corpse corpse : state.corpses()) engine.corpses.put(corpse.id(), corpse);
        for (Trap trap : state.traps()) engine.traps.put(trap.position(), trap);
        return engine;
    }

    // ------------------------------------------------------------------ read access for snapshots and tests

    public long getTick()                 { return tick; }
    public GameMap getMap()               { return map; }
    public GameRules getRules()           { return rules; }
    public Collection<Player> getPlayers(){ return players.values(); }
    public Player getPlayer(int id)       { return players.get(id); }
    public Collection<Unit> getUnits()    { return units.values(); }
    public Unit getUnit(int id)           { return units.get(id); }
    public Collection<Corpse> getCorpses(){ return corpses.values(); }
    public Collection<Trap> getTraps()    { return traps.values(); }
    /** What happened during the most recent tick. */
    public List<GameEvent> getEvents()    { return events; }

    /** Summoned legion members, counted against the legion cap. */
    public int legionSizeOf(Player player) {
        return legionSize(player);
    }

    /** Legion members raised from corpses, which take no room under the cap. */
    public int raisedSizeOf(Player player) {
        return countLegion(player, true);
    }

    /** Finds a player by name, ignoring case, as Robot Worlds looked up robots. */
    public Player findPlayer(String name) {
        for (Player player : players.values()) {
            if (player.getName().equalsIgnoreCase(name)) return player;
        }
        return null;
    }

    /** Whether a unit could be placed here: on the map, walkable, empty and not trapped. */
    public boolean isFree(Position p) {
        return map.inBounds(p) && map.isWalkable(p) && !occupancy.containsKey(p) && !traps.containsKey(p);
    }

    /** Makes necromancers rise inside this rectangle, as a chapter's starting ground. */
    public void setStartArea(int x1, int y1, int x2, int y2) {
        startArea = new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)};
    }

    /** Makes sure ids handed out from now on are at least {@code next}. Safe from any thread. */
    public void reservePlayerIds(int next) {
        nextPlayerId.accumulateAndGet(next, Math::max);
    }

    public int peekNextPlayerId() {
        return nextPlayerId.get();
    }

    /** Puts a unit down directly. For tests and scripted scenarios. */
    public Unit spawn(UnitType type, int ownerId, Position position) {
        if (occupancy.containsKey(position) || !map.isWalkable(position)) {
            throw new IllegalArgumentException("Cannot place a unit at " + position);
        }
        return addUnit(type, ownerId, position, false);
    }
}
