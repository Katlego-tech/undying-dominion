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

    private volatile GameEngine engine;
    private volatile JSONObject story;
    private final int maxPlayers;

    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    /** Lower-cased names in use. Guarded by itself so the full check and the claim happen together. */
    private final Map<String, ClientConnection> names = new ConcurrentHashMap<>();
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
        this.engine = engine;
        this.maxPlayers = maxPlayers;
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

    public void sendTo(int playerId, String json) {
        for (ClientConnection connection : connections.values()) {
            if (Integer.valueOf(playerId).equals(connection.getPlayerId())) connection.send(json);
        }
    }

    public void broadcast(String json) {
        for (ClientConnection connection : connections.values()) {
            if (connection.getPlayerId() != null) connection.send(json);
        }
    }

    /**
     * Swaps in a new world, as a campaign does between chapters. Everyone connected is joined into it
     * with the level, experience and record they had, and sent a fresh welcome for the new map.
     * Must be called on the game loop thread, for example from a tick listener.
     */
    public void replaceEngine(GameEngine next) {
        GameEngine previous = engine;
        int highestId = 0;
        for (ClientConnection connection : connections.values()) {
            Integer id = connection.getPlayerId();
            if (id != null) highestId = Math.max(highestId, id);
        }
        next.reservePlayerIds(Math.max(highestId + 1, previous.peekNextPlayerId()));
        engine = next;
        for (ClientConnection connection : connections.values()) {
            Integer id = connection.getPlayerId();
            if (id == null) continue;
            Player before = previous.getPlayer(id);
            next.submit(new Command.Join(id, connection.getName()));
            if (before != null) {
                next.submit(new Command.Restore(id, before.getLevel(), before.getXp(), before.getKills(),
                        before.getDeaths(), previous.legionOf(id)));
            }
            connection.send(SnapshotWriter.welcome(id, connection.getName(), next.getMap(), next.getRules()));
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

    /** Ends a player's session from the server side, e.g. the console's purge. */
    public void disconnect(int playerId, String reason) {
        engine.submit(new Command.Leave(playerId));
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

            if (playerId == null) {
                if (!"join".equals(command)) throw new ProtocolException("Join the Dominion first");
                join(connection, message.optString("name", null));
            } else if ("join".equals(command)) {
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
        Integer seat = null;
        synchronized (names) {
            if (heldSeatId != null) {
                seat = heldSeatId;
                name = heldSeatName;
                heldSeatId = null;
                heldSeatName = null;
            }
            String key = name.toLowerCase();
            if (names.containsKey(key)) throw new ProtocolException("Too many of you in this world");
            if (names.size() >= maxPlayers) throw new ProtocolException("No more space in this world");
            names.put(key, connection);
        }
        if (seat != null) {
            int id = seat;
            String seatName = name;
            // The hold becomes the player's own pause in one step, so no tick slips in between.
            runOnLoop(() -> {
                pause(id, seatName);
                held = false;
            });
        }
        int playerId = seat != null ? seat : engine.allocatePlayerId();
        connection.joined(playerId, name);
        // Queued before the engine hears of the player, so the welcome always precedes the first state.
        connection.send(SnapshotWriter.welcome(playerId, name, engine.getMap(), engine.getRules()));
        engine.submit(new Command.Join(playerId, name));
        log.info("{} joined the Dominion", name);
        for (IntConsumer listener : joinListeners) {
            listener.accept(playerId);
        }
    }

    private void onClose(String sessionId) {
        ClientConnection connection = connections.remove(sessionId);
        if (connection == null) return;
        connection.stop();
        Integer playerId = connection.getPlayerId();
        if (playerId != null) {
            names.remove(connection.getName().toLowerCase(), connection);
            engine.submit(new Command.Leave(playerId));
            runOnLoop(() -> {
                reading.remove(playerId);
                if (playerId.equals(pausedBy)) lift();
            });
            log.info("{} left the Dominion", connection.getName());
        }
    }

    // ------------------------------------------------------------------ game loop thread

    private void step() {
        long started = System.nanoTime();
        try {
            Runnable task;
            while ((task = loopTasks.poll()) != null) {
                task.run();
            }

            GameEngine engine = this.engine;
            pause = currentPause();
            if (pause == null) engine.tick();
            else engine.admit();
            while ((task = afterStepTasks.poll()) != null) {
                task.run();
            }

            JSONArray scoreboard = SnapshotWriter.scoreboard(engine);
            for (ClientConnection connection : connections.values()) {
                Integer playerId = connection.getPlayerId();
                if (playerId == null || !connection.isOpen()) continue;
                Player player = engine.getPlayer(playerId);
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
        if (!reading.isEmpty() && joinedCount() == 1) {
            return new JSONObject().put("by", JSONObject.NULL).put("reason", "story");
        }
        return null;
    }

    /** Players who have joined, as opposed to sockets still at the name screen. */
    public long joinedCount() {
        return connections.values().stream().filter(c -> c.getPlayerId() != null).count();
    }

    private String describeWorld() {
        return SnapshotWriter.mapJson(engine.getMap())
                .put("tick", engine.getTick())
                .put("players", SnapshotWriter.scoreboard(engine))
                .toString();
    }
}
