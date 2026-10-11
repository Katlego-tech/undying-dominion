package com.undyingdominion;

/** Command line options, parsed the Robot Worlds way: every flag optional, unknown flags rejected. */
public class ServerOptions {

    public static final int DEFAULT_PORT = 5000;
    public static final int DEFAULT_MAX_PLAYERS = 8;
    public static final String DEFAULT_MAP = "classpath:/maps/default.properties";

    private int port = DEFAULT_PORT;
    private int maxPlayers = DEFAULT_MAX_PLAYERS;
    private String mapLocation = DEFAULT_MAP;
    private Long seed;
    private boolean wildCreatures = true;

    public static ServerOptions parse(String[] args) {
        ServerOptions options = new ServerOptions();
        if (args == null) return options;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "-p", "--port" -> options.port = range(value(args, ++i, flag), flag, 0, 65535);
                case "-mc", "--max-clients" -> options.maxPlayers = range(value(args, ++i, flag), flag, 1, 64);
                case "-m", "--map" -> options.mapLocation = value(args, ++i, flag);
                case "--seed" -> options.seed = parseLong(value(args, ++i, flag), flag);
                case "--no-wild" -> options.wildCreatures = false;
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }
        return options;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for option: " + flag);
        }
        return args[index];
    }

    private static int range(String raw, String flag, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be an integer, got: " + raw);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(flag + " must be " + min + ".." + max + ", got " + parsed);
        }
        return parsed;
    }

    private static long parseLong(String raw, String flag) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be an integer, got: " + raw);
        }
    }

    public int port()             { return port; }
    public int maxPlayers()       { return maxPlayers; }
    public String mapLocation()   { return mapLocation; }
    public Long seed()            { return seed; }
    public boolean wildCreatures(){ return wildCreatures; }
}
