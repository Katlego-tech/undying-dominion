package com.undyingdominion.tools;

import com.undyingdominion.game.UnitType;
import com.undyingdominion.story.Campaign;
import com.undyingdominion.story.Campaign.Chapter;
import com.undyingdominion.story.Campaign.Spawn;
import com.undyingdominion.story.Campaign.Trigger;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.MapConfig;
import com.undyingdominion.world.Terrain;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws maps to PNG files, so they can be looked at without running a game.
 *
 * <pre>
 * java -cp undying-dominion.jar com.undyingdominion.tools.MapRenderer map.properties out.png
 * java -cp undying-dominion.jar com.undyingdominion.tools.MapRenderer --campaign story/arc1 out-folder
 * </pre>
 *
 * With a campaign, every chapter is drawn with its starting ground and where its creatures appear.
 */
public final class MapRenderer {

    private static final int TILE = 22;
    private static final int HEADER = 64;
    private static final int FOOTER = 40;

    private static final Color BG = new Color(0x0b0a0e);
    private static final Color BONE = new Color(0xe8e2d0);
    private static final Color DIM = new Color(0x9d978a);
    private static final Color SOUL = new Color(0x5eead4);
    private static final Color GOLD = new Color(0xfbbf24);
    private static final Color GROUND = new Color(0x17151c);
    private static final Color RUINS = new Color(0x3a3542);
    private static final Color SWAMP = new Color(0x15241c);
    private static final Color ABYSS = new Color(0x050308);
    private static final Color FOREST = new Color(0x112418);

    private static final Map<UnitType.Look, Color> LOOK_COLORS = Map.of(
            UnitType.Look.BEAST, new Color(0xc2a27a),
            UnitType.Look.GOBLIN, new Color(0x84cc16),
            UnitType.Look.PLANT, new Color(0x4ade80),
            UnitType.Look.HUMANOID, new Color(0x94a3b8),
            UnitType.Look.BEASTMAN, new Color(0xf59e0b),
            UnitType.Look.DEMON, new Color(0xf43f5e),
            UnitType.Look.UNDEAD, BONE);

    private MapRenderer() {
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        if (args.length == 3 && args[0].equals("--campaign")) {
            Campaign campaign = Campaign.load(Path.of(args[1]));
            Path out = Path.of(args[2]);
            Files.createDirectories(out);
            List<Chapter> chapters = campaign.chapters();
            for (int i = 0; i < chapters.size(); i++) {
                Chapter chapter = chapters.get(i);
                Path file = out.resolve(String.format("%02d-%s.png", i + 1, chapter.id()));
                write(render(campaign.loadMap(chapter), "Chapter " + (i + 1) + ": " + chapter.title(),
                        chapter.source() + " · " + chapter.objective().text(), chapter), file);
                System.out.println("Rendered " + file);
            }
        } else if (args.length == 2) {
            GameMap map = MapConfig.load(args[0]);
            write(render(map, Path.of(args[0]).getFileName().toString(), map.getWidth() + " x " + map.getHeight(), null),
                    Path.of(args[1]));
            System.out.println("Rendered " + args[1]);
        } else {
            System.err.println("Usage: MapRenderer <map.properties> <out.png> | --campaign <folder> <out-folder>");
            System.exit(2);
        }
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        ImageIO.write(image, "png", file.toFile());
    }

    /** Draws a map, and a chapter's start and creatures when one is given. */
    public static BufferedImage render(GameMap map, String title, String subtitle, Chapter chapter) {
        int width = map.getWidth() * TILE;
        int height = map.getHeight() * TILE;
        BufferedImage image = new BufferedImage(width, height + HEADER + FOOTER, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        g.setColor(BONE);
        g.setFont(new Font(Font.SERIF, Font.BOLD, 24));
        g.drawString(title, 16, 32);
        g.setColor(DIM);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString(subtitle, 16, 52);

        g.translate(0, HEADER);
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) tile(g, map, x, y);
        }
        g.setColor(new Color(255, 255, 255, 10));
        for (int x = 0; x <= map.getWidth(); x++) g.drawLine(x * TILE, 0, x * TILE, height);
        for (int y = 0; y <= map.getHeight(); y++) g.drawLine(0, y * TILE, width, y * TILE);

