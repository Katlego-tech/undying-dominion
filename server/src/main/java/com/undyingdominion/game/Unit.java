package com.undyingdominion.game;

import com.undyingdominion.world.Position;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** One body on the battlefield: a necromancer, a member of a legion, or a wild creature. */
public class Unit {

    /**
     * What the unit is doing. MEND and SET_TRAP are Robot Worlds' REPAIR and SETMINE modes:
     * the unit is rooted until the mode ends.
     */
    public enum Order { IDLE, MOVE, ATTACK_MOVE, ATTACK, MEND, SET_TRAP }

    /** Owner id of wild creatures. Player ids start at 1. */
    public static final int NEUTRAL = 0;

    private final int id;
    private final UnitType type;
    private final int ownerId;
    /** Raised from a corpse rather than summoned; a raised wolf is an undead wolf. */
    private final boolean raised;

    private Position position;
    /** The way the unit last stepped. Like a launched robot, it starts facing north. */
    private int facingX = 0, facingY = -1;
    private int hp;

    private Order order = Order.IDLE;
    private Position destination;
    private Integer targetId;
    /** True when the current target was picked by the unit itself rather than ordered. */
    private boolean autoTarget;
    private long modeEndsTick;

    private final Deque<Position> path = new ArrayDeque<>();
    private Position pathGoal;
    private long pathComputedTick = Long.MIN_VALUE;
    private int blockedTicks;

    private long moveReadyTick;
    private long attackReadyTick;
    private long nextWanderTick;
    /** When a raised unit returns to the dead; {@link #NO_EXPIRY} for one that doesn't, or hasn't been timed yet. */
    private long expiresAtTick = NO_EXPIRY;
    public static final long NO_EXPIRY = -1;

    private int lastHitByOwner = NEUTRAL;
    private boolean hasBeenHit;

    public Unit(int id, UnitType type, int ownerId, Position position, boolean raised) {
        this.id = id;
        this.type = type;
        this.ownerId = ownerId;
        this.position = position;
        this.raised = raised;
        this.hp = type.maxHp;
    }

    public boolean isAlive() { return hp > 0; }

    /** Everyone is hostile to everyone with a different owner. Wild creatures share one owner. */
    public boolean isHostileTo(Unit other) {
        return other.ownerId != ownerId;
    }

    /**
     * Takes damage and remembers who dealt it, so the kill can be credited. A unit setting a trap
     * has its guard down and takes double, as a robot's shields drop while it sets a mine.
     */
    public void takeDamage(int amount, int attackerOwner) {
        int dealt = order == Order.SET_TRAP ? amount * 2 : amount;
        hp = Math.max(0, hp - dealt);
        lastHitByOwner = attackerOwner;
        hasBeenHit = true;
    }

    /** Drops the unit without crediting anyone, e.g. when its necromancer falls. */
    public void collapse() {
        hp = 0;
        hasBeenHit = false;
    }

    public void healFully() {
        hp = type.maxHp;
    }

    // --- Orders ---

    /** Stops the unit where it stands, which becomes its post (see {@link #getPost}). */
    public void orderIdle() {
        order = Order.IDLE;
        destination = position;
        clearTarget();
        clearPath();
    }

    public void orderMove(Position destination, boolean attackMove) {
        this.order = attackMove ? Order.ATTACK_MOVE : Order.MOVE;
        this.destination = destination;
        clearTarget();
        clearPath();
    }

    public void orderAttack(int targetId) {
        this.order = Order.ATTACK;
        this.destination = null;
        this.targetId = targetId;
        this.autoTarget = false;
        clearPath();
    }

    /** Roots the unit in MEND or SET_TRAP until {@code endsTick}. */
    public void orderMode(Order mode, long endsTick) {
        orderIdle();
        this.order = mode;
        this.modeEndsTick = endsTick;
    }

    public boolean isRooted() {
        return order == Order.MEND || order == Order.SET_TRAP;
    }

    public void acquire(int targetId) {
        this.targetId = targetId;
        this.autoTarget = true;
    }

    public void clearTarget() {
        targetId = null;
        autoTarget = false;
    }

    // --- Movement ---

