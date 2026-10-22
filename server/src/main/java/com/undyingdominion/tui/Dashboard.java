package com.undyingdominion.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.tui.WorldSnapshot.CorpseDot;
import com.undyingdominion.tui.WorldSnapshot.PlayerRow;
import com.undyingdominion.tui.WorldSnapshot.TrapDot;
import com.undyingdominion.tui.WorldSnapshot.UnitDot;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Terrain;

import java.util.List;
import java.util.Locale;

/**
 * The server dashboard: a fog-free view of the whole world, the necromancers, and the log, drawn on
 * a Lanterna screen. Holds only display state; everything it can do to the server goes through
 * {@link Actions}.
 */
public class Dashboard {

    /** What the keeper can do from the dashboard. */
    public interface Actions {
        void purge(int playerId);
        void openInBrowser();
        /** Runs a line as an admin console command; its output goes to the log. */
        void command(String line);
        void quit();
    }

    enum Mode { NORMAL, CONFIRM_PURGE, CONFIRM_QUIT, COMMAND }

    static final TextColor BG = rgb("#0e0c12");
    static final TextColor PANEL_EDGE = rgb("#3a3444");
    static final TextColor BONE = rgb("#e8e2d0");
    static final TextColor DIM = rgb("#8f897d");
    static final TextColor FAINT = rgb("#55505c");
    static final TextColor SOUL = rgb("#5eead4");
    static final TextColor VIOLET = rgb("#a78bfa");
    static final TextColor BLOOD = rgb("#f87171");
    static final TextColor GOLD = rgb("#fbbf24");
    static final TextColor HEADER_BG = rgb("#1d1726");
    static final TextColor SELECTED_BG = rgb("#1f3b38");

    private static final TextColor GROUND_A = rgb("#1c1922");
    private static final TextColor GROUND_B = rgb("#201d27");
    private static final TextColor RUINS = rgb("#5d5569");
    private static final TextColor SWAMP = rgb("#1f3d2c");
    private static final TextColor ABYSS = rgb("#2a1245");
    private static final TextColor FOREST = rgb("#14301c");
    private static final TextColor CORPSE = rgb("#9a9488");
    private static final TextColor TRAP = rgb("#f59e0b");

    private GameMap map;
    private final String mapName;
    private final int port;
    private final int maxPlayers;
    private final String lanUrl;
    private final LogBuffer log;
    private final Actions actions;

    private WorldSnapshot snapshot = WorldSnapshot.EMPTY;
    private Mode mode = Mode.NORMAL;
    private int selectedId = -1;
    private final StringBuilder input = new StringBuilder();
    private String status = "";
    private String paused;
    private long statusUntil;

    public Dashboard(GameMap map, String mapName, int port, int maxPlayers, String lanUrl, LogBuffer log, Actions actions) {
        this.map = map;
        this.mapName = mapName;
        this.port = port;
        this.maxPlayers = maxPlayers;
        this.lanUrl = lanUrl;
        this.log = log;
        this.actions = actions;
    }

    public void setSnapshot(WorldSnapshot snapshot) {
        if (snapshot == null) return;
        this.snapshot = snapshot;
        if (snapshot.map() != null) map = snapshot.map();
        List<PlayerRow> players = snapshot.players();
        if (players.stream().noneMatch(p -> p.id() == selectedId)) {
            selectedId = players.isEmpty() ? -1 : players.get(0).id();
        }
    }

    public void flash(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + 4000;
    }

    Mode mode() { return mode; }

    // ------------------------------------------------------------------ input

    public void handle(KeyStroke key) {
        if (key == null) return;
        switch (mode) {
            case NORMAL -> handleNormal(key);
            case CONFIRM_PURGE -> {
                PlayerRow selected = selected();
                if (isChar(key, 'y') && selected != null) {
                    actions.purge(selected.id());
                    flash("Purged " + selected.name() + ".");
                }
                mode = Mode.NORMAL;
            }
            case CONFIRM_QUIT -> {
                if (isChar(key, 'y')) actions.quit();
                mode = Mode.NORMAL;
            }
            case COMMAND -> handleCommand(key);
        }
    }

