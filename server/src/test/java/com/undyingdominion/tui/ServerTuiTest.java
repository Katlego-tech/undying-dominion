package com.undyingdominion.tui;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dashboard's pause label, for every reason the world can stop (GameServer.currentPause). */
class ServerTuiTest {

    @Test
    void everyPauseReasonHasALabelAndNoneNeedsAName() {
        assertNull(ServerTui.pauseLabel(null));
        assertEquals("by Vesper", ServerTui.pauseLabel(pause("player", "Vesper")));
        assertEquals("by you", ServerTui.pauseLabel(pause("host", "the host")));
        assertEquals("a player is reading", ServerTui.pauseLabel(pause("story", null)));
        assertEquals("waiting for the saved player", ServerTui.pauseLabel(pause("resume", null)));
        assertEquals("everyone is away", ServerTui.pauseLabel(pause("away", null)));
    }

    @Test
    void anUnknownReasonWithoutANameStillLabelsInsteadOfCrashing() {
        assertEquals("lobby", ServerTui.pauseLabel(pause("lobby", null)));
    }

    @Test
    void containerAndVirtualMachineBridgesAreNeverOfferedAsTheInviteAddress() {
        for (String bridge : List.of("docker0", "br-7ce113a1c528", "virbr0", "veth12ab", "vmnet8", "vboxnet0", "tun0", "wg0", "tailscale0")) {
            assertFalse(ServerTui.invitable(bridge), bridge);
        }
        for (String nic : List.of("wlp1s0", "eth0", "enp3s0", "enx7aa679d12603", "wlan0")) {
            assertTrue(ServerTui.invitable(nic), nic);
        }
    }

    @Test
    void theInviteLinkFollowsTheAddressWhenTheNetworkChanges() {
        ArrayDeque<String> addresses = new ArrayDeque<>(List.of("192.168.26.147", "10.1.1.140"));
        long[] now = {0};
        Supplier<String> link = ServerTui.inviteLink(addresses::poll, 5000, () -> now[0]);

        assertEquals("http://192.168.26.147:5000", link.get());
        now[0] += 1_000_000_000L;
        assertEquals("http://192.168.26.147:5000", link.get(), "looked up again only every few seconds");
        now[0] += 5_000_000_000L;
        assertEquals("http://10.1.1.140:5000", link.get());
        now[0] += 5_000_000_000L;
        assertNull(link.get(), "no network, no link");
    }

    @Test
    void aFrameThatThrowsIsLoggedOnceAndTheNextOneStillDraws() {
        List<String> logged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Runnable broken = () -> { throw new IllegalStateException("a unit off the map"); };

        assertFalse(ServerTui.drawSafely(broken, logged::add, seen));
        assertFalse(ServerTui.drawSafely(broken, logged::add, seen));
        int[] drawn = {0};
        assertTrue(ServerTui.drawSafely(() -> drawn[0]++, logged::add, seen));

        assertEquals(1, drawn[0], "the dashboard carries on");
        assertEquals(List.of("The dashboard skipped a frame: java.lang.IllegalStateException: a unit off the map"), logged,
                "the same failure is logged once, not ten times a second");
    }

    private static JSONObject pause(String reason, String by) {
        return new JSONObject().put("reason", reason).put("by", by == null ? JSONObject.NULL : by);
    }

    @Test
    void aWorldIsLabelledByItsModeAndWhoIsInIt() {
        WorldSnapshot.PlayerRow lin = new WorldSnapshot.PlayerRow(1, "Lin", "#5eead4", 1, 0, 0, 10, 0, 10, 0, 0, 0, true, false);

        assertEquals("Free-for-all 1", ServerTui.worldLabel(true, List.of(lin)));
        assertEquals("Free-for-all 0", ServerTui.worldLabel(true, List.of()));
        assertEquals("Campaign · Lin", ServerTui.worldLabel(false, List.of(lin)));
        assertEquals("Campaign · starting", ServerTui.worldLabel(false, List.of()), "before its player is in");
    }
}
