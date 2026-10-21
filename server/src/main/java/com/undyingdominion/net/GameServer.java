package com.undyingdominion.net;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.Player;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Hosts one Dominion: runs the game loop, serves the browser client, and speaks the WebSocket
 * protocol to players and bots.
 *
 * <p>The engine is only ever touched from the loop thread. Network threads hand it commands through
 * its queue, and anything else that needs the engine (the admin console) is run on the loop with
 * {@link #runOnLoop}.
 */
public class GameServer {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);
    /** When run from the server directory, serve the client straight from source so edits show on reload. */
    private static final Path DEV_CLIENT = Path.of("src/main/resources/public");

    /** How long an away player's place is kept unless the server is told otherwise. */
    public static final Duration DEFAULT_GRACE = Duration.ofSeconds(30);
    private static final String SESSION_ENDED = "That session has ended. Join again";

    private volatile GameEngine engine;
    private volatile JSONObject story;
    private final int maxPlayers;
    private final Duration grace;

    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    /** Lower-cased names in use, away players' included. Guarded by itself so the full check and the claim happen together. */
    private final Map<String, ClientConnection> names = new ConcurrentHashMap<>();
    /** Every joined player's seat, connected or away, by player id and by token. Changed only under {@link #names}. */
    private final Map<Integer, Seat> seats = new ConcurrentHashMap<>();
    private final Map<String, Seat> seatsByToken = new ConcurrentHashMap<>();
    private final Queue<Runnable> loopTasks = new ConcurrentLinkedQueue<>();
    /** Run after the step's tick (or admit), when joins queued alongside them have been applied. */
    private final Queue<Runnable> afterStepTasks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> worldJson = new AtomicReference<>("{}");
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "game-loop"));

    private final List<Consumer<GameEngine>> tickListeners = new CopyOnWriteArrayList<>();
    private final List<IntConsumer> joinListeners = new CopyOnWriteArrayList<>();
    private volatile double lastTickMillis;

    /** Who paused the world: a player's id, {@link #HOST}, or null while it runs. Loop thread only. */
    private Integer pausedBy;
    private String pausedByName;
    /** Players with a story card open. A lone player reading pauses the world. Loop thread only. */
    private final Set<Integer> reading = new HashSet<>();
    private volatile JSONObject pause;
    private static final int HOST = -1;
    private volatile SaveHandler saveHandler;
    /** A resumed game's player, kept for the first join. The world is held until then. Guarded by {@link #names}. */
    private Integer heldSeatId;
    private String heldSeatName;
    private volatile boolean held;

    /** What to do when a player asks to save. Called on the game loop thread. */
    @FunctionalInterface
    public interface SaveHandler {
        void save(int playerId, String name, boolean force);
    }

    private Javalin app;

    public GameServer(GameEngine engine, int maxPlayers) {
        this(engine, maxPlayers, DEFAULT_GRACE);
    }

    /** {@code grace} is how long a dropped player's place is kept for them to rejoin. Zero removes them on drop. */
    public GameServer(GameEngine engine, int maxPlayers, Duration grace) {
        this.engine = engine;
        this.maxPlayers = maxPlayers;
        this.grace = grace;
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            if (Files.isDirectory(DEV_CLIENT)) {
                config.staticFiles.add(DEV_CLIENT.toAbsolutePath().toString(), Location.EXTERNAL);
            } else {
                config.staticFiles.add("/public", Location.CLASSPATH);
            }

            config.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> connections.put(ctx.sessionId(), new ClientConnection(ctx)));
                ws.onMessage(ctx -> onMessage(ctx.sessionId(), ctx.message()));
                ws.onClose(ctx -> onClose(ctx.sessionId()));
                ws.onError(ctx -> onClose(ctx.sessionId()));
            });

            // Robot Worlds' Web API iteration asked for GET /world: the world's layout as JSON.
            // Unit positions are left out so the API cannot be used to see through the fog.
            config.routes.get("/api/world", ctx -> ctx.contentType("application/json").result(worldJson.get()));
        });

        app.start(port);
        worldJson.set(describeWorld());

        long period = 1000L / engine.getRules().ticksPerSecond();
        loop.scheduleAtFixedRate(this::step, period, period, TimeUnit.MILLISECONDS);
    }

    public int port() {
        return app.port();
    }

    /**
     * Calls {@code listener} on the game loop thread after every tick. It may read the engine but
     * must be quick, since the next tick waits for it.
     */
    public void addTickListener(Consumer<GameEngine> listener) {
        tickListeners.add(listener);
    }

    /** Calls {@code listener} with a player's id right after they join and are sent the welcome. */
    public void addJoinListener(IntConsumer listener) {
        joinListeners.add(listener);
    }

    /** The world currently being played. Changes when a campaign moves on to its next chapter. */
    public GameEngine engine() {
        return engine;
    }

    /**
     * Keeps a resumed game's player for whoever joins first, and holds the world still until then. On
     * that join they become the saved player, name and all, and the world waits for them to resume it.
     * Call before {@link #start}.
     */
    public void holdSeat(int playerId, String name) {
        synchronized (names) {
            heldSeatId = playerId;
            heldSeatName = name;
        }
        held = true;
    }

    /** Who answers players' save requests. Without one, save is an unsupported command. */
    public void setSaveHandler(SaveHandler handler) {
        this.saveHandler = handler;
    }

    /** Story progress included in every player's state, or null outside a campaign. */
    public void setStory(JSONObject story) {
        this.story = story;
    }

    /** Sends to a player's live socket. A player who is away misses it. */
    public void sendTo(int playerId, String json) {
        Seat seat = seats.get(playerId);
        ClientConnection connection = seat == null ? null : seat.connection();
        if (connection != null) connection.send(json);
    }

    public void broadcast(String json) {
        for (Seat seat : seats.values()) {
            ClientConnection connection = seat.connection();
            if (connection != null) connection.send(json);
        }
    }

    /**
     * Swaps in a new world, as a campaign does between chapters. Every seat, connected or away, is
     * joined into it with the level, experience and record it had. Those connected are sent a fresh
     * welcome for the new map; an away player gets it when they rejoin.
     * Must be called on the game loop thread, for example from a tick listener.
     */
    public void replaceEngine(GameEngine next) {
        GameEngine previous = engine;
        synchronized (names) {
            int highestId = seats.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            next.reservePlayerIds(Math.max(highestId + 1, previous.peekNextPlayerId()));
            engine = next;
            for (Seat seat : seats.values()) {
                int id = seat.playerId;
                Player before = previous.getPlayer(id);
                next.submit(new Command.Join(id, seat.name));
                if (before != null) {
                    next.submit(new Command.Restore(id, before.getLevel(), before.getXp(), before.getKills(),
                            before.getDeaths(), previous.legionOf(id)));
                }
                ClientConnection connection = seat.connection();
                if (connection != null) connection.send(welcome(seat, next));
            }
        }
        worldJson.set(describeWorld());
    }

    /** How long the last tick took, including sending every player's state. */
    public double lastTickMillis() {
        return lastTickMillis;
    }

    /** Stops the world for everyone, from the host's console or dashboard. */
    public void pauseByHost() {
        runOnLoop(() -> pause(HOST, "the host"));
    }

    /** Sets the world running again, however it was paused by hand. */
    public void resume() {
        runOnLoop(this::lift);
    }

    /** Why the world is stopped, as sent to players: {@code {by, reason}}; or null while it runs. */
    public JSONObject pause() {
        return pause;
    }

    /** Runs a task on the game loop thread before the next tick. */
    public void runOnLoop(Runnable task) {
        loopTasks.add(task);
    }

    public void stop() {
        loop.shutdownNow();
        for (ClientConnection connection : connections.values()) {
            connection.close(1001, "The Dominion has fallen silent");
        }
        if (app != null) {
            try {
                app.stop();
            } catch (RuntimeException e) {
                log.warn("Server did not stop cleanly: {}", e.getMessage());
            }
        }
    }

    /** Ends a player's session from the server side, e.g. the console's purge. Their seat goes at once, away or not. */
    public void disconnect(int playerId, String reason) {
        Seat seat = seats.get(playerId);
        if (seat == null) {
            engine.submit(new Command.Leave(playerId));
        } else if (remove(seat)) {
            runOnLoop(() -> released(playerId));
        }
        for (ClientConnection connection : connections.values()) {
            if (Integer.valueOf(playerId).equals(connection.getPlayerId())) {
                connection.send(SnapshotWriter.error(reason));
                connection.close(1000, reason);
            }
        }
    }

    // ------------------------------------------------------------------ network threads

    private void onMessage(String sessionId, String raw) {
        ClientConnection connection = connections.get(sessionId);
        if (connection == null) return;
        try {
            JSONObject message = ProtocolParser.read(raw);
            String command = ProtocolParser.commandName(message);
            Integer playerId = connection.getPlayerId();
            if (playerId != null && !isSeated(playerId, connection)) return;  // stale: its seat moved to another socket

            if (playerId == null) {
                if ("join".equals(command)) join(connection, message.optString("name", null));
                else if ("rejoin".equals(command)) rejoin(connection, message.opt("token"));
                else throw new ProtocolException("Join the Dominion first");
            } else if ("join".equals(command) || "rejoin".equals(command)) {
                throw new ProtocolException("You have already joined");
            } else if ("pause".equals(command)) {
                runOnLoop(() -> pause(playerId, connection.getName()));
            } else if ("resume".equals(command)) {
                runOnLoop(this::lift);
            } else if ("save".equals(command) && saveHandler != null) {
                SaveHandler handler = saveHandler;
                String name = message.optString("name", null);
                boolean force = message.optBoolean("force", false);
                afterStepTasks.add(() -> handler.save(playerId, name, force));
            } else if ("reading".equals(command)) {
                boolean open = message.optBoolean("open", false);
                runOnLoop(() -> {
                    if (open) reading.add(playerId);
                    else reading.remove(playerId);
                });
            } else {
                engine.submit(ProtocolParser.parse(message, playerId));
            }
        } catch (ProtocolException e) {
            connection.send(SnapshotWriter.error(e.getMessage()));
        }
    }

    private void join(ClientConnection connection, String requestedName) throws ProtocolException {
        String name = ProtocolParser.validName(requestedName);
        Integer heldId = null;
        Seat seat;
        synchronized (names) {
            // A socket already closed would leave a seat no drop can ever reach. onClose checks under this lock too.
            if (!connection.isOpen()) return;
            if (heldSeatId != null) {
                heldId = heldSeatId;
                name = heldSeatName;
                heldSeatId = null;
                heldSeatName = null;
            }
            String key = name.toLowerCase();
            if (names.containsKey(key)) throw new ProtocolException("Too many of you in this world");
            if (names.size() >= maxPlayers) throw new ProtocolException("No more space in this world");
            names.put(key, connection);
            seat = Seat.connected(heldId != null ? heldId : engine.allocatePlayerId(), name, connection);
            connection.joined(seat.playerId, name);
            seats.put(seat.playerId, seat);
            seatsByToken.put(seat.token, seat);
        }
        if (heldId != null) {
            int id = heldId;
            String seatName = name;
            // The hold becomes the player's own pause in one step, so no tick slips in between.
            runOnLoop(() -> {
                pause(id, seatName);
                held = false;
            });
        }
        int playerId = seat.playerId;
        // Queued before the engine hears of the player, so the welcome always precedes the first state.
        connection.send(welcome(seat, engine));
        engine.submit(new Command.Join(playerId, name));
        log.info("{} joined the Dominion", name);
        for (IntConsumer listener : joinListeners) {
            listener.accept(playerId);
        }
    }

    /** Takes back an existing seat by its token. Join listeners are not told: the player never left. */
    private void rejoin(ClientConnection connection, Object token) throws ProtocolException {
        if (!(token instanceof String presented)) throw new ProtocolException(ProtocolParser.BAD_ARGUMENTS);
        Seat seat;
        ClientConnection replaced;
        synchronized (names) {
            if (!connection.isOpen()) return;
            seat = seatsByToken.get(presented);
            if (seat == null) throw new ProtocolException(SESSION_ENDED);
            connection.joined(seat.playerId, seat.name);
            // Queued before the seat is handed over, so the loop can't send this socket a state ahead of it;
            // and under the lock, so a chapter change can't slip its welcome in ahead of this one.
            connection.send(welcome(seat, engine));
            replaced = seat.reconnect(connection);
        }
        if (replaced != null) replaced.close(1000, "Taken up in another window");
        log.info("{} rejoined the Dominion", seat.name);
    }

    private void onClose(String sessionId) {
        ClientConnection connection = connections.remove(sessionId);
        if (connection == null) return;
        connection.stop();
        Integer playerId;
        boolean away;
        synchronized (names) {
            playerId = connection.getPlayerId();
            Seat seat = playerId == null ? null : seats.get(playerId);
            away = seat != null && seat.drop(connection, System.nanoTime() + grace.toNanos());
        }
        if (!away) return;  // never joined, taken over by another socket, or a seat already removed
        runOnLoop(() -> reading.remove(playerId));
        log.info("{} lost their connection; holding their place for {}s", connection.getName(), grace.toSeconds());
    }

    /** Frees a seat: its token stops working, its name and place are free, and the player leaves the world. */
    private boolean remove(Seat seat) {
        synchronized (names) {
            if (!seats.remove(seat.playerId, seat)) return false;
            seatsByToken.remove(seat.token);
            names.remove(seat.name.toLowerCase());
        }
        engine.submit(new Command.Leave(seat.playerId));
        log.info("{} left the Dominion", seat.name);
        return true;
    }

    private boolean isSeated(int playerId, ClientConnection connection) {
        Seat seat = seats.get(playerId);
        return seat != null && seat.connection() == connection;
    }

    private static String welcome(Seat seat, GameEngine engine) {
        return SnapshotWriter.welcome(seat.playerId, seat.name, seat.token, engine.getMap(), engine.getRules());
    }

    // ------------------------------------------------------------------ game loop thread

    private void step() {
        long started = System.nanoTime();
        try {
            Runnable task;
            while ((task = loopTasks.poll()) != null) {
                task.run();
            }
            expireSeats();

            GameEngine engine = this.engine;
            pause = currentPause();
            if (pause == null) engine.tick();
            else engine.admit();
            while ((task = afterStepTasks.poll()) != null) {
                task.run();
            }

            JSONArray scoreboard = SnapshotWriter.scoreboard(engine);
            for (Seat seat : seats.values()) {
                ClientConnection connection = seat.connection();
                if (connection == null || !connection.isOpen()) continue;
                Player player = engine.getPlayer(seat.playerId);
                if (player == null) continue;
                connection.offerState(SnapshotWriter.state(engine, player, scoreboard, story, pause));
            }

            if (engine.getTick() % engine.getRules().ticksPerSecond() == 0) {
                worldJson.set(describeWorld());
            }
            lastTickMillis = (System.nanoTime() - started) / 1_000_000.0;
            if (pause != null) return;
            for (Consumer<GameEngine> listener : tickListeners) {
                listener.accept(engine);
            }
        } catch (Throwable t) {
            // A scheduled task that throws is never run again, so the loop must not let anything out.
            log.error("Tick {} failed", engine.getTick(), t);
        }
    }

    /** Removes every seat whose grace has run out. Wall time, so it runs whether or not the world is paused. */
    private void expireSeats() {
        long now = System.nanoTime();
        for (Seat seat : seats.values()) {
            boolean removed;
            synchronized (names) {
                // One critical section, so a rejoin can't land between the check and the removal.
                removed = seat.expired(now) && remove(seat);
            }
            if (removed) released(seat.playerId);
        }
    }

    /** What a removed player held on the loop: an open story card, and the pause if it was theirs. */
    private void released(int playerId) {
        reading.remove(playerId);
        if (Integer.valueOf(playerId).equals(pausedBy)) lift();
    }

    private void pause(int by, String name) {
        if (pausedBy != null) return;
        pausedBy = by;
        pausedByName = name;
        log.info("The Dominion is paused by {}", name);
    }

    private void lift() {
        if (pausedBy == null) return;
        log.info("The Dominion resumes");
        pausedBy = null;
        pausedByName = null;
    }

    private JSONObject currentPause() {
        if (held) {
            return new JSONObject().put("by", JSONObject.NULL).put("reason", "resume");
        }
        if (pausedBy != null) {
            return new JSONObject().put("by", pausedByName).put("reason", pausedBy == HOST ? "host" : "player");
        }
        if (!seats.isEmpty() && seats.values().stream().allMatch(Seat::isAway)) {
            return new JSONObject().put("by", JSONObject.NULL).put("reason", "away");
        }
        if (!reading.isEmpty() && joinedCount() == 1) {
            return new JSONObject().put("by", JSONObject.NULL).put("reason", "story");
        }
        return null;
    }

    /** Players who have joined, connected or away, as opposed to sockets still at the name screen. */
    public long joinedCount() {
        return seats.size();
    }

    private String describeWorld() {
        return SnapshotWriter.mapJson(engine.getMap())
                .put("tick", engine.getTick())
                .put("players", SnapshotWriter.scoreboard(engine))
                .toString();
    }
}
