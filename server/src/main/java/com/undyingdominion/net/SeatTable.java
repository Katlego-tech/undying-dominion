package com.undyingdominion.net;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Every joined player's seat, connected or away, and the names they hold. The table is its own
 * lock, and every change happens under it, so a join, a rejoin, a drop, an expiry and a chapter
 * change each see the whole table at once. Reading one seat, or iterating them all, needs no lock.
 *
 * <p>Expiry is wall time from {@code clock}. A seat whose grace has run out is removed by the loop's
 * {@link #expire}, and also by the next join or rejoin, so nobody can take back an expired seat in
 * the moment before the loop gets to it.
 */
final class SeatTable {

    static final String SESSION_ENDED = "That session has ended. Join again";

    /** A join that took a seat, and whether it was a resumed game's held seat. */
    record Joined(Seat seat, boolean wasHeld) {}

    /** A rejoin that took a seat back, and the socket it took it from, if the seat wasn't away. */
    record Rejoined(Seat seat, ClientConnection replaced) {}

    private final int maxPlayers;
    private final long graceNanos;
    private final LongSupplier clock;
    private final Consumer<Seat> onRemoved;

    private final Map<Integer, Seat> byId = new ConcurrentHashMap<>();
    private final Map<String, Seat> byToken = new HashMap<>();
    /** Lower-cased names, away players' included. */
    private final Map<String, Seat> byName = new HashMap<>();
    private Integer heldId;
    private String heldName;

    /**
     * {@code clock} gives nanoseconds, like {@link System#nanoTime}. {@code onRemoved} hears of every
     * removed seat, under the lock, whatever removed it.
     */
    SeatTable(int maxPlayers, Duration grace, LongSupplier clock, Consumer<Seat> onRemoved) {
        this.maxPlayers = maxPlayers;
        this.graceNanos = grace.toNanos();
        this.clock = clock;
        this.onRemoved = onRemoved;
    }

    /** Keeps a resumed game's player for whoever joins first. */
    synchronized void hold(int playerId, String name) {
        heldId = playerId;
        heldName = name;
    }

    /**
     * Seats a player who has just joined on {@code connection}, or the held player if there is one.
     * {@code onSeated} runs under the lock before the seat is visible to the loop, so whatever it
     * queues reaches the socket before any state. Returns null if the socket closed first.
     */
    synchronized Joined join(ClientConnection connection, String name, IntSupplier newPlayerId,
                             Consumer<Seat> onSeated) throws ProtocolException {
        if (!connection.isOpen()) return null;
        expireLocked();
        boolean wasHeld = heldId != null;
        String seatName = wasHeld ? heldName : name;
        String key = seatName.toLowerCase();
        if (byName.containsKey(key)) throw new ProtocolException("Too many of you in this world");
        if (byName.size() >= maxPlayers) throw new ProtocolException("No more space in this world");

        Seat seat = Seat.connected(wasHeld ? heldId : newPlayerId.getAsInt(), seatName, connection);
        heldId = null;
        heldName = null;
        connection.joined(seat.playerId, seat.name);
        onSeated.accept(seat);
        byName.put(key, seat);
        byToken.put(seat.token, seat);
        byId.put(seat.playerId, seat);
        return new Joined(seat, wasHeld);
    }

    /**
     * Hands the seat with this token to {@code connection}. {@code onSeated} runs under the lock
     * before the hand-over, as for a join. Returns null if the socket closed first.
     */
    synchronized Rejoined rejoin(ClientConnection connection, String token, Consumer<Seat> onSeated)
            throws ProtocolException {
        if (!connection.isOpen()) return null;
        expireLocked();
        Seat seat = byToken.get(token);
        if (seat == null) throw new ProtocolException(SESSION_ENDED);
        connection.joined(seat.playerId, seat.name);
        onSeated.accept(seat);
        return new Rejoined(seat, seat.reconnect(connection));
    }

    /**
     * Marks {@code connection}'s seat away, if it is still that seat's socket. False for a socket
     * that never joined, was taken over, or whose seat is already gone.
     */
    synchronized boolean drop(ClientConnection connection) {
        Integer playerId = connection.getPlayerId();
        Seat seat = playerId == null ? null : byId.get(playerId);
        return seat != null && seat.drop(connection, clock.getAsLong() + graceNanos);
    }

    /** Removes the player's seat at once, away or not. False if they have none. */
    synchronized boolean remove(int playerId) {
        Seat seat = byId.get(playerId);
        return seat != null && removeLocked(seat);
    }

    /** Removes every seat whose grace has run out. */
    synchronized void expire() {
        expireLocked();
    }

    /** Runs {@code task} with the table held still, as a chapter change does while it moves every seat. */
    synchronized void withLock(Runnable task) {
        task.run();
    }

    Seat get(int playerId) {
        return byId.get(playerId);
    }

    /** Every seat, connected or away. Safe to iterate while seats come and go. */
    Collection<Seat> all() {
        return byId.values();
    }

    int size() {
        return byId.size();
    }

    boolean everyoneAway() {
        return !byId.isEmpty() && byId.values().stream().allMatch(Seat::isAway);
    }

    /** Whether {@code connection} is the live socket of this player's seat. */
    boolean isSeated(int playerId, ClientConnection connection) {
        Seat seat = byId.get(playerId);
        return seat != null && seat.connection() == connection;
    }

    long graceSeconds() {
        return Duration.ofNanos(graceNanos).toSeconds();
    }

    private void expireLocked() {
        long now = clock.getAsLong();
        List<Seat> expired = new ArrayList<>();
        for (Seat seat : byId.values()) {
            if (seat.expired(now)) expired.add(seat);
        }
        expired.forEach(this::removeLocked);
    }

    private boolean removeLocked(Seat seat) {
        if (!byId.remove(seat.playerId, seat)) return false;
        byToken.remove(seat.token);
        byName.remove(seat.name.toLowerCase());
        onRemoved.accept(seat);
        return true;
    }
}
