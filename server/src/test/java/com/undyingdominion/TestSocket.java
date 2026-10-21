package com.undyingdominion;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * One WebSocket to a running Dominion, as a player or bot holds it. Everything the server sends is
 * queued for the test to wait on, the last welcome's player id is kept, and so is the reason the
 * server gave if it closed the socket. Shared by every acceptance test that plays over the wire.
 */
public final class TestSocket {

    private static final long WAIT_SECONDS = 8;

    private final BlockingQueue<JSONObject> inbox = new LinkedBlockingQueue<>();
    private final CompletableFuture<String> closed = new CompletableFuture<>();
    private final WebSocket socket;
    private volatile Integer playerId;

    private TestSocket(int port) {
        socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            JSONObject message = new JSONObject(partial.toString());
                            partial.setLength(0);
                            if (message.getString("type").equals("welcome")) playerId = message.getInt("playerId");
                            inbox.add(message);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                        closed.complete(reason);
                        return null;
                    }
                }).join();
    }

    public static TestSocket open(int port) {
        return new TestSocket(port);
    }

    /** Opens a socket and sends {@code join} with this name. */
    public static TestSocket join(int port, String name) {
        TestSocket socket = open(port);
        socket.send(new JSONObject().put("command", "join").put("name", name));
        return socket;
    }

    /** Opens a socket and sends {@code rejoin} with this token. */
    public static TestSocket rejoin(int port, String token) {
        TestSocket socket = open(port);
        socket.send(new JSONObject().put("command", "rejoin").put("token", token));
        return socket;
    }

    public void send(String json) {
        socket.sendText(json, true).join();
    }

    public void send(JSONObject message) {
        send(message.toString());
    }

    /** The first message that matches, skipping everything before it. Fails the test after eight seconds. */
    public JSONObject await(String what, Predicate<JSONObject> match) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                JSONObject message = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && match.test(message)) return message;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fail("waited " + WAIT_SECONDS + " seconds for " + what);
    }

    public JSONObject awaitType(String type) {
        return awaitType(type, m -> true);
    }

    public JSONObject awaitType(String type, Predicate<JSONObject> match) {
        return await("a matching " + type, m -> m.getString("type").equals(type) && match.test(m));
    }

    /** The next state. */
    public JSONObject awaitState(Predicate<JSONObject> match) {
        return awaitType("state", match);
    }

    /** The state that carries {@code text} among the player's notices. */
    public JSONObject awaitNotice(String text) {
        return await("the notice: " + text, m -> isState(m)
                && m.getJSONObject("you").getJSONArray("notices").toList().contains(text));
    }

    /** The reason the server gave for closing this socket. */
    public String awaitClose() {
        try {
            return closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fail("the server did not close the socket: " + e);
        }
    }

    /** The player id from the last welcome, or null before any. */
    public Integer playerId() {
        return playerId;
    }

    /** Everything that arrives in the next {@code millis}, without failing if nothing does. */
    public List<JSONObject> collect(long millis) {
        List<JSONObject> messages = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        try {
            long left;
            while ((left = deadline - System.nanoTime()) > 0) {
                JSONObject message = inbox.poll(left, TimeUnit.NANOSECONDS);
                if (message != null) messages.add(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return messages;
    }

    /** Forgets everything received so far, so the next wait sees only what comes after. */
    public void drain() {
        inbox.clear();
    }

    /** Drops the socket without a close handshake, as a refreshed or killed tab does. */
    public void abort() {
        socket.abort();
    }

    public static boolean isState(JSONObject message) {
        return message.getString("type").equals("state");
    }
}