    private void handleNormal(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ArrowUp || isChar(key, 'k')) move(-1);
        else if (type == KeyType.ArrowDown || isChar(key, 'j')) move(1);
        else if (isChar(key, 'p')) {
            if (selected() == null) flash("No necromancer to purge.");
            else mode = Mode.CONFIRM_PURGE;
        } else if (isChar(key, 'o')) actions.openInBrowser();
        else if (isChar(key, ':')) {
            input.setLength(0);
            mode = Mode.COMMAND;
        } else if (isChar(key, 'q') || type == KeyType.Escape || (key.isCtrlDown() && isChar(key, 'c'))) {
            mode = Mode.CONFIRM_QUIT;
        }
    }

    private void handleCommand(KeyStroke key) {
        switch (key.getKeyType()) {
            case Escape -> mode = Mode.NORMAL;
            case Backspace -> {
                if (!input.isEmpty()) input.setLength(input.length() - 1);
            }
            case Enter -> {
                String line = input.toString().trim();
                mode = Mode.NORMAL;
                if (!line.isEmpty()) {
                    log.add("> " + line);
                    actions.command(line);
                }
            }
            case Character -> {
                if (input.length() < 60) input.append(key.getCharacter());
            }
            default -> { }
        }
    }

    private static boolean isChar(KeyStroke key, char c) {
        return key.getKeyType() == KeyType.Character && Character.toLowerCase(key.getCharacter()) == c;
    }

    private void move(int delta) {
        List<PlayerRow> players = snapshot.players();
        if (players.isEmpty()) return;
        int index = Math.max(0, indexOf(selectedId));
        selectedId = players.get(Math.floorMod(index + delta, players.size())).id();
    }

    private int indexOf(int playerId) {
        List<PlayerRow> players = snapshot.players();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).id() == playerId) return i;
        }
        return -1;
    }

    private PlayerRow selected() {
        int index = indexOf(selectedId);
        return index < 0 ? null : snapshot.players().get(index);
    }

    // ------------------------------------------------------------------ drawing

    public void draw(Screen screen) {
        TerminalSize size = screen.getTerminalSize();
        int width = size.getColumns();
        int height = size.getRows();
        TextGraphics g = screen.newTextGraphics();
        g.setBackgroundColor(BG);
        g.setForegroundColor(BONE);
        g.fill(' ');

        int mapCols = map.getWidth() + 2;
        int topHeight = (map.getHeight() + 1) / 2 + 3;
        int minWidth = mapCols + 44;
        int minHeight = topHeight + 7;
        if (width < minWidth || height < minHeight) {
            g.setForegroundColor(DIM);
            put(g, 2, 1, "Enlarge the terminal to at least " + minWidth + " x " + minHeight + ".", width - 4);
            put(g, 2, 2, "It is " + width + " x " + height + ". Press q to quit.", width - 4);
            return;
        }

        drawHeader(g, width);
        drawWorld(g, 0, 1, mapCols, topHeight);
        drawPlayers(g, mapCols, 1, width - mapCols, topHeight);
        drawLog(g, 0, 1 + topHeight, width, height - topHeight - 2);
        drawFooter(g, height - 1, width);
    }

    /** Who or what stopped the world, shown in the header; null while it runs. */
    public void setPaused(String paused) {
        this.paused = paused;
    }

    private void drawHeader(TextGraphics g, int width) {
        g.setBackgroundColor(HEADER_BG);
        g.setForegroundColor(BONE);
        g.putString(0, 0, " ".repeat(width));
        g.enableModifiers(SGR.BOLD);
        g.putString(1, 0, "UNDYING DOMINION");
        g.disableModifiers(SGR.BOLD);

        int x = 19;
        x = segment(g, x, snapshot.story() != null ? snapshot.story() : mapName, snapshot.story() != null ? GOLD : DIM, width);
        x = segment(g, x, "● open on :" + port, SOUL, width);
        if (paused != null) x = segment(g, x, "PAUSED · " + paused, GOLD, width);
        x = segment(g, x, "tick " + String.format(Locale.ROOT, "%,d", snapshot.tick()), DIM, width);
        x = segment(g, x, String.format(Locale.ROOT, "%.1f ms", snapshot.tickMillis()), snapshot.tickMillis() > 50 ? BLOOD : DIM, width);
        x = segment(g, x, snapshot.players().size() + "/" + maxPlayers + " necromancers", DIM, width);
        if (lanUrl != null) segment(g, x, lanUrl, VIOLET, width);
        g.setBackgroundColor(BG);
    }

    private int segment(TextGraphics g, int x, String text, TextColor color, int width) {
        if (x + text.length() + 3 > width) return width;
        g.setForegroundColor(FAINT);
        g.putString(x, 0, "·");
        g.setForegroundColor(color);
        g.putString(x + 2, 0, text);
        return x + text.length() + 5;
    }

    private void drawWorld(TextGraphics g, int x, int y, int w, int h) {
        box(g, x, y, w, h, "World", "no fog");

        // Two tiles per character cell: the top tile is the foreground of '▀', the bottom its background.
        TextColor[][] tiles = new TextColor[map.getWidth()][map.getHeight()];
        for (int ty = 0; ty < map.getHeight(); ty++) {
            for (int tx = 0; tx < map.getWidth(); tx++) {
                tiles[tx][ty] = terrainColor(map.terrainAt(tx, ty), tx, ty);
            }
        }
        for (CorpseDot c : snapshot.corpses()) paint(tiles, c.x(), c.y(), CORPSE);
        for (TrapDot t : snapshot.traps()) paint(tiles, t.x(), t.y(), TRAP);
        for (UnitDot u : snapshot.units()) paint(tiles, u.x(), u.y(), unitColor(u));

        int rows = h - 3;
        for (int row = 0; row < rows; row++) {
            for (int tx = 0; tx < map.getWidth() && tx < w - 2; tx++) {
                int top = row * 2;
                int bottom = top + 1;
                if (top >= map.getHeight()) break;
                g.setForegroundColor(tiles[tx][top]);
                g.setBackgroundColor(bottom < map.getHeight() ? tiles[tx][bottom] : BG);
                g.setCharacter(x + 1 + tx, y + 1 + row, '▀');
            }
        }
        g.setBackgroundColor(BG);

        int lx = x + 2;
        int ly = y + h - 2;
        lx = legend(g, lx, ly, "■", RUINS, "ruins");
        lx = legend(g, lx, ly, "■", SWAMP, "swamp");
        lx = legend(g, lx, ly, "■", ABYSS, "abyss");
        lx = legend(g, lx, ly, "■", FOREST, "forest");
        lx = legend(g, lx, ly, "■", CORPSE, "corpse");
        legend(g, lx, ly, "■", TRAP, "trap");
    }

    private static void paint(TextColor[][] tiles, int x, int y, TextColor color) {
        if (x >= 0 && y >= 0 && x < tiles.length && y < tiles[0].length) tiles[x][y] = color;
    }

    private TextColor unitColor(UnitDot unit) {
        TextColor owner = rgb(snapshot.colorOf(unit.owner()));
        // Commanders and kings stand out; everything else wears its owner's colour.
        if (unit.type().isCommander()) return BONE;
        if (unit.type() == UnitType.SKELETON_KING) return GOLD;
        return owner;
    }

    private static TextColor terrainColor(Terrain terrain, int x, int y) {
        return switch (terrain) {
            case GROUND -> ((x + y) & 1) == 0 ? GROUND_A : GROUND_B;
            case RUINS -> RUINS;
            case SWAMP -> SWAMP;
            case ABYSS -> ABYSS;
            case FOREST -> FOREST;
        };
    }

    private static int legend(TextGraphics g, int x, int y, String mark, TextColor color, String label) {
        g.setForegroundColor(color);
        g.putString(x, y, mark);
        g.setForegroundColor(DIM);
        g.putString(x + 2, y, label);
        return x + label.length() + 4;
    }

    private void drawPlayers(TextGraphics g, int x, int y, int w, int h) {
        List<PlayerRow> players = snapshot.players();
        box(g, x, y, w, h, "Necromancers", players.size() + "/" + maxPlayers);

        int inner = w - 4;
        g.setForegroundColor(FAINT);
        put(g, x + 2, y + 1, row("NAME", "LV", "SOUL", "LEGION", "K/D", "STATE", inner), inner);

        int maxRows = h - 5;
        if (players.isEmpty()) {
            g.setForegroundColor(DIM);
            put(g, x + 2, y + 3, "Nobody has risen yet.", inner);
            put(g, x + 2, y + 4, "Press o to open the game in a browser.", inner);
        }
        for (int i = 0; i < players.size() && i < maxRows; i++) {
            PlayerRow p = players.get(i);
            boolean selected = p.id() == selectedId;
            int ry = y + 2 + i;
            if (selected) {
                g.setBackgroundColor(SELECTED_BG);
                g.putString(x + 1, ry, " ".repeat(w - 2));
            }
            g.setForegroundColor(rgb(p.color()));
            g.putString(x + 2, ry, "●");
            g.setForegroundColor(p.alive() && !p.away() ? BONE : DIM);
            if (selected) g.enableModifiers(SGR.BOLD);
            put(g, x + 4, ry, row(p.name(), String.valueOf(p.level()), p.soul() + "/" + p.soulMax(),
                    p.legion() + "/" + p.legionCap(), p.kills() + "/" + p.deaths(),
                    p.away() ? "away" : p.alive() ? "alive" : "fallen", inner - 2), inner - 2);
            g.disableModifiers(SGR.BOLD);
            g.setBackgroundColor(BG);
        }

        g.setForegroundColor(DIM);
        put(g, x + 2, y + h - 2, "undead " + snapshot.undeadCount() + " · wild " + snapshot.wildCount()
                + " · corpses " + snapshot.corpses().size() + " · traps " + snapshot.traps().size(), inner);
    }

    /** Lays the table columns out across the available width, giving the name what is left. */
    private static String row(String name, String level, String soul, String legion, String kd, String state, int width) {
        String rest = String.format("%3s %8s %7s %6s %7s", level, soul, legion, kd, state);
        int nameWidth = Math.max(4, width - rest.length() - 1);
        String clipped = name.length() > nameWidth ? name.substring(0, nameWidth - 1) + "…" : name;
        return String.format("%-" + nameWidth + "s %s", clipped, rest);
    }

    private void drawLog(TextGraphics g, int x, int y, int w, int h) {
        box(g, x, y, w, h, "Log", null);
        List<String> lines = log.tail(h - 2);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            g.setForegroundColor(line.contains("WARN") || line.contains("ERROR") ? BLOOD
                    : line.startsWith("> ") ? SOUL : DIM);
            put(g, x + 2, y + 1 + i, line, w - 4);
        }
    }

    private void drawFooter(TextGraphics g, int y, int width) {
        g.setBackgroundColor(HEADER_BG);
        g.putString(0, y, " ".repeat(width));
        PlayerRow selected = selected();
        switch (mode) {
            case CONFIRM_PURGE -> {
                g.setForegroundColor(BLOOD);
                put(g, 1, y, "Purge " + (selected == null ? "?" : selected.name())
                        + "? Their legion crumbles and they are disconnected.  y to confirm, any other key to cancel", width - 2);
            }
            case CONFIRM_QUIT -> {
                g.setForegroundColor(GOLD);
                put(g, 1, y, "Close the Dominion? Everyone is disconnected.  y to confirm, any other key to cancel", width - 2);
            }
            case COMMAND -> {
                g.setForegroundColor(SOUL);
                put(g, 1, y, ":" + input + "█   (players, dump, pause, resume, purge <name>, chapter <n>, save <name>, saves, help · Enter to run, Esc to cancel)", width - 2);
            }
            case NORMAL -> {
                if (System.currentTimeMillis() < statusUntil) {
                    g.setForegroundColor(SOUL);
                    put(g, 1, y, status, width - 2);
                } else {
                    hints(g, y, width);
                }
            }
        }
        g.setBackgroundColor(BG);
    }

    private static void hints(TextGraphics g, int y, int width) {
        int x = 1;
        String[][] keys = {{"↑↓", "select"}, {"p", "purge"}, {"o", "open game"}, {":", "command"}, {"q", "quit"}};
        for (String[] key : keys) {
            if (x + key[0].length() + key[1].length() + 4 > width) break;
            g.setForegroundColor(BONE);
            g.enableModifiers(SGR.BOLD);
            g.putString(x, y, key[0]);
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(DIM);
            g.putString(x + key[0].length() + 1, y, key[1]);
            x += key[0].length() + key[1].length() + 4;
        }
    }

    private static void box(TextGraphics g, int x, int y, int w, int h, String title, String note) {
        g.setForegroundColor(PANEL_EDGE);
        g.setBackgroundColor(BG);
        g.putString(x, y, "┌" + "─".repeat(w - 2) + "┐");
        for (int row = y + 1; row < y + h - 1; row++) {
            g.putString(x, row, "│");
            g.putString(x + w - 1, row, "│");
        }
        g.putString(x, y + h - 1, "└" + "─".repeat(w - 2) + "┘");
        g.setForegroundColor(BONE);
        g.enableModifiers(SGR.BOLD);
        g.putString(x + 2, y, " " + title + " ");
        g.disableModifiers(SGR.BOLD);
        if (note != null) {
            g.setForegroundColor(DIM);
            g.putString(x + w - note.length() - 4, y, " " + note + " ");
        }
    }

    private static void put(TextGraphics g, int x, int y, String text, int maxWidth) {
        if (maxWidth <= 0) return;
        g.putString(x, y, text.length() > maxWidth ? text.substring(0, Math.max(0, maxWidth - 1)) + "…" : text);
    }

    static TextColor rgb(String hex) {
        int n = Integer.parseInt(hex.substring(1), 16);
        return new TextColor.RGB((n >> 16) & 255, (n >> 8) & 255, n & 255);
    }
}
