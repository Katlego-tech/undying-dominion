package com.undyingdominion.tui;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static JSONObject pause(String reason, String by) {
        return new JSONObject().put("reason", reason).put("by", by == null ? JSONObject.NULL : by);
    }
}
