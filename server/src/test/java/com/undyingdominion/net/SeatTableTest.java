package com.undyingdominion.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seat table on its own: joins, drops, rejoins, takeovers and expiry, on a clock the test moves. */
class SeatTableTest {

    private final AtomicLong clock = new AtomicLong(42);
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<Seat> removed = new ArrayList<>();
    private final SeatTable table = new SeatTable(2, Duration.ofSeconds(30), clock::get, removed::add);

    @Test
    void aJoinSeatsThePlayerAfterItsWelcomeIsQueued() throws Exception {
        ClientConnection vesper = socket();
        SeatTable.Joined joined = table.join(vesper, "Vesper", nextId::getAndIncrement,
                seat -> assertNull(table.get(seat.playerId), "not visible to the loop until greeted"));

        assertEquals(1, joined.seat().playerId);
        assertFalse(joined.wasHeld());
        assertSame(joined.seat(), table.get(1));
        assertEquals(1, vesper.getPlayerId());
        assertTrue(table.isSeated(1, vesper));
    }

    @Test
    void namesAreTakenCaseBlindAndTheTableFills() throws Exception {
        table.join(socket(), "Vesper", nextId::getAndIncrement, seat -> { });

        ProtocolException taken = assertThrows(ProtocolException.class,
                () -> table.join(socket(), "VESPER", nextId::getAndIncrement, seat -> { }));
        assertEquals("Too many of you in this world", taken.getMessage());

        table.join(socket(), "Corvin", nextId::getAndIncrement, seat -> { });
        ProtocolException full = assertThrows(ProtocolException.class,
                () -> table.join(socket(), "Ilsa", nextId::getAndIncrement, seat -> { }));
        assertEquals("No more space in this world", full.getMessage());
    }

    @Test
    void aSocketThatClosedBeforeJoiningLeavesNoSeat() throws Exception {
        ClientConnection gone = socket();
        gone.stop();

        assertNull(table.join(gone, "Vesper", nextId::getAndIncrement, seat -> { }));
        assertEquals(0, table.size());
    }

    @Test
    void aDropKeepsTheSeatAndItsNameUntilTheGraceRunsOut() throws Exception {
        ClientConnection vesper = socket();
        Seat seat = table.join(vesper, "Vesper", nextId::getAndIncrement, s -> { }).seat();

        assertTrue(table.drop(vesper));
        assertTrue(seat.isAway());
        assertTrue(table.everyoneAway());
        assertThrows(ProtocolException.class, () -> table.join(socket(), "Vesper", nextId::getAndIncrement, s -> { }));

        passSeconds(29);
        table.expire();
        assertSame(seat, table.get(seat.playerId), "still within grace");

        passSeconds(1);
        table.expire();
        assertNull(table.get(seat.playerId));
        assertEquals(List.of(seat), removed);
        assertNotNull(table.join(socket(), "Vesper", nextId::getAndIncrement, s -> { }), "the name is free again");
    }

    @Test
    void withNoGraceADropRemovesTheSeatAtOnce() throws Exception {
        SeatTable noGrace = new SeatTable(2, Duration.ZERO, clock::get, removed::add);
        ClientConnection vesper = socket();
        Seat seat = noGrace.join(vesper, "Vesper", nextId::getAndIncrement, s -> { }).seat();

        assertFalse(noGrace.drop(vesper), "not away: gone");
        assertNull(noGrace.get(seat.playerId));
        assertEquals(List.of(seat), removed);
    }

    @Test
    void aRejoinTakesTheSeatBackWithItsToken() throws Exception {
        ClientConnection first = socket();
        Seat seat = table.join(first, "Vesper", nextId::getAndIncrement, s -> { }).seat();
        table.drop(first);

        ClientConnection second = socket();
        SeatTable.Rejoined rejoined = table.rejoin(second, seat.token,
                s -> assertTrue(s.isAway(), "greeted before the seat is handed over"));

        assertSame(seat, rejoined.seat());
        assertNull(rejoined.replaced(), "the seat was away, so nobody is replaced");
        assertSame(second, seat.connection());
        assertEquals(seat.playerId, second.getPlayerId());
    }

    @Test
    void aRejoinFromASecondSocketReplacesTheFirst() throws Exception {
        ClientConnection first = socket();
        Seat seat = table.join(first, "Vesper", nextId::getAndIncrement, s -> { }).seat();

        ClientConnection second = socket();
        SeatTable.Rejoined rejoined = table.rejoin(second, seat.token, s -> { });

        assertSame(first, rejoined.replaced());
        assertFalse(table.isSeated(seat.playerId, first), "the old socket is stale");
        assertFalse(table.drop(first), "its close changes nothing");
        assertFalse(seat.isAway());
    }

    @Test
    void anExpiredSeatCannotBeTakenBackEvenBeforeTheLoopRemovesIt() throws Exception {
        ClientConnection first = socket();
        Seat seat = table.join(first, "Vesper", nextId::getAndIncrement, s -> { }).seat();
        table.drop(first);
        passSeconds(30);

        ProtocolException ended = assertThrows(ProtocolException.class,
                () -> table.rejoin(socket(), seat.token, s -> { }));
        assertEquals(SeatTable.SESSION_ENDED, ended.getMessage());
        assertEquals(List.of(seat), removed, "removed once, by the rejoin");

        table.expire();
        assertEquals(1, removed.size(), "and not again by the loop");
    }

    @Test
    void anUnknownTokenIsRefused() {
        ProtocolException ended = assertThrows(ProtocolException.class,
                () -> table.rejoin(socket(), "q3V9cX0bTqKk1yH6m2wJ8A", s -> { }));
        assertEquals(SeatTable.SESSION_ENDED, ended.getMessage());
    }

    @Test
    void aRemovedSeatsTokenStopsWorking() throws Exception {
        Seat seat = table.join(socket(), "Vesper", nextId::getAndIncrement, s -> { }).seat();

        assertTrue(table.remove(seat.playerId));
        assertFalse(table.remove(seat.playerId), "only once");
        assertThrows(ProtocolException.class, () -> table.rejoin(socket(), seat.token, s -> { }));
    }

    @Test
    void theFirstJoinTakesAHeldSeatWithItsName() throws Exception {
        table.hold(7, "Vesper");

        SeatTable.Joined joined = table.join(socket(), "Someone", nextId::getAndIncrement, s -> { });

        assertTrue(joined.wasHeld());
        assertEquals(7, joined.seat().playerId);
        assertEquals("Vesper", joined.seat().name);
        assertFalse(table.join(socket(), "Corvin", nextId::getAndIncrement, s -> { }).wasHeld(), "held once only");
    }

    private void passSeconds(int seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private static int sockets;

    /** A connection whose transport swallows what it's sent. */
    private static ClientConnection socket() {
        String id = "test-" + ++sockets;
        return new ClientConnection(new ClientConnection.Transport() {
            @Override public String id()                         { return id; }
            @Override public void send(String text)              { }
            @Override public void close(int code, String reason) { }
        });
    }
}
