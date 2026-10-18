package com.undyingdominion;

import com.undyingdominion.net.AdminConsole;
import com.undyingdominion.tui.ServerTui;

/**
 * Starts a Dominion. In an interactive terminal it runs the full-screen dashboard; with
 * {@code --plain}, or when output is piped or there is no terminal (a container, a service), it
 * runs a plain line-by-line console instead.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        ServerOptions options;
        try {
            options = ServerOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("Usage: [-p port] [-m map.properties | --campaign folder | --resume save] [--db saves.db]"
                    + " [-mc max-players] [--seed n] [--no-wild] [--plain]");
            System.exit(2);
            return;
        }

        if (options.dashboard() && System.console() != null) {
            ServerTui.run(options);
            return;
        }

        Dominion dominion;
        try {
            dominion = Dominion.raise(options);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        System.out.printf("* Raising the Dominion.. [map: %d x %d, obstacles: %d, max players: %d]%n",
                dominion.map().getWidth(), dominion.map().getHeight(), dominion.map().getObstacles().size(),
                options.maxPlayers());
        if (dominion.story() != null) System.out.println("* Campaign: " + dominion.story().campaign().title());
        System.out.printf("* Open http://localhost:%d to play. Type help for console commands.%n", dominion.server().port());

        Runtime.getRuntime().addShutdownHook(new Thread(dominion::close));
        new AdminConsole(dominion.server(), System.in, System.out, () -> System.exit(0))
                .withStory(dominion.story()).withSaves(dominion.saves()).start();
    }
}