        if (chapter != null) overlay(g, chapter);
        g.translate(0, -HEADER);
        legend(g, height + HEADER + 26);
        g.dispose();
        return image;
    }

    private static void tile(Graphics2D g, GameMap map, int x, int y) {
        int px = x * TILE, py = y * TILE;
        long seed = (x * 73856093L) ^ (y * 19349663L);
        double r = ((seed % 1000) + 1000) % 1000 / 1000.0;
        switch (map.terrainAt(x, y)) {
            case GROUND -> {
                g.setColor(shade(GROUND, r * 0.08));
                g.fillRect(px, py, TILE, TILE);
            }
            case RUINS -> {
                g.setColor(new Color(0x25212b));
                g.fillRect(px, py, TILE, TILE);
                g.setColor(shade(RUINS, r * 0.08));
                g.fillRect(px + 1, py + 1, TILE - 2, TILE - 2);
                g.setColor(new Color(0x4a4452));
                g.fillRect(px + 1, py + 1, TILE - 2, 2);
            }
            case SWAMP -> {
                g.setColor(shade(SWAMP, r * 0.1));
                g.fillRect(px, py, TILE, TILE);
                g.setColor(new Color(150, 200, 165, 40));
                g.drawOval(px + 4 + (int) (r * 6), py + 8, 9, 4);
            }
            case ABYSS -> {
                g.setColor(ABYSS);
                g.fillRect(px, py, TILE, TILE);
                int[][] sides = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                for (int[] s : sides) {
                    if (map.inBounds(x + s[0], y + s[1]) && map.terrainAt(x + s[0], y + s[1]) != Terrain.ABYSS) {
                        int ex = s[0] == 1 ? px + TILE : px, ey = s[1] == 1 ? py + TILE : py;
                        g.setPaint(new GradientPaint(ex, ey, new Color(124, 58, 237, 110),
                                ex - s[0] * TILE * 0.7f, ey - s[1] * TILE * 0.7f, new Color(124, 58, 237, 0)));
                        g.fillRect(px, py, TILE, TILE);
                    }
                }
            }
            case FOREST -> {
                g.setColor(FOREST);
                g.fillRect(px, py, TILE, TILE);
                g.setColor(shade(new Color(0x1d4429), r * 0.15));
                g.fillOval(px + 2 + (int) (r * 4), py + 2, TILE - 6, TILE - 6);
                g.setColor(new Color(0x2a5c37));
                g.fillOval(px + 6, py + 4 + (int) (r * 3), TILE / 2, TILE / 2);
            }
        }
    }

    private static void overlay(Graphics2D g, Chapter chapter) {
        int[] start = chapter.startArea();
        g.setColor(new Color(94, 234, 212, 40));
        g.fillRect(start[0] * TILE, start[1] * TILE, (start[2] - start[0] + 1) * TILE, (start[3] - start[1] + 1) * TILE);
        g.setColor(SOUL);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));
        g.drawRect(start[0] * TILE, start[1] * TILE, (start[2] - start[0] + 1) * TILE, (start[3] - start[1] + 1) * TILE);
        label(g, "Necromancer rises", start[0] * TILE + 4, start[1] * TILE - 6, SOUL);

        int[] areaLabels = {0};
        for (Spawn spawn : merge(chapter.spawns())) spawn(g, spawn, false, areaLabels);
        List<Spawn> later = new java.util.ArrayList<>();
        for (Trigger trigger : chapter.triggers()) later.addAll(trigger.spawns());
        for (Spawn spawn : merge(later)) spawn(g, spawn, true, areaLabels);
    }

    /** Waves that scatter the same creature over the same area are drawn as one box with the total count. */
    private static List<Spawn> merge(List<Spawn> spawns) {
        Map<String, Spawn> merged = new LinkedHashMap<>();
        int fixed = 0;
        for (Spawn spawn : spawns) {
            String key = spawn.area() == null ? "at" + fixed++ : spawn.unitType() + java.util.Arrays.toString(spawn.area());
            merged.merge(key, spawn, (a, b) -> new Spawn(a.unitType(), a.count() + b.count(), a.area(), a.at()));
        }
        return List.copyOf(merged.values());
    }

    /** {@code areaLabels} counts area labels drawn so far, so overlapping areas stack their labels. */
    private static void spawn(Graphics2D g, Spawn spawn, boolean later, int[] areaLabels) {
        UnitType type = UnitType.valueOf(spawn.unitType());
        Color color = type.boss ? GOLD : LOOK_COLORS.getOrDefault(type.look, BONE);
        String name = (type.level > 0 ? "Lv " + type.level + " " : "") + type.label;
        float[] dash = later ? new float[]{4f, 4f} : null;

        if (spawn.area() != null) {
            int[] a = spawn.area();
            int x = a[0] * TILE, y = a[1] * TILE, w = (a[2] - a[0] + 1) * TILE, h = (a[3] - a[1] + 1) * TILE;
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), later ? 18 : 34));
            g.fillRect(x, y, w, h);
            g.setColor(color);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
            g.drawRect(x, y, w, h);
            label(g, spawn.count() + "× " + name + (later ? " (later)" : ""), x + 4, y + 14 + 16 * (areaLabels[0]++ % 8), color);
        }
        for (int[] p : spawn.at()) {
            int cx = p[0] * TILE + TILE / 2, cy = p[1] * TILE + TILE / 2;
            int r = type.boss ? TILE : TILE / 3;
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
            g.fillOval(cx - r / 2, cy - r / 2, r, r);
            g.setStroke(new BasicStroke(type.boss ? 2.5f : 1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, dash, 0f));
            g.setColor(type.boss ? GOLD : color.darker());
            g.drawOval(cx - r / 2, cy - r / 2, r, r);
            if (type.boss) label(g, "☠ " + name + (later ? " (later)" : ""), cx + r / 2 + 4, cy + 4, GOLD);
        }
        // Label fixed groups of ordinary creatures once, not at every point.
        if (!spawn.at().isEmpty() && !type.boss) {
            int[] first = spawn.at().get(0);
            label(g, spawn.at().size() + "× " + name + (later ? " (later)" : ""),
                    first[0] * TILE + TILE, first[1] * TILE + 4, color);
        }
    }

    private static void label(Graphics2D g, String text, int x, int y, Color color) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics m = g.getFontMetrics();
        g.setColor(new Color(11, 10, 14, 200));
        g.fillRoundRect(x - 3, y - m.getAscent(), m.stringWidth(text) + 6, m.getHeight(), 6, 6);
        g.setColor(color);
        g.drawString(text, x, y);
    }

    private static void legend(Graphics2D g, int y) {
        Map<String, Color> items = new LinkedHashMap<>();
        items.put("ground", GROUND.brighter());
        items.put("ruins", RUINS);
        items.put("swamp", new Color(0x24493a));
        items.put("abyss", new Color(0x3b1d6e));
        items.put("forest", new Color(0x2a5c37));
        items.put("boss", GOLD);
        items.put("start", SOUL);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        int x = 16;
        for (Map.Entry<String, Color> item : items.entrySet()) {
            g.setColor(item.getValue());
            g.fillRect(x, y - 10, 12, 12);
            g.setColor(DIM);
            g.drawString(item.getKey(), x + 18, y);
            x += 18 + g.getFontMetrics().stringWidth(item.getKey()) + 18;
        }
    }

    private static Color shade(Color c, double amount) {
        int d = (int) (amount * 255);
        return new Color(Math.min(255, c.getRed() + d), Math.min(255, c.getGreen() + d), Math.min(255, c.getBlue() + d));
    }
}
