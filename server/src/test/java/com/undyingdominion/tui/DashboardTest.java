package com.undyingdominion.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.undyingdominion.game.Command;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.net.AdminConsole;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashboardTest {

    private final List<String> calls = new ArrayList<>();
    private GameEngine engine;
    private Dashboard dashboard;
    private LogBuffer log;

    @BeforeEach
    void setUp() throws IOException {
        GameMap map = MapConfig.load("classpath:/maps/default.properties");
        engine = new GameEngine(map, GameRules.defaults(), new Random(5));
        for (String name : List.of("Vesper", "Corvin")) {
            engine.submit(new Command.Join(engine.allocatePlayerId(), name));
        }
        for (int i = 0; i < 120; i++) engine.tick();

        log = new LogBuffer(100);
        log.add("12:00:01 INFO GameServer - Vesper joined the Dominion");
        dashboard = new Dashboard(map, "The Sundered Barrow", 5000, 8, () -> "http://192.168.1.5:5000", log,
                new Dashboard.Actions() {
                    public void purge(int playerId) { calls.add("purge " + playerId); }
                    public void openInBrowser() { calls.add("open"); }
                    public void command(String line) { calls.add("command " + line); }
                    public void quit() { calls.add("quit"); }
                    public void cycleWorld(int delta) { calls.add("world " + delta); }
                });
        dashboard.setSnapshot(WorldSnapshot.of(engine, 1.5));
    }

    @Test
    void drawsTheWorldThePlayersAndTheLog() throws IOException {
        String screen = render(140, 42);

        assertTrue(screen.contains("UNDYING DOMINION"));
        assertTrue(screen.contains("open on :5000"));
        assertTrue(screen.contains("World"));
        assertTrue(screen.contains("Necromancers"));
        assertTrue(screen.contains("Vesper"));
        assertTrue(screen.contains("Corvin"));
        assertTrue(screen.contains("Vesper joined the Dominion"));
        assertTrue(screen.contains("▀"), "the map is drawn in half blocks");
    }

    @Test
    void theLegionColumnCountsTheRaisedBesideTheSummoned() throws IOException {
        int vesper = engine.getPlayers().iterator().next().getId();
        engine.submit(new Command.Restore(vesper, 1, 0, 0, 0, List.of(),
                List.of(UnitType.SKELETON_WARRIOR, UnitType.SKELETON_WARRIOR)));
        engine.tick();
        dashboard.setSnapshot(WorldSnapshot.of(engine, 1.5));

        assertTrue(render(140, 42).contains("0/10+2"), "two raised beside an empty legion");
        assertEquals("0/10 +2 raised", AdminConsole.legionColumn(0, 10, 2));
        assertEquals("3/10", AdminConsole.legionColumn(3, 10, 0));
    }

    @Test
    void asksForABiggerTerminalWhenItDoesNotFit() throws IOException {
        assertTrue(render(60, 20).contains("Enlarge the terminal"));
    }

    @Test
    void purgingTheSelectedNecromancerNeedsConfirming() {
        int first = engine.getPlayers().iterator().next().getId();

        dashboard.handle(KeyStroke.fromString("p"));
        assertEquals(Dashboard.Mode.CONFIRM_PURGE, dashboard.mode());
        dashboard.handle(KeyStroke.fromString("y"));

        assertEquals(List.of("purge " + first), calls);
    }

    @Test
    void anyOtherKeyCancelsAPurge() {
        dashboard.handle(KeyStroke.fromString("p"));
        dashboard.handle(KeyStroke.fromString("n"));

        assertTrue(calls.isEmpty());
        assertEquals(Dashboard.Mode.NORMAL, dashboard.mode());
    }

    @Test
    void theSelectionMovesWithTheArrowKeys() {
        dashboard.handle(new KeyStroke(KeyType.ArrowDown));
        dashboard.handle(KeyStroke.fromString("p"));
        dashboard.handle(KeyStroke.fromString("y"));

        int second = engine.getPlayers().stream().skip(1).findFirst().orElseThrow().getId();
        assertEquals(List.of("purge " + second), calls);
    }

    @Test
    void typedCommandsRunThroughTheAdminConsole() {
        dashboard.handle(KeyStroke.fromString(":"));
        for (char c : "dump".toCharArray()) dashboard.handle(new KeyStroke(c, false, false));
        dashboard.handle(new KeyStroke(KeyType.Enter));

        assertEquals(List.of("command dump"), calls);
        assertTrue(log.tail(1).get(0).contains("> dump"));
    }

    @Test
    void quittingNeedsConfirming() {
        dashboard.handle(KeyStroke.fromString("q"));
        assertTrue(calls.isEmpty());
        dashboard.handle(KeyStroke.fromString("y"));

        assertEquals(List.of("quit"), calls);
    }

    @Test
    void onAHubTheWorldsLineNamesEveryWorldAndMarksTheOneDrawn() throws IOException {
        dashboard.setWorlds(List.of("Free-for-all 2", "Campaign · Lin", "Campaign · Mara"), 1);
        dashboard.setCapacity(1);

        String screen = render(140, 42);
        String worlds = line(screen, "Worlds");
        assertTrue(worlds.contains("Free-for-all 2"), worlds);
        assertTrue(worlds.contains("▸ Campaign · Lin"), "the world drawn is marked: " + worlds);
        assertTrue(worlds.contains("Campaign · Mara"), worlds);
        assertFalse(worlds.contains("▸ Free-for-all"), worlds);
        assertTrue(line(screen, "UNDYING DOMINION").contains("2/1 necromancers"), "the header counts against the drawn world's seats");
        assertTrue(screen.contains("[ ] world"), "the footer says how to change it");
    }

    @Test
    void theBracketsAskForThePreviousOrNextWorld() {
        dashboard.setWorlds(List.of("Free-for-all 2", "Campaign · Lin"), 0);

        dashboard.handle(KeyStroke.fromString("]"));
        dashboard.handle(KeyStroke.fromString("["));

        assertEquals(List.of("world 1", "world -1"), calls);
    }

    @Test
    void aSingleWorldServerHasNoWorldsLineAndTheBracketsDoNothing() throws IOException {
        dashboard.handle(KeyStroke.fromString("]"));

        String screen = render(140, 42);
        assertTrue(calls.isEmpty());
        assertFalse(screen.contains("Worlds"));
        assertFalse(screen.contains("[ ] world"));
    }

    /** The screen row holding {@code text}, or a failure naming it. */
    private static String line(String screen, String text) {
        return screen.lines().filter(l -> l.contains(text)).findFirst()
                .orElseGet(() -> org.junit.jupiter.api.Assertions.fail("no line with " + text + " in\n" + screen));
    }

    private String render(int columns, int rows) throws IOException {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(columns, rows));
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        dashboard.draw(screen);
        screen.refresh();
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                text.append(terminal.getCharacter(col, row).getCharacterString());
            }
            text.append('\n');
        }
        screen.stopScreen();
        return text.toString();
    }
}
