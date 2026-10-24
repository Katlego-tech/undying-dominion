package com.undyingdominion.net;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The Dominion announcing itself on the local network every two seconds, so the Android app can list
 * it without anyone typing an address (docs/design/android.md §6 Beacon).
 */
public final class Beacon implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Beacon.class);
    public static final int DEFAULT_PORT = 5001;
    private static final long PERIOD_MILLIS = 2000;

    private final DatagramSocket socket;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("beacon").unstarted(r));
    private boolean warned;

    private Beacon(DatagramSocket socket) {
        this.socket = socket;
    }

    /**
     * Starts announcing {@code payload} to {@code port} on each of {@code targets}, now and every two
     * seconds. The targets are read again each time, so a network that comes up later is heard too. A network that refuses the datagrams costs one warning in the log, never the game.
     */
    public static Beacon start(int port, Supplier<List<InetAddress>> targets, Supplier<String> payload) throws SocketException {
        DatagramSocket socket = new DatagramSocket();
        socket.setBroadcast(true);
        Beacon beacon = new Beacon(socket);
        beacon.timer.scheduleAtFixedRate(() -> beacon.announce(port, targets, payload), 0, PERIOD_MILLIS, TimeUnit.MILLISECONDS);
        return beacon;
    }

    /**
     * What the app reads: who is hosting, where to connect, the modes a player can start there, and how
     * full it is. On a hub {@code players} and {@code max} count every world (docs/design/modes.md).
     */
    public static String payload(String name, int port, List<String> modes, long players, int max) {
        return new JSONObject()
                .put("game", "undying-dominion")
                .put("v", 1)
                .put("name", name)
                .put("port", port)
                .put("modes", modes)
                .put("players", players)
                .put("max", max)
                .toString();
    }

    /** The whole local network, then each up interface's own broadcast address, so a hotspot's network hears it too. */
    public static List<InetAddress> targets() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            targets.add(InetAddress.getByAddress(new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255}));
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null && !targets.contains(address.getBroadcast())) {
                        targets.add(address.getBroadcast());
                    }
                }
            }
        } catch (IOException e) {
            // no interface list: the all-ones address alone
        }
        return targets;
    }

    private void announce(int port, Supplier<List<InetAddress>> targets, Supplier<String> payload) {
        try {
            byte[] bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : targets.get()) {
                try {
                    socket.send(new DatagramPacket(bytes, bytes.length, target, port));
                } catch (IOException e) {
                    if (!warned) log.warn("The beacon can't reach {}: {}. Players can still type the address.", target, e.getMessage());
                    warned = true;
                }
            }
        } catch (RuntimeException e) {
            log.warn("The beacon skipped an announcement: {}", e.toString());
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        socket.close();
    }
}
