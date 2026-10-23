package com.undyingdominion.net;

import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Unit;
import com.undyingdominion.persistence.SaveService;
import com.undyingdominion.persistence.SaveSummary;
import com.undyingdominion.story.StoryDirector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The server operator's console, carried over from Robot Worlds' world commands:
 * {@code players} (was {@code robots}), {@code dump}, {@code purge <name>} and {@code quit}.
 */
public class AdminConsole implements Runnable {

    private final GameServer server;
    private final InputStream in;
    private final PrintStream out;
    private final Runnable onQuit;
    private StoryDirector story;
    private SaveService saves;

    public AdminConsole(GameServer server, InputStream in, PrintStream out, Runnable onQuit) {
        this.server = server;
        this.in = in;
        this.out = out;
        this.onQuit = onQuit;
    }

    /** Enables the {@code chapter} command when the server is playing a campaign. */
    public AdminConsole withStory(StoryDirector story) {
        this.story = story;
        return this;
    }

    /** Enables {@code save} and {@code saves}. */
    public AdminConsole withSaves(SaveService saves) {
        this.saves = saves;
        return this;
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
    public boolean handle(String line) {
        if (line.isEmpty()) return true;
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";

        switch (command) {
            case "help" -> out.println("""
                    Commands:
                      players        list necromancers and their standing
                      dump           show the state of the world
                      pause          stop the world for everyone
                      resume         set the world running again
                      purge <name>   destroy a necromancer's legion and remove them
                      chapter <n>    jump the campaign to chapter n
                      save <name>    save a solo campaign (save --force <name> replaces one)
                      saves          list saved games
                      quit           close the Dominion""");
            case "players" -> server.runOnLoop(this::printPlayers);
            case "dump" -> server.runOnLoop(this::printDump);
            case "pause" -> {
                server.pauseByHost();
                out.println("The Dominion is paused. Type resume to go on.");
            }
            case "resume" -> {
                server.resume();
                out.println("The Dominion resumes.");
            }
            case "purge" -> {
                if (argument.isEmpty()) {
                    out.println("usage: purge <name>");
                } else {
                    server.runOnLoop(() -> purge(argument));
                }
            }
            case "chapter" -> {
                if (story == null) {
                    out.println("This Dominion is not playing a campaign.");
                } else {
                    server.runOnLoop(() -> {
                        try {
                            story.jumpTo(Integer.parseInt(argument));
                            out.println("Chapter " + argument + ": " + story.current().title());
                        } catch (IllegalArgumentException e) {
                            out.println("usage: chapter <n>. " + e.getMessage());
                        }
                    });
                }
            }
            case "save" -> save(argument);
            case "saves" -> printSaves();
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
        GameEngine engine = server.engine();
        if (engine.getPlayers().isEmpty()) {
            out.println("No necromancers in the Dominion.");
            return;
        }
        out.printf("%-4s %-16s %5s %6s %-15s %6s %8s%n", "id", "name", "level", "soul", "legion", "kills", "status");
        for (Player p : engine.getPlayers()) {
            out.printf("%-4d %-16s %5d %6d %-15s %6d %8s%n", p.getId(), p.getName(), p.getLevel(), p.getSoul(),
                    legionColumn(engine.legionSizeOf(p), p.legionCap(), engine.raisedSizeOf(p)), p.getKills(),
                    p.getNecromancerId() == null ? "fallen" : "alive");
        }
    }

    /** "3/10", or "3/10 +2 raised" when some of the legion was raised, which takes no room in it. */
    public static String legionColumn(int legion, int cap, int raised) {
        return legion + "/" + cap + (raised > 0 ? " +" + raised + " raised" : "");
    }

    private void printDump() {
        GameEngine engine = server.engine();
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

    private void save(String argument) {
        if (saves == null) {
            out.println("Saving is not available here.");
            return;
        }
        boolean force = argument.startsWith("--force");
        String name = force ? argument.substring("--force".length()).trim() : argument;
        if (name.isEmpty()) {
            out.println("usage: save <name>, or save --force <name> to replace one");
            return;
        }
        server.runOnLoop(() -> saves.save(name, force, message -> out.println(message.equals(SaveService.nameTaken(name))
                ? "A save called \"" + name + "\" already exists. Use save --force " + name + " to replace it"
                : message)));
    }

    private void printSaves() {
        if (saves == null) {
            out.println("Saving is not available here.");
            return;
        }
        try {
            List<SaveSummary> list = saves.list();
            if (list.isEmpty()) out.println("No saved games.");
            DateTimeFormatter when = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
            for (SaveSummary s : list) {
                out.println(s.name() + " · " + s.campaignTitle() + " · chapter " + s.chapter() + " · " + when.format(s.savedAt()));
            }
        } catch (RuntimeException e) {
            out.println("The saves could not be read: " + e.getMessage());
        }
    }

    private void purge(String name) {
        GameEngine engine = server.engine();
        Player player = engine.findPlayer(name);
        if (player == null) {
            out.println("No necromancer named '" + name + "'.");
            return;
        }
        server.disconnect(player.getId(), "Purged by the Dominion's keeper");
        out.println("Purged '" + player.getName() + "'.");
    }
}
