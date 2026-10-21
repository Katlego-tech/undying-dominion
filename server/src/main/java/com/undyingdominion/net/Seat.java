package com.undyingdominion.net;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * A player's place in the world, kept while their socket comes and goes. Its token is a bearer
 * secret: whoever presents it with {@code rejoin} takes the seat back. Changed only under
 * {@link SeatTable}'s lock; the connection may be read without it.
 */
final class Seat {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    final int playerId;
    final String name;
    final String token;

    private volatile ClientConnection connection;
    private long awayUntilNanos;

    private Seat(int playerId, String name, String token, ClientConnection connection) {
        this.playerId = playerId;
        this.name = name;
        this.token = token;
        this.connection = connection;
    }

    /** A new seat for a player who has just joined on {@code connection}, with a fresh token. */
    static Seat connected(int playerId, String name, ClientConnection connection) {
        return new Seat(playerId, name, newToken(), connection);
    }

    /** 16 random bytes, URL-safe Base64 without padding: 22 characters. */
    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    boolean isAway() {
        return connection == null;
    }

    /** The one live socket for this player, or null while they are away. */
    ClientConnection connection() {
        return connection;
    }

    /** Seats {@code next} and returns the connection it replaces, or null if the seat was away. */
    ClientConnection reconnect(ClientConnection next) {
        ClientConnection previous = connection;
        connection = next;
        return previous;
    }

    /** Marks the seat away if {@code closed} is its connection; false (and no change) for a stale socket. */
    boolean drop(ClientConnection closed, long awayUntilNanos) {
        if (connection != closed) return false;
        connection = null;
        this.awayUntilNanos = awayUntilNanos;
        return true;
    }

    boolean expired(long nowNanos) {
        return connection == null && nowNanos - awayUntilNanos >= 0;
    }
}
