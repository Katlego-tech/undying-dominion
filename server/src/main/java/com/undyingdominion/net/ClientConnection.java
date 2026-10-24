package com.undyingdominion.net;

import io.javalin.websocket.WsContext;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One browser or bot connected over WebSocket.
 *
 * <p>Sending happens on the connection's own virtual thread, so a slow client never stalls the game
 * loop. World states are sent latest-wins: if a client falls behind, it skips stale states instead
 * of queueing them. Other messages (welcome, errors) are always delivered, in order.
 */
public class ClientConnection {

    /** The socket underneath: a WebSocket session in the server, anything that takes text in tests. */
    interface Transport {
        String id();
        void send(String text) throws Exception;
        void close(int code, String reason);
    }

    private final Transport transport;
    private final Queue<String> messages = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> latestState = new AtomicReference<>();
    private final Semaphore wakeUp = new Semaphore(0);
    private volatile boolean open = true;

    private volatile Integer playerId;
    private volatile String name;

    /** The socket's id, unique among the server's connections. */
    public String id() {
        return transport.id();
    }

    public ClientConnection(WsContext ctx) {
        this(new Transport() {
            @Override public String id()                       { return ctx.sessionId(); }
            @Override public void send(String text)            { ctx.send(text); }
            @Override public void close(int code, String reason) { ctx.closeSession(code, reason); }
        });
    }

    ClientConnection(Transport transport) {
        this.transport = transport;
        Thread.ofVirtual().name("ws-send-" + transport.id()).start(this::sendLoop);
    }

    /** Queues a message that must arrive. */
    public void send(String json) {
        messages.add(json);
        wakeUp.release();
    }

    /** Offers the newest world state, replacing any the client has not been sent yet. */
    public void offerState(String json) {
        latestState.set(json);
        wakeUp.release();
    }

    public void close(int code, String reason) {
        open = false;
        wakeUp.release();
        try {
            transport.close(code, reason);
        } catch (Exception ignored) {
            // already closed
        }
    }

    /** Stops the sender after the socket has gone. */
    public void stop() {
        open = false;
        wakeUp.release();
    }

    private void sendLoop() {
        try {
            while (open) {
                wakeUp.acquire();
                wakeUp.drainPermits();
                String message;
                while (open && (message = messages.poll()) != null) {
                    transport.send(message);
                }
                String state = latestState.getAndSet(null);
                if (open && state != null) {
                    transport.send(state);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            open = false;
        }
    }

    public String sessionId()          { return transport.id(); }
    public boolean isOpen()            { return open; }
    public Integer getPlayerId()       { return playerId; }
    public String getName()            { return name; }

    public void joined(int playerId, String name) {
        this.playerId = playerId;
        this.name = name;
    }
}
