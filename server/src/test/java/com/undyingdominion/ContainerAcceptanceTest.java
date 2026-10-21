package com.undyingdominion;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The image itself, run the way a host runs it: on 5050, with saves on a volume. A save made in one
 * container is resumed in a brand-new one, which is the bug the brownfields image had (its database
 * lived inside the container). Runs only when {@code -Ddominion.image=<tag>} names an image, as the
 * gate's container check does; docs/design/docker.md.
 */
@EnabledIfSystemProperty(named = "dominion.image", matches = ".+")
class ContainerAcceptanceTest {

    private final String image = System.getProperty("dominion.image");
    private final String volume = "ud-test-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> containers = new ArrayList<>();
    private final List<TestSocket> sockets = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        sockets.forEach(TestSocket::abort);
        for (String container : containers) docker("rm", "-f", container);
        docker("volume", "rm", "-f", volume);
    }

    @Test
    void aSaveOutlivesItsContainer() throws Exception {
        Path trial = Path.of(getClass().getResource("/campaigns/trial/campaign.json").toURI()).getParent();
        docker("volume", "create", volume);

        int first = run("-v", trial + ":/campaign:ro", image, "--campaign", "/campaign", "--seed", "4");
        HttpResponse<String> world = get(first, "/api/world");
        assertEquals(200, world.statusCode());
        assertEquals(20, new JSONObject(world.body()).getInt("width"), "the trial campaign's first map");

        TestSocket vesper = join(first, "Vesper");
        vesper.awaitType("welcome");
        vesper.send("{\"command\":\"save\",\"name\":\"crypt\"}");
        vesper.awaitNotice("Saved \"crypt\": chapter 1, The Yard.");
        vesper.abort();
        docker("rm", "-f", containers.removeLast());

        int second = run("-v", trial + ":/campaign:ro", image, "--resume", "crypt");
        TestSocket someone = join(second, "Someone");
        assertEquals("Vesper", someone.awaitType("welcome").getString("name"), "resumed from the volume");
    }

    // ------------------------------------------------------------------ helpers

    /** Starts a container on a free local port with the test's volume at /data, and waits until it answers. */
    private int run(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("run", "-d", "-p", "127.0.0.1::5050", "-v", volume + ":/data"));
        command.addAll(List.of(args));
        String container = docker(command.toArray(String[]::new)).strip();
        containers.add(container);
        String mapping = docker("port", container, "5050").lines().findFirst().orElseThrow();
        int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try {
                if (get(port, "/api/world").statusCode() == 200) return port;
            } catch (IOException starting) {
                // not listening yet
            }
            Thread.sleep(250);
        }
        return fail("the container did not answer within 60 seconds:\n" + docker("logs", container));
    }

    private TestSocket join(int port, String name) {
        TestSocket socket = TestSocket.join(port, name);
        sockets.add(socket);
        return socket;
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Runs the docker CLI and returns what it printed. A failure fails the test with its output. */
    private static String docker(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "docker " + String.join(" ", args) + " hung");
        if (process.exitValue() != 0) fail("docker " + String.join(" ", args) + " failed:\n" + output);
        return output;
    }
}
