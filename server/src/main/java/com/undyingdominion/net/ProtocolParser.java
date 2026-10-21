package com.undyingdominion.net;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.world.Position;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a client's JSON message into a {@link Command}. Every message names a {@code command};
 * the other fields depend on it. See the protocol section of the README.
 */
public final class ProtocolParser {

    public static final String UNSUPPORTED = "Unsupported command";
    public static final String BAD_ARGUMENTS = "Could not parse arguments";

    private static final int MAX_UNITS_PER_COMMAND = 200;

    private ProtocolParser() {
    }

    /** Reads the command name, or fails if the message is not a JSON object with one. */
    public static String commandName(JSONObject message) throws ProtocolException {
        String command = message.optString("command", "").trim().toLowerCase();
        if (command.isEmpty()) throw new ProtocolException(UNSUPPORTED);
        return command;
    }

    public static JSONObject read(String raw) throws ProtocolException {
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            throw new ProtocolException(BAD_ARGUMENTS);
        }
    }

    /** Parses any command other than join, which the server handles before the player exists. */
    public static Command parse(JSONObject message, int playerId) throws ProtocolException {
        String command = commandName(message);
        try {
            return switch (command) {
                case "summon" -> new Command.Summon(playerId, UnitType.valueOf(message.getString("unit").toUpperCase()));
                case "move" -> new Command.Move(playerId, unitIds(message),
                        new Position(message.getInt("x"), message.getInt("y")),
                        message.optBoolean("attackMove", false));
                case "attack" -> new Command.Attack(playerId, unitIds(message), message.getInt("target"));
                case "stop" -> new Command.Stop(playerId, unitIds(message));
                case "raise" -> new Command.Raise(playerId, message.getInt("corpse"));
                case "explode" -> new Command.Explode(playerId, message.getInt("corpse"));
                case "fuse" -> new Command.Fuse(playerId, unitIds(message));
                case "mend" -> new Command.Mend(playerId, unitIds(message));
                case "trap" -> new Command.SetTrap(playerId, unitIds(message));
                case "dismiss" -> new Command.Dismiss(playerId, unitIds(message));
                default -> throw new ProtocolException(UNSUPPORTED);
            };
        } catch (JSONException | IllegalArgumentException e) {
            throw new ProtocolException(BAD_ARGUMENTS);
        }
    }

    private static List<Integer> unitIds(JSONObject message) throws ProtocolException {
        JSONArray array = message.getJSONArray("units");
        if (array.length() > MAX_UNITS_PER_COMMAND) throw new ProtocolException(BAD_ARGUMENTS);
        List<Integer> ids = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            ids.add(array.getInt(i));
        }
        return ids;
    }

    /**
     * Cleans up a requested necromancer name: trimmed, 1 to 16 letters, digits, spaces,
     * underscores or hyphens.
     */
    public static String validName(String requested) throws ProtocolException {
        String name = requested == null ? "" : requested.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 16 || !name.matches("[\\p{L}\\p{N} _-]+")) {
            throw new ProtocolException("Names are 1 to 16 letters, digits, spaces, _ or -");
        }
        return name;
    }
}
