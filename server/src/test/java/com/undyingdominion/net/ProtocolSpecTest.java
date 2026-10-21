package com.undyingdominion.net;

import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.TestSocket;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the protocol spec served at /asyncapi.json true to the server, both ways: every command it
 * documents is understood by a real server, and every field a real server sends is documented,
 * with every documented required field present.
 */
class ProtocolSpecTest {

    private static final Set<String> NOT_UNDERSTOOD =
            Set.of("Unsupported command", "Could not parse arguments", "Join the Dominion first");

    @TempDir
    Path dir;

    private final JSONObject spec = loadSpec();
    private final JSONObject messages = spec.getJSONObject("components").getJSONObject("messages");
    private Dominion dominion;
    private final List<TestSocket> sockets = new ArrayList<>();
    private final Set<String> undocumented = new TreeSet<>();

    @AfterEach
    void stop() {
        sockets.forEach(TestSocket::abort);
        if (dominion != null) dominion.close();
    }

    @Test
    void everyDocumentedCommandIsUnderstood() throws Exception {
        dominion = Dominion.raise(ServerOptions.parse(new String[] {"-p", "0", "--db", db(), "--no-wild", "--seed", "4"}));
        TestSocket player = socket(example("join"));
        player.awaitType("welcome");

        for (String name : clientMessages()) {
            if (name.equals("join")) continue;
            TestSocket socket = name.equals("rejoin") ? socket(example(name)) : player;
            if (socket == player) player.send(example(name));
            for (JSONObject reply : socket.collect(300)) {
                if (reply.getString("type").equals("error")) {
                    assertFalse(NOT_UNDERSTOOD.contains(reply.getString("message")),
                            "the server doesn't understand the documented " + name + ": " + reply);
                }
            }
        }
    }

    @Test
    void everythingAFreeForAllSendsIsDocumented() throws Exception {
        dominion = Dominion.raise(ServerOptions.parse(new String[] {"-p", "0", "--db", db(), "--seed", "4"}));
        TestSocket player = socket(example("join"));
        player.send(new JSONObject().put("command", "summon").put("unit", "SKELETON_WARRIOR"));
        player.awaitState(m -> m.getJSONArray("units").length() > 1);
        player.send(new JSONObject().put("command", "pause"));
        player.awaitState(m -> m.has("paused"));
        player.send(new JSONObject().put("command", "join").put("name", "Again"));
        player.awaitType("error");
        player.collect(300);

        List<JSONObject> received = player.history();
        assertTrue(received.stream().anyMatch(m -> m.getString("type").equals("welcome")), "the welcome is checked too");
        checkAll(received);
        assertTrue(undocumented.isEmpty(), "sent by the server but not in asyncapi.json: " + undocumented);
    }

    @Test
    void everythingACampaignSendsIsDocumented() throws Exception {
        dominion = Dominion.raise(ServerOptions.parse(new String[] {
                "-p", "0", "--db", db(), "--seed", "4", "--campaign", trialCampaign().toString()}));
        TestSocket player = socket(example("join"));
        player.awaitType("story");
        player.awaitState(m -> m.has("story"));
        player.collect(300);

        checkAll(player.history());
        assertTrue(undocumented.isEmpty(), "sent by the server but not in asyncapi.json: " + undocumented);
    }

    // ------------------------------------------------------------------ checking against the schema

    private void checkAll(List<JSONObject> received) {
        assertFalse(received.isEmpty());
        for (JSONObject message : received) {
            String type = message.getString("type");
            assertTrue(serverMessages().contains(type), "an undocumented message type: " + type);
            check(message, messages.getJSONObject(type).getJSONObject("payload"), type);
        }
    }

    /** Walks a value against its schema: its type, const and enum, and every field, noting any the schema doesn't name. */
    private void check(Object value, JSONObject schema, String path) {
        if (schema.has("const")) assertEquals(schema.get("const"), value, path + " is not its const");
        if (schema.has("enum")) {
            assertTrue(schema.getJSONArray("enum").toList().contains(value), path + " = " + value + " is not in its enum");
        }
        if (schema.has("type")) {
            Object type = schema.get("type");
            List<Object> allowed = type instanceof JSONArray types ? types.toList() : List.of(type);
            assertTrue(allowed.contains(jsonType(value)), path + " is " + jsonType(value) + ", the spec says " + type);
        }
        if (value instanceof JSONObject object) {
            JSONObject properties = schema.optJSONObject("properties");
            JSONObject additional = schema.optJSONObject("additionalProperties");
            JSONArray required = schema.optJSONArray("required");
            if (required != null) {
                for (int i = 0; i < required.length(); i++) {
                    assertTrue(object.has(required.getString(i)), path + "." + required.getString(i) + " is required but missing");
                }
            }
            for (String key : object.keySet()) {
                JSONObject field = properties != null ? properties.optJSONObject(key) : null;
                if (field == null) field = additional;
                if (field == null) undocumented.add(path + "." + key);
                else check(object.get(key), field, path + "." + key);
            }
        } else if (value instanceof JSONArray array && schema.has("items")) {
            for (int i = 0; i < array.length(); i++) check(array.get(i), schema.getJSONObject("items"), path + "[]");
        }
    }

    private static String jsonType(Object value) {
        if (value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer || value instanceof Long) return "integer";
        if (value instanceof Number) return "number";
        return "string";
    }

    // ------------------------------------------------------------------ the spec

    private List<String> clientMessages() {
        return operationMessages("send");
    }

    private List<String> serverMessages() {
        return operationMessages("receive");
    }

    private List<String> operationMessages(String action) {
        List<String> names = new ArrayList<>();
        JSONObject operations = spec.getJSONObject("operations");
        for (String key : operations.keySet()) {
            JSONObject operation = operations.getJSONObject(key);
            if (!operation.getString("action").equals(action)) continue;
            String ref = operation.getJSONArray("messages").getJSONObject(0).getString("$ref");
            names.add(ref.substring(ref.lastIndexOf('/') + 1));
        }
        assertFalse(names.isEmpty(), "no " + action + " operations in the spec");
        return names;
    }

    private JSONObject example(String message) {
        return messages.getJSONObject(message).getJSONArray("examples").getJSONObject(0).getJSONObject("payload");
    }

    private static JSONObject loadSpec() {
        try (InputStream in = ProtocolSpecTest.class.getResourceAsStream("/public/asyncapi.json")) {
            JSONObject spec = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("3.0.0", spec.getString("asyncapi"));
            return spec;
        } catch (Exception e) {
            throw new AssertionError("asyncapi.json could not be read", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private TestSocket socket(JSONObject first) {
        TestSocket socket = TestSocket.open(dominion.server().port());
        sockets.add(socket);
        socket.send(first);
        return socket;
    }

    private String db() {
        return dir.resolve("saves.db").toString();
    }

    private Path trialCampaign() throws Exception {
        Path source = Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent();
        Path campaign = dir.resolve("trial");
        Files.createDirectories(campaign.resolve("maps"));
        for (String file : List.of("campaign.json", "maps/yard.properties", "maps/hall.properties")) {
            Files.copy(source.resolve(file), campaign.resolve(file));
        }
        return campaign;
    }
}
