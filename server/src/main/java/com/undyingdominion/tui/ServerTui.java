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
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
        Supplier<String> lanUrl = inviteLink(ServerTui::lanAddress, port, System::nanoTime);

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
        String invite = lanUrl.get();
        log.add("The Dominion is open on port " + port + ". Players join at http://localhost:" + port
                + (invite == null ? "" : " or " + invite));

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

    /**
     * The header's word for why the world is stopped, or null while it runs. Only a player's pause
     * has a name in {@code by}; every other reason is labelled without one, so a pause with no person
     * behind it (a resumed save waiting, everyone away) can never take the dashboard down.
     */
    static String pauseLabel(JSONObject pause) {
        if (pause == null) return null;
        String reason = pause.optString("reason", "");
        return switch (reason) {
            case "host" -> "by you";
            case "player" -> "by " + pause.optString("by", "a player");
            case "story" -> "a player is reading";
            case "resume" -> "waiting for the saved player";
            case "away" -> "everyone is away";
            default -> reason;
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

    /** How often the invite link is looked up again, so it follows the machine onto a new network. */
    private static final long INVITE_REFRESH_NANOS = 5_000_000_000L;

    /** The link other players open, from {@code address}, looked up again every few seconds. */
    static Supplier<String> inviteLink(Supplier<String> address, int port, LongSupplier nanoClock) {
        return new Supplier<>() {
            private String url;
            private long lookedUpAt;
            private boolean looked;

            @Override
            public synchronized String get() {
                long now = nanoClock.getAsLong();
                if (!looked || now - lookedUpAt >= INVITE_REFRESH_NANOS) {
                    String host = address.get();
                    url = host == null ? null : "http://" + host + ":" + port;
                    lookedUpAt = now;
                    looked = true;
                }
                return url;
            }
        };
    }

    /** Container and virtual-machine bridges, and VPN tunnels: a phone on the same Wi-Fi can't reach them. */
    static boolean invitable(String nicName) {
        return !nicName.matches("(docker|br-|virbr|veth|vmnet|vboxnet|tun|tap|wg|tailscale|utun|ppp).*");
    }

    /**
     * This machine's address on the local network, for inviting players, or null if there is none: the
     * address its default route leaves from, else the first one that isn't a bridge.
     */
    static String lanAddress() {
        // Connecting a UDP socket sends nothing; it only asks which local address the route would use.
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), 53);
            InetAddress local = probe.getLocalAddress();
            NetworkInterface nic = local instanceof Inet4Address ? NetworkInterface.getByInetAddress(local) : null;
            // A full-tunnel VPN owns the default route; its address is no use to a phone, so scan instead.
            if (local.isSiteLocalAddress() && nic != null && invitable(nic.getName())) return local.getHostAddress();
        } catch (IOException | RuntimeException ignored) {
            // no default route; fall back to the interfaces
        }
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || !invitable(nic.getName())) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException | RuntimeException ignored) {
            // no network information; no invite link
        }
        return null;
    }
}
