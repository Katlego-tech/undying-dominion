package com.undyingdominion.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTerminal;
import com.undyingdominion.Dominion;
import com.undyingdominion.ServerOptions;
import com.undyingdominion.game.Player;
import com.undyingdominion.net.AdminConsole;
import com.undyingdominion.net.GameServer;
import org.json.JSONObject;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a Dominion with the full-screen dashboard in the terminal that started it. */
public final class ServerTui {

    private static final long FRAME_MILLIS = 100;

    private ServerTui() {
    }

    public static void run(ServerOptions options) throws IOException, InterruptedException {
        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        InputStream realIn = System.in;
        LogBuffer log = new LogBuffer(500);

        Terminal terminal = new DefaultTerminalFactory(realOut, realIn, StandardCharsets.UTF_8)
                .setForceTextTerminal(true)
                .setUnixTerminalCtrlCBehaviour(UnixLikeTerminal.CtrlCBehaviour.TRAP)
                .createTerminal();
        Screen screen = new TerminalScreen(terminal);

        // From here on, logging lands in the dashboard instead of on top of it.
        PrintStream captured = log.stream();
        System.setOut(captured);
        System.setErr(captured);

        Dominion dominion;
        try {
            dominion = Dominion.raise(options);
        } catch (IOException | RuntimeException e) {
            System.setOut(realOut);
            System.setErr(realErr);
            realErr.println("Could not raise the Dominion: " + e.getMessage());
            log.tail(5).forEach(realErr::println);
            System.exit(1);
            return;
        }

        GameServer server = dominion.server();
        int port = server.port();
        AtomicReference<WorldSnapshot> latest = new AtomicReference<>(WorldSnapshot.EMPTY);
        server.addTickListener(e -> {
            if (e.getTick() % 2 == 0) latest.set(WorldSnapshot.of(e, server.lastTickMillis(),
                    dominion.story() == null ? null : dominion.story().headline(), server::isAway));
        });

        AtomicReference<Boolean> running = new AtomicReference<>(true);
        AdminConsole console = new AdminConsole(server, InputStream.nullInputStream(), captured,
                () -> running.set(false)).withStory(dominion.story()).withSaves(dominion.saves());
        String lanUrl = lanAddress() == null ? null : "http://" + lanAddress() + ":" + port;

        Dashboard[] holder = new Dashboard[1];
        Dashboard dashboard = new Dashboard(dominion.map(), mapName(options), port, options.maxPlayers(), lanUrl, log,
                new Dashboard.Actions() {
                    @Override
                    public void purge(int playerId) {
                        server.runOnLoop(() -> {
                            Player player = server.engine().getPlayer(playerId);
                            if (player != null) server.disconnect(playerId, "Purged by the Dominion's keeper");
                        });
                    }

                    @Override
                    public void openInBrowser() {
                        holder[0].flash(openBrowser("http://localhost:" + port));
                    }

                    @Override
                    public void command(String line) {
                        console.handle(line);
                    }

                    @Override
                    public void quit() {
                        running.set(false);
                    }
                });
        holder[0] = dashboard;
        log.add("The Dominion is open on port " + port + ". Players join at http://localhost:" + port
                + (lanUrl == null ? "" : " or " + lanUrl));

        screen.startScreen();
        screen.setCursorPosition(null);
        try {
            while (running.get()) {
                KeyStroke key;
                while ((key = screen.pollInput()) != null) {
                    dashboard.handle(key);
                }
                dashboard.setSnapshot(latest.get());
                dashboard.setPaused(pauseLabel(server.pause()));
                screen.doResizeIfNecessary();
                dashboard.draw(screen);
                screen.refresh();
                Thread.sleep(FRAME_MILLIS);
            }
        } finally {
            screen.stopScreen();
            System.setOut(realOut);
            System.setErr(realErr);
            dominion.close();
            realOut.println("The Dominion is closed.");
        }
        System.exit(0);
    }

    /** "by Vesper", "by you", "story" for the header; null while the world runs. */
    private static String pauseLabel(JSONObject pause) {
        if (pause == null) return null;
        return switch (pause.getString("reason")) {
            case "host" -> "by you";
            case "story" -> "a player is reading";
            default -> "by " + pause.getString("by");
        };
    }

    private static String mapName(ServerOptions options) {
        String location = options.mapLocation();
        if (options.campaignFolder() != null) return "Campaign";
        if (location.equals(ServerOptions.DEFAULT_MAP)) return "The Sundered Barrow";
        String file = location.substring(location.lastIndexOf('/') + 1);
        return file.replace(".properties", "");
    }

    private static String openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return "Opened " + url + " in your browser.";
            }
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // fall through to telling the keeper the address
        }
        return "No browser available here. Open " + url + " yourself.";
    }

    /** This machine's address on the local network, for inviting players, or null if there is none. */
    static String lanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // no network information; no invite link
        }
        return null;
    }
}
