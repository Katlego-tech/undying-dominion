package com.undyingdominion;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.PlayerState;
import com.undyingdominion.game.Roster;
import com.undyingdominion.net.Beacon;
import com.undyingdominion.net.GameServer;
import com.undyingdominion.persistence.SaveService;
import com.undyingdominion.persistence.SaveDao;
import com.undyingdominion.persistence.SqliteSaveDao;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.StoryDirector;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * One running world: the server hosting it, the saves it can make and, for a campaign, the director
 * telling its story. {@code story} is null in a free-for-all. {@code beacon} is null with {@code --no-beacon}.
 */
public record Dominion(GameServer server, StoryDirector story, SaveService saves, Beacon beacon) {

    private static final Logger log = LoggerFactory.getLogger(Dominion.class);

    /** Loads the map or campaign, builds the engine and opens the server. Throws if the port cannot be taken. */
    public static Dominion raise(ServerOptions options) throws IOException {
        return raise(options, System::nanoTime);
    }

    /** As above, timing rejoin grace periods by {@code nanoClock}, so tests can move time on. */
    public static Dominion raise(ServerOptions options, LongSupplier nanoClock) throws IOException {
        Random random = options.seed() == null ? new Random() : new Random(options.seed());
        SaveDao dao = new SqliteSaveDao(Path.of(options.database()));
        GameEngine engine;
        StoryDirector story = null;
        SaveService.Resumed resumed = null;
        if (options.resume() != null) {
            resumed = SaveService.resume(dao, options.resume());
            story = resumed.director();
            engine = story.firstEngine();
        } else if (options.campaignFolder() != null) {
            story = new StoryDirector(Campaign.load(Path.of(options.campaignFolder())), random);
            engine = story.firstEngine();
        } else {
            GameMap map = MapConfig.load(options.mapLocation());
            // Nobody in a free-for-all can raise the dead, so they leave no corpses.
            GameRules rules = GameRules.defaults().corpses(false);
            if (!options.wildCreatures()) rules.maxNeutrals(0);
            engine = new GameEngine(map, rules, random);
        }

        GameServer server = new GameServer(engine, options.maxPlayers(), Duration.ofSeconds(options.graceSeconds()), nanoClock);
        if (story == null) server.offerCommanders(Roster.builtIn());
        else server.mode("campaign");
        if (resumed != null) holdSeatFor(server, resumed);
        try {
            server.start(options.port());
        } catch (RuntimeException e) {
            server.stop();
            throw e;
        }
        if (story != null) story.attach(server);
        SaveService saves = new SaveService(server, story, dao);
        server.setSaveHandler((playerId, name, force) -> saves.save(name, force, message -> {
            Player player = server.engine().getPlayer(playerId);
            if (player != null) player.notice(message);
        }));
        if (story != null) {
            // Each new chapter is saved once its players have been joined into the new world.
            story.onChapterBegins(chapter -> server.runAfterStep(() -> saves.autosave(message ->
                    server.engine().getPlayers().forEach(player -> player.notice(message)))));
        }
        return new Dominion(server, story, saves, announce(options, server, story == null ? "free-for-all" : "campaign"));
    }

    /** Starts the LAN beacon the Android app listens for, unless it's turned off. A refused socket costs only the beacon. */
    private static Beacon announce(ServerOptions options, GameServer server, String mode) {
        if (!options.beacon()) return null;
        String host = hostName();
        try {
            return Beacon.start(options.beaconPort(), Beacon::targets,
                    () -> Beacon.payload(host, server.port(), mode, server.joinedCount(), options.maxPlayers()));
        } catch (SocketException e) {
            log.warn("No LAN beacon: {}. Players can still type the address.", e.getMessage());
            return null;
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "Dominion";
        }
    }

    /** Keeps the saved player's seat for the first join, and greets them once they have taken it. */
    private static void holdSeatFor(GameServer server, SaveService.Resumed resumed) {
        PlayerState saved = resumed.save().engine().players().getFirst();
        server.holdSeat(saved.id(), saved.name());
        String greeting = "Resumed \"" + resumed.save().name() + "\": chapter " + resumed.director().chapterNumber()
                + ", " + resumed.director().current().title() + ". You are " + saved.name() + ". Press P to carry on.";
        server.addJoinListener(playerId -> {
            if (playerId != saved.id()) return;
            server.runOnLoop(() -> {
                Player player = server.engine().getPlayer(playerId);
                if (player != null) player.notice(greeting);
            });
        });
    }

    /** The current world. In a campaign it changes from chapter to chapter. */
    public GameEngine engine() {
        return server.engine();
    }

    public GameMap map() {
        return server.engine().getMap();
    }

    public void close() {
        if (beacon != null) beacon.close();
        server.stop();
    }
}
