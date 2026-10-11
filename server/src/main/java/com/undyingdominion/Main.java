package com.undyingdominion;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.net.AdminConsole;
import com.undyingdominion.net.GameServer;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;

import java.util.Random;

/** Starts a Dominion: loads the map, starts the game loop and opens the server. */
public class Main {

    public static void main(String[] args) throws Exception {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [-p port] [-m map.properties] [-mc max-players] [--seed n] [--no-wild]");
            System.exit(2);
            return;
        }

        GameMap map = MapConfig.load(options.mapLocation());
        GameRules rules = GameRules.defaults();
        if (!options.wildCreatures()) rules.maxNeutrals(0);
        Random random = options.seed() == null ? new Random() : new Random(options.seed());

        GameEngine engine = new GameEngine(map, rules, random);
        GameServer server = new GameServer(engine, options.maxPlayers());
        server.start(options.port());

        System.out.printf("* Raising the Dominion.. [map: %d x %d, obstacles: %d, max players: %d]%n",
                map.getWidth(), map.getHeight(), map.getObstacles().size(), options.maxPlayers());
        System.out.printf("* Open http://localhost:%d to play. Type help for console commands.%n", server.port());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        new AdminConsole(server, engine, System.in, System.out, () -> System.exit(0)).start();
    }
}
