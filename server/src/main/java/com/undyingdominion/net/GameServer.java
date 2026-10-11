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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private final GameEngine engine;
    private final int maxPlayers;

    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    /** Lower-cased names in use. Guarded by itself so the full check and the claim happen together. */
    private final Map<String, ClientConnection> names = new ConcurrentHashMap<>();
    private final Queue<Runnable> loopTasks = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> worldJson = new AtomicReference<>("{}");
    private final ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "game-loop"));

    private Javalin app;

    public GameServer(GameEngine engine, int maxPlayers) {
        this.engine = engine;
        this.maxPlayers = maxPlayers;
    }

    public void start(int port) {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            if (Files.isDirectory(DEV_CLIENT)) {
                config.staticFiles.add(DEV_CLIENT.toAbsolutePath().toString(), Location.EXTERNAL);
            } else {
                config.staticFiles.add("/public", Location.CLASSPATH);
            }
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> connections.put(ctx.sessionId(), new ClientConnection(ctx)));
            ws.onMessage(ctx -> onMessage(ctx.sessionId(), ctx.message()));
            ws.onClose(ctx -> onClose(ctx.sessionId()));
            ws.onError(ctx -> onClose(ctx.sessionId()));
        });

        // Robot Worlds' Web API iteration asked for GET /world: the world's layout as JSON.
        // Unit positions are left out so the API cannot be used to see through the fog.
        app.get("/api/world", ctx -> ctx.contentType("application/json").result(worldJson.get()));

        app.start(port);
        worldJson.set(describeWorld());

        long period = 1000L / engine.getRules().ticksPerSecond();
        loop.scheduleAtFixedRate(this::step, period, period, TimeUnit.MILLISECONDS);
    }

    public int port() {
        return app.port();
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
        if (app != null) app.stop();
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
            } else {
                engine.submit(ProtocolParser.parse(message, playerId));
            }
        } catch (ProtocolException e) {
            connection.send(SnapshotWriter.error(e.getMessage()));
        }
    }

    private void join(ClientConnection connection, String requestedName) throws ProtocolException {
        String name = ProtocolParser.validName(requestedName);
        String key = name.toLowerCase();
        synchronized (names) {
            if (names.containsKey(key)) throw new ProtocolException("Too many of you in this world");
            if (names.size() >= maxPlayers) throw new ProtocolException("No more space in this world");
            names.put(key, connection);
        }
        int playerId = engine.allocatePlayerId();
        connection.joined(playerId, name);
        // Queued before the engine hears of the player, so the welcome always precedes the first state.
        connection.send(SnapshotWriter.welcome(playerId, name, engine.getMap(), engine.getRules()));
        engine.submit(new Command.Join(playerId, name));
        log.info("{} joined the Dominion", name);
    }

    private void onClose(String sessionId) {
        ClientConnection connection = connections.remove(sessionId);
        if (connection == null) return;
        connection.stop();
        Integer playerId = connection.getPlayerId();
        if (playerId != null) {
            names.remove(connection.getName().toLowerCase(), connection);
            engine.submit(new Command.Leave(playerId));
            log.info("{} left the Dominion", connection.getName());
        }
    }

    // ------------------------------------------------------------------ game loop thread

    private void step() {
        try {
            Runnable task;
            while ((task = loopTasks.poll()) != null) {
                task.run();
            }

            engine.tick();

            JSONArray scoreboard = SnapshotWriter.scoreboard(engine);
            for (ClientConnection connection : connections.values()) {
                Integer playerId = connection.getPlayerId();
                if (playerId == null || !connection.isOpen()) continue;
                Player player = engine.getPlayer(playerId);
                if (player == null) continue;
                connection.offerState(SnapshotWriter.state(engine, player, scoreboard));
            }

            if (engine.getTick() % engine.getRules().ticksPerSecond() == 0) {
                worldJson.set(describeWorld());
            }
        } catch (Throwable t) {
            // A scheduled task that throws is never run again, so the loop must not let anything out.
            log.error("Tick {} failed", engine.getTick(), t);
        }
    }

    private String describeWorld() {
        return SnapshotWriter.mapJson(engine.getMap())
                .put("tick", engine.getTick())
                .put("players", SnapshotWriter.scoreboard(engine))
                .toString();
    }
}
