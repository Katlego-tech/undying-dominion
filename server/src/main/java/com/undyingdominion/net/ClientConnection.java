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

    private final WsContext ctx;
    private final Queue<String> messages = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> latestState = new AtomicReference<>();
    private final Semaphore wakeUp = new Semaphore(0);
    private volatile boolean open = true;

    private volatile Integer playerId;
    private volatile String name;

    public ClientConnection(WsContext ctx) {
        this.ctx = ctx;
        Thread.ofVirtual().name("ws-send-" + ctx.sessionId()).start(this::sendLoop);
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
            ctx.closeSession(code, reason);
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
                    ctx.send(message);
                }
                String state = latestState.getAndSet(null);
                if (open && state != null) {
                    ctx.send(state);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            open = false;
        }
    }

    public String sessionId()          { return ctx.sessionId(); }
    public boolean isOpen()            { return open; }
    public Integer getPlayerId()       { return playerId; }
    public String getName()            { return name; }

    public void joined(int playerId, String name) {
        this.playerId = playerId;
        this.name = name;
    }
}