    /** Moves one tile and turns to face the way it went. */
    public void stepTo(Position next) {
        facingX = Integer.signum(next.x() - position.x());
        facingY = Integer.signum(next.y() - position.y());
        position = next;
    }

    /** The tile directly ahead. */
    public Position ahead() {
        return position.offset(facingX, facingY);
    }

    // --- Pathing ---

    public void setPath(List<Position> steps, Position goal, long tick) {
        path.clear();
        path.addAll(steps);
        pathGoal = goal;
        pathComputedTick = tick;
        blockedTicks = 0;
    }

    public void clearPath() {
        path.clear();
        pathGoal = null;
        blockedTicks = 0;
    }

    public Deque<Position> path() { return path; }

    // --- Saving ---

    public UnitState capture() {
        return new UnitState(id, type.name(), ownerId, raised, position, facingX, facingY, hp, order, destination,
                targetId, autoTarget, modeEndsTick, List.copyOf(path), pathGoal, pathComputedTick, blockedTicks,
                moveReadyTick, attackReadyTick, nextWanderTick, lastHitByOwner, hasBeenHit, expiresAtTick);
    }

    static Unit restore(UnitState state) {
        Unit unit = new Unit(state.id(), UnitType.valueOf(state.type()), state.ownerId(), state.position(), state.raised());
        unit.facingX = state.facingX();
        unit.facingY = state.facingY();
        unit.hp = state.hp();
        unit.order = state.order();
        unit.destination = state.destination();
        unit.targetId = state.targetId();
        unit.autoTarget = state.autoTarget();
        unit.modeEndsTick = state.modeEndsTick();
        unit.path.addAll(state.path());
        unit.pathGoal = state.pathGoal();
        unit.pathComputedTick = state.pathComputedTick();
        unit.blockedTicks = state.blockedTicks();
        unit.moveReadyTick = state.moveReadyTick();
        unit.attackReadyTick = state.attackReadyTick();
        unit.nextWanderTick = state.nextWanderTick();
        unit.lastHitByOwner = state.lastHitByOwner();
        unit.hasBeenHit = state.hasBeenHit();
        unit.expiresAtTick = state.expiresAtTick();
        return unit;
    }

    // --- Accessors ---

    public int getId()              { return id; }
    public UnitType getType()       { return type; }
    public int getOwnerId()         { return ownerId; }
    public boolean isRaised()       { return raised; }
    public Position getPosition()   { return position; }
    public int getHp()              { return hp; }
    public Order getOrder()         { return order; }
    public long getModeEndsTick()   { return modeEndsTick; }
    public Position getDestination(){ return destination; }

    /**
     * Where an idle unit was left: it fights what comes near, but only near here, and walks back after.
     * Kept in the destination field, which an idle unit has no other use for, so saves carry it.
     * Null for a unit idle since before posts existed; {@link #setPost} gives it one.
     */
    public Position getPost()       { return order == Order.IDLE ? destination : null; }

    public void setPost(Position post) {
        if (order == Order.IDLE) destination = post;
    }
    public Integer getTargetId()    { return targetId; }
    public boolean isAutoTarget()   { return autoTarget; }
    public Position getPathGoal()   { return pathGoal; }
    public long getPathComputedTick() { return pathComputedTick; }
    public int getBlockedTicks()    { return blockedTicks; }
    public void incrementBlocked()  { blockedTicks++; }
    public void resetBlocked()      { blockedTicks = 0; }
    public long getMoveReadyTick()  { return moveReadyTick; }
    public void setMoveReadyTick(long t)   { moveReadyTick = t; }
    public long getAttackReadyTick(){ return attackReadyTick; }
    public void setAttackReadyTick(long t) { attackReadyTick = t; }
    public long getNextWanderTick() { return nextWanderTick; }
    public long getExpiresAtTick()  { return expiresAtTick; }
    public void setExpiresAtTick(long t)  { expiresAtTick = t; }
    public void setNextWanderTick(long t)  { nextWanderTick = t; }
    /** Owner of whoever struck the killing blow, if the unit died in combat. */
    public int getLastHitByOwner()  { return hasBeenHit ? lastHitByOwner : NEUTRAL; }
}
