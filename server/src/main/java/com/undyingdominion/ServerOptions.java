package com.undyingdominion;

/** Command line options, parsed the Robot Worlds way: every flag optional, unknown flags rejected. */
public class ServerOptions {

    public static final int DEFAULT_PORT = 5000;
    public static final int DEFAULT_MAX_PLAYERS = 8;
    public static final String DEFAULT_MAP = "classpath:/maps/default.properties";
    public static final String DEFAULT_DB = "undying-dominion.db";
    public static final int DEFAULT_GRACE_SECONDS = 30;

    private int port = DEFAULT_PORT;
    private int maxPlayers = DEFAULT_MAX_PLAYERS;
    private String mapLocation = DEFAULT_MAP;
    private Long seed;
    private boolean wildCreatures = true;
    private boolean dashboard = true;
    private String campaignFolder;
    private String database = DEFAULT_DB;
    private String resume;
    private int graceSeconds = DEFAULT_GRACE_SECONDS;
    private boolean mapGiven;

    /** Options built in code rather than read from the command line. */
    public static ServerOptions of(int port, int maxPlayers, String mapLocation, Long seed, boolean wildCreatures) {
        ServerOptions options = new ServerOptions();
        options.port = port;
        options.maxPlayers = maxPlayers;
        options.mapLocation = mapLocation;
        options.seed = seed;
        options.wildCreatures = wildCreatures;
        return options;
    }

    public static ServerOptions parse(String[] args) {
        ServerOptions options = new ServerOptions();
        if (args == null) return options;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "-p", "--port" -> options.port = range(value(args, ++i, flag), flag, 0, 65535);
                case "-mc", "--max-clients" -> options.maxPlayers = range(value(args, ++i, flag), flag, 1, 64);
                case "-m", "--map" -> {
                    options.mapLocation = value(args, ++i, flag);
                    options.mapGiven = true;
                }
                case "--seed" -> options.seed = parseLong(value(args, ++i, flag), flag);
                case "--no-wild" -> options.wildCreatures = false;
                case "--plain" -> options.dashboard = false;
                case "--campaign" -> options.campaignFolder = value(args, ++i, flag);
                case "--db" -> options.database = value(args, ++i, flag);
                case "--resume" -> options.resume = value(args, ++i, flag);
                case "--grace" -> options.graceSeconds = grace(value(args, ++i, flag));
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }
        if (options.resume != null && (options.campaignFolder != null || options.mapGiven)) {
            throw new IllegalArgumentException("--resume takes its world from the save; drop --campaign and --map");
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

    private static int grace(String raw) {
        try {
            int seconds = Integer.parseInt(raw);
            if (seconds >= 0 && seconds <= 3600) return seconds;
        } catch (NumberFormatException e) {
            // refused below, like a value out of range
        }
        throw new IllegalArgumentException("--grace takes 0 to 3600 seconds");
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
    /** A folder holding campaign.json, to play its story instead of a free-for-all. Null if none. */
    public String campaignFolder() { return campaignFolder; }
    /** The name of a saved game to carry on from instead of starting a fresh world. Null if none. */
    public String resume()        { return resume; }
    /** The SQLite file saves are kept in. Relative paths are from the working directory. */
    public String database()      { return database; }
    /** How long a dropped player's place is kept for them to rejoin. Zero removes them on drop. */
    public int graceSeconds()     { return graceSeconds; }
    /** Whether to run the full-screen terminal dashboard. Ignored when there is no interactive terminal. */
    public boolean dashboard()    { return dashboard; }
}
