package com.undyingdominion.world;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Loads a {@link GameMap} from a properties file, in the same shape Robot Worlds used:
 *
 * <pre>
 * map.width=48
 * map.height=32
 * obstacle.ruins.1=10,4,12,9
 * obstacle.swamp.1=20,20,26,24
 * obstacle.abyss.1=30,10,31,16
 * </pre>
 *
 * Coordinates are {@code x1,y1,x2,y2}, inclusive.
 */
public final class MapConfig {

    private MapConfig() {
    }

    /** Reads a map from disk, or from the classpath when the path starts with {@code classpath:}. */
    public static GameMap load(String location) throws IOException {
        Properties props = new Properties();
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            try (InputStream in = MapConfig.class.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("No map on the classpath at " + resource);
                props.load(in);
            }
        } else {
            try (InputStream in = Files.newInputStream(Path.of(location))) {
                props.load(in);
            }
        }
        return fromProperties(props);
    }

    public static GameMap fromProperties(Properties props) {
        int width = Integer.parseInt(required(props, "map.width").trim());
        int height = Integer.parseInt(required(props, "map.height").trim());

        List<Obstacle> obstacles = new ArrayList<>();
        props.stringPropertyNames().stream()
                .filter(key -> key.startsWith("obstacle."))
                .sorted()
                .forEach(key -> obstacles.add(parseObstacle(key, props.getProperty(key))));

        return new GameMap(width, height, obstacles);
    }

    static Obstacle parseObstacle(String key, String value) {
        String[] keyParts = key.split("\\.");
        if (keyParts.length < 2) {
            throw new IllegalArgumentException("Obstacle key must be obstacle.<terrain>.<n>: " + key);
        }
        Terrain terrain = Terrain.valueOf(keyParts[1].toUpperCase());

        String[] coords = value.split(",");
        if (coords.length != 4) {
            throw new IllegalArgumentException("Invalid obstacle format (expected x1,y1,x2,y2): " + value);
        }
        return new Obstacle(
                Integer.parseInt(coords[0].trim()), Integer.parseInt(coords[1].trim()),
                Integer.parseInt(coords[2].trim()), Integer.parseInt(coords[3].trim()),
                terrain);
    }

    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Map is missing " + key);
        return value;
    }
}
