package com.undyingdominion;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.PlayerState;
import com.undyingdominion.net.GameServer;
import com.undyingdominion.persistence.SaveService;
import com.undyingdominion.persistence.SaveDao;
import com.undyingdominion.persistence.SqliteSaveDao;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.StoryDirector;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * One running world: the server hosting it, the saves it can make and, for a campaign, the director
 * telling its story. {@code story} is null in a free-for-all.
 */
public record Dominion(GameServer server, StoryDirector story, SaveService saves) {

    /** Loads the map or campaign, builds the engine and opens the server. Throws if the port cannot be taken. */
    public static Dominion raise(ServerOptions options) throws IOException {
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
            GameRules rules = GameRules.defaults();
            if (!options.wildCreatures()) rules.maxNeutrals(0);
            engine = new GameEngine(map, rules, random);
        }

        GameServer server = new GameServer(engine, options.maxPlayers());
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
        return new Dominion(server, story, saves);
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
        server.stop();
    }
}
