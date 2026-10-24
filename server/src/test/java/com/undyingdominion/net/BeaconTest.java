package com.undyingdominion.net;

import com.undyingdominion.ServerOptions;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Dominion announcing itself so the Android app can find it (docs/design/android.md §6 Beacon). */
class BeaconTest {

    @Test
    void aListenerHearsTheDocumentedFields() throws Exception {
        try (DatagramSocket listener = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            try (Beacon ignored = Beacon.start(listener.getLocalPort(), () -> List.of(InetAddress.getLoopbackAddress()),
                    () -> Beacon.payload("kirito-laptop", 5000, List.of("free-for-all", "campaign"), 2, 12))) {
                DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                listener.receive(packet);
                JSONObject beacon = new JSONObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                assertEquals("undying-dominion", beacon.getString("game"));
                assertEquals(1, beacon.getInt("v"));
                assertEquals("kirito-laptop", beacon.getString("name"));
                assertEquals(5000, beacon.getInt("port"));
                assertEquals(List.of("free-for-all", "campaign"), beacon.getJSONArray("modes").toList());
                assertFalse(beacon.has("mode"), "modes replaces mode (docs/design/modes.md)");
                assertEquals(2, beacon.getInt("players"));
                assertEquals(12, beacon.getInt("max"));
            }
        }
    }

    @Test
    void itAnnouncesAgainEveryFewSecondsWithTheCountAsItIsNow() throws Exception {
        int[] players = {0};
        try (DatagramSocket listener = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(4000);
            try (Beacon ignored = Beacon.start(listener.getLocalPort(), () -> List.of(InetAddress.getLoopbackAddress()),
                    () -> Beacon.payload("host", 5000, List.of("campaign"), ++players[0], 8))) {
                DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                listener.receive(packet);
                listener.receive(packet);
                JSONObject second = new JSONObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                assertEquals(2, second.getInt("players"));
            }
        }
    }

    @Test
    void closingItStopsTheAnnouncements() throws Exception {
        try (DatagramSocket listener = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            Beacon beacon = Beacon.start(listener.getLocalPort(), () -> List.of(InetAddress.getLoopbackAddress()),
                    () -> Beacon.payload("host", 5000, List.of("free-for-all"), 0, 8));
            listener.receive(new DatagramPacket(new byte[1024], 1024));
            beacon.close();
            listener.setSoTimeout(2600);
            assertThrows(SocketTimeoutException.class, () -> listener.receive(new DatagramPacket(new byte[1024], 1024)));
        }
    }

    @Test
    void theBeaconIsOnByDefaultAndCanBeTurnedOffOrMoved() {
        ServerOptions plain = ServerOptions.parse(new String[0]);
        assertTrue(plain.beacon());
        assertEquals(5001, plain.beaconPort());
        assertFalse(ServerOptions.parse(new String[] {"--no-beacon"}).beacon());
        assertEquals(6001, ServerOptions.parse(new String[] {"--beacon-port", "6001"}).beaconPort());
    }

    @Test
    void theTargetsAreReadAgainForEachAnnouncement() throws Exception {
        int[] reads = {0};
        try (DatagramSocket listener = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(4000);
            try (Beacon ignored = Beacon.start(listener.getLocalPort(), () -> {
                reads[0]++;
                return List.of(InetAddress.getLoopbackAddress());
            }, () -> Beacon.payload("host", 5000, List.of("free-for-all"), 0, 8))) {
                listener.receive(new DatagramPacket(new byte[1024], 1024));
                listener.receive(new DatagramPacket(new byte[1024], 1024));
            }
        }
        assertTrue(reads[0] >= 2, "a network that comes up later is announced to");
    }

    @Test
    void itSendsToTheWholeNetworkAndEachInterfacesOwnBroadcast() {
        List<InetAddress> targets = Beacon.targets();
        assertEquals("255.255.255.255", targets.getFirst().getHostAddress());
    }
}
