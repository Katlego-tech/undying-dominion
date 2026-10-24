package com.undyingdominion.net;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one address players connect to (docs/design/modes.md §3): it serves the client and the web
 * API, and routes each socket to a world by its first message: a {@code join} by its mode, a
 * {@code rejoin} by its token. Everything after goes to that world, which does all the rest.
 */
public final class Gateway {

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);
    /** When run from the server directory, serve the client straight from source so edits show on reload. */
    private static final Path DEV_CLIENT = Path.of("src/main/resources/public");

    /** Which world a player belongs in, and what the server offers. */
    public interface Router {
        /** The world a {@code join} asks for. Throws with the message the player sees if there's none. */
        GameServer forJoin(JSONObject join) throws ProtocolException;

        /** Every world running now, for rejoins. */
        List<GameServer> worlds();

        /** The world that answers {@code /api/world}, {@code /roster} and anything sent before a join. */
        GameServer lobby();

        /** What {@code GET /modes} says. */
        JSONObject modes();
    }

    private final Router router;
    private final Map<String, ClientConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, GameServer> routed = new ConcurrentHashMap<>();
    private Javalin app;

    public Gateway(Router router) {
        this.router = router;
    }

    /** A server with one world: every socket goes to it, as before there were several. */
    static Gateway single(GameServer world) {
        return new Gateway(new Router() {
            @Override public GameServer forJoin(JSONObject join) { return world; }
            @Override public List<GameServer> worlds()            { return List.of(world); }
            @Override public GameServer lobby()                   { return world; }
            @Override public JSONObject modes() {
                return new JSONObject().put("modes", new JSONArray().put(world.modeEntry()));
            }
        });
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
            config.routes.get("/api/world", ctx -> ctx.contentType("application/json").result(router.lobby().worldJson()));
            // The join form shows the kits before there's a socket (docs/design/characters.md §6 Client).
            config.routes.get("/roster", ctx -> ctx.contentType("application/json").result(router.lobby().rosterJson()));
            // The title screen's choices (docs/design/modes.md §6).
            config.routes.get("/modes", ctx -> ctx.contentType("application/json").result(router.modes().toString()));
        });
        app.start(port);
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        if (app == null) return;
        try {
            app.stop();
        } catch (RuntimeException e) {
            log.warn("Server did not stop cleanly: {}", e.getMessage());
        }
    }

    private void onMessage(String sessionId, String raw) {
        ClientConnection connection = connections.get(sessionId);
        if (connection == null) return;
        GameServer world = routed.get(sessionId);
        // Until a player has a seat, each join or rejoin picks its world afresh, so a refused join can try another.
        if (world == null || connection.getPlayerId() == null) {
            GameServer chosen;
            try {
                chosen = route(raw);
            } catch (ProtocolException e) {
                connection.send(SnapshotWriter.error(e.getMessage()));
                return;
            }
            if (chosen != world) {
                if (world != null) world.detach(connection);
                chosen.attach(connection);
                routed.put(sessionId, chosen);
                world = chosen;
            }
        }
        world.onMessage(connection, raw);
    }

    /** The world a socket's message belongs in: a join's mode, a rejoin's token, else the lobby, which answers it. */
    private GameServer route(String raw) throws ProtocolException {
        JSONObject message;
        try {
            message = new JSONObject(raw);
        } catch (JSONException e) {
            return router.lobby();
        }
        String command = message.optString("command", "").toLowerCase();
        if ("join".equals(command)) return router.forJoin(message);
        if ("rejoin".equals(command) && message.opt("token") instanceof String token) {
            for (GameServer world : router.worlds()) {
                if (world.knowsToken(token)) return world;
            }
        }
        return router.lobby();
    }

    private void onClose(String sessionId) {
        ClientConnection connection = connections.remove(sessionId);
        if (connection == null) return;
        GameServer world = routed.remove(sessionId);
        if (world != null) world.onClose(connection);
        else connection.stop();
    }
}
