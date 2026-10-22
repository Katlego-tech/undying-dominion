package com.undyingdominion.bot;

import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A sample AI necromancer that plays over the public WebSocket protocol and nothing else: it
 * joins, lets {@link BotBrain} decide what to do with every state, and if its connection drops it
 * rejoins with its token, or joins afresh once the server has let its place go
 * (docs/design/bot.md). A starting point for anyone writing their own bot.
 *
 * <pre>java -cp undying-dominion-…-jar-with-dependencies.jar com.undyingdominion.bot.SampleBot [ws-url] [name] [commander]</pre>
 */
public final class SampleBot {

    private static final Logger log = Logger.getLogger(SampleBot.class.getName());
    private static final String SESSION_ENDED = "That session has ended. Join again";
    private static final int MAX_RECONNECTS = 5;

    private final URI server;
    private final String name;
    /** Who it fields in a free-for-all; a campaign makes it the necromancer whatever this says. */
    private final String commander;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bot-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    private volatile WebSocket socket;
    private volatile String token;
    private volatile BotBrain brain;
    private volatile Integer playerId;
    private volatile boolean closing;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    /** Guarded by this: drops counted toward giving up, and whether a reconnect is already on its way. */
    private int failedConnects;
    private boolean reconnecting;

    public SampleBot(URI server, String name) {
        this(server, name, "HEXER");
    }

    public SampleBot(URI server, String name, String commander) {
        this.server = server;
        this.name = name;
        this.commander = commander;
    }

    public static void main(String[] args) throws InterruptedException {
        URI server = URI.create(args.length > 0 ? args[0] : "ws://localhost:5000/ws");
        String name = args.length > 1 ? args[1] : "Bonewright";
        String commander = args.length > 2 ? args[2] : "HEXER";
        SampleBot bot = new SampleBot(server, name, commander);
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close));
        bot.start();
        bot.closed.join();  // until close(), from a signal or from giving up
    }

    /** Connects and joins. Returns once the join is sent; play goes on in the background. */
    public void start() {
        connect(join());
    }

    /** Leaves the world for good: closes the socket and stops reconnecting. */
    public void close() {
        closing = true;
        retries.shutdownNow();
        WebSocket current = socket;
        if (current != null && !current.isOutputClosed()) current.sendClose(WebSocket.NORMAL_CLOSURE, "Bot stopped");
        closed.complete(null);
    }

    /** The player id from the last welcome, or null before one. */
    public Integer playerId() {
        return playerId;
    }

    /** Drops the socket without a close handshake, as a network failure does. For tests. */
    void abort() {
        WebSocket current = socket;
        socket = null;  // so the listener doesn't count this drop a second time
        if (current != null) current.abort();
        retries.schedule(this::onDrop, 0, TimeUnit.MILLISECONDS);
    }

    private JSONObject join() {
        return new JSONObject().put("command", "join").put("name", name).put("commander", commander);
    }

    private void connect(JSONObject first) {
        Listener listener = new Listener();
        try {
            socket = http.newWebSocketBuilder().buildAsync(server, listener).join();
            listener.current = true;
            // A socket that closed before it was ours to watch still counts as a drop.
            if (listener.gone) {
                onDrop();
                return;
            }
            send(first);
        } catch (RuntimeException e) {
            log.warning("Could not reach " + server + ": " + e.getMessage());
            onDrop();
        }
    }

    private void send(JSONObject message) {
        WebSocket current = socket;
        if (current != null && !current.isOutputClosed()) current.sendText(message.toString(), true).join();
    }

    private void onMessage(JSONObject message) {
        switch (message.getString("type")) {
            case "welcome" -> {
                // In the world again, so the next drop starts its backoff from one second.
                synchronized (this) {
                    failedConnects = 0;
                }
                // A second welcome is a new chapter's map: a new brain for the new world.
                playerId = message.getInt("playerId");
                token = message.getString("token");
                brain = new BotBrain(message);
            }
            case "state" -> {
                BotBrain current = brain;
                if (current != null) current.decide(message).forEach(this::send);
            }
            case "error" -> {
                String text = message.getString("message");
                if (SESSION_ENDED.equals(text)) {
                    token = null;
                    send(join());
                } else if (playerId == null || brain == null) {
                    // A refused join (name taken, server full, a bad name) won't change by retrying.
                    log.warning("Could not join: " + text);
                    close();
                } else {
                    log.info("The server said: " + text);
                }
            }
            default -> { }  // story: a bot has no use for the narration
        }
    }

    /** The socket is gone and we didn't close it: try to take the seat back, waiting longer each time. */
    private synchronized void onDrop() {
        if (closing || retries.isShutdown() || reconnecting) return;
        if (failedConnects >= MAX_RECONNECTS) {
            log.warning("Gave up after " + MAX_RECONNECTS + " attempts to reach " + server);
            close();
            return;
        }
        reconnecting = true;
        long delay = 1L << failedConnects++;
        retries.schedule(() -> {
            synchronized (this) {
                reconnecting = false;
            }
            String held = token;
            connect(held != null ? new JSONObject().put("command", "rejoin").put("token", held) : join());
        }, delay, TimeUnit.SECONDS);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();
        /** Set once connect() has made this listener's socket the bot's socket. */
        volatile boolean current;
        /** Set when this socket closed or failed, perhaps before connect() got to it. */
        volatile boolean gone;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    onMessage(new JSONObject(text));
                } catch (RuntimeException e) {
                    log.warning("Could not act on a message: " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            gone();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            gone();
        }

        private void gone() {
            gone = true;
            if (current && socket != null && !closing) onDrop();
        }
    }
}
