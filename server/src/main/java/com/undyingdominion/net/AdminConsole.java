package com.undyingdominion.net;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Unit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * The server operator's console, carried over from Robot Worlds' world commands:
 * {@code players} (was {@code robots}), {@code dump}, {@code purge <name>} and {@code quit}.
 */
public class AdminConsole implements Runnable {

    private final GameServer server;
    private final GameEngine engine;
    private final InputStream in;
    private final PrintStream out;
    private final Runnable onQuit;

    public AdminConsole(GameServer server, GameEngine engine, InputStream in, PrintStream out, Runnable onQuit) {
        this.server = server;
        this.engine = engine;
        this.in = in;
        this.out = out;
        this.onQuit = onQuit;
    }

    /** Starts reading commands on a background thread. */
    public void start() {
        Thread thread = new Thread(this, "admin-console");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handle(line.trim())) return;
            }
        } catch (IOException e) {
            out.println("Console closed: " + e.getMessage());
        }
    }

    /** Handles one console line. Returns false once the server is shutting down. */
    boolean handle(String line) {
        if (line.isEmpty()) return true;
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "help" -> out.println("""
                    Commands:
                      players        list necromancers and their standing
                      dump           show the state of the world
                      purge <name>   destroy a necromancer's legion and remove them
                      quit           close the Dominion""");
            case "players" -> server.runOnLoop(this::printPlayers);
            case "dump" -> server.runOnLoop(this::printDump);
            case "purge" -> {
                if (argument.isEmpty()) {
                    out.println("usage: purge <name>");
                } else {
                    server.runOnLoop(() -> purge(argument));
                }
            }
            case "quit" -> {
                out.println("Closing the Dominion.");
                onQuit.run();
                return false;
            }
            default -> out.println("Unknown command '" + command + "'. Type help.");
        }
        return true;
    }

    private void printPlayers() {
        if (engine.getPlayers().isEmpty()) {
            out.println("No necromancers in the Dominion.");
            return;
        }
        out.printf("%-4s %-16s %5s %6s %6s %6s %8s%n", "id", "name", "level", "soul", "legion", "kills", "status");
        for (Player p : engine.getPlayers()) {
            out.printf("%-4d %-16s %5d %6d %6s %6d %8s%n", p.getId(), p.getName(), p.getLevel(), p.getSoul(),
                    engine.legionSizeOf(p) + "/" + p.legionCap(), p.getKills(),
                    p.getNecromancerId() == null ? "fallen" : "alive");
        }
    }

    private void printDump() {
        out.printf("Tick %d. Map %d x %d with %d obstacles.%n", engine.getTick(),
                engine.getMap().getWidth(), engine.getMap().getHeight(), engine.getMap().getObstacles().size());
        Map<String, Integer> census = new TreeMap<>();
        for (Unit unit : engine.getUnits()) {
            Player owner = engine.getPlayer(unit.getOwnerId());
            String side = unit.getOwnerId() == Unit.NEUTRAL ? "wild" : owner == null ? "?" : owner.getName();
            census.merge(side + " " + unit.getType().label, 1, Integer::sum);
        }
        census.forEach((group, count) -> out.printf("  %3d  %s%n", count, group));
        out.printf("Corpses: %d. Traps: %d.%n", engine.getCorpses().size(), engine.getTraps().size());
    }

    private void purge(String name) {
        Player player = engine.findPlayer(name);
        if (player == null) {
            out.println("No necromancer named '" + name + "'.");
            return;
        }
        server.disconnect(player.getId(), "Purged by the Dominion's keeper");
        out.println("Purged '" + player.getName() + "'.");
    }
}
