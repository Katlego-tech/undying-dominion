package com.undyingdominion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerOptionsTest {

    @Test
    void everythingHasADefault() {
        ServerOptions options = ServerOptions.parse(new String[0]);

        assertEquals(ServerOptions.DEFAULT_PORT, options.port());
        assertEquals(ServerOptions.DEFAULT_MAP, options.mapLocation());
        assertEquals(ServerOptions.DEFAULT_MAX_PLAYERS, options.maxPlayers());
        assertNull(options.seed());
        assertTrue(options.wildCreatures());
    }

    @Test
    void readsEveryFlag() {
        ServerOptions options = ServerOptions.parse(
                new String[]{"-p", "6000", "-m", "maps/arena.properties", "-mc", "4", "--seed", "42", "--no-wild"});

        assertEquals(6000, options.port());
        assertEquals("maps/arena.properties", options.mapLocation());
        assertEquals(4, options.maxPlayers());
        assertEquals(42L, options.seed());
        assertFalse(options.wildCreatures());
    }

    @Test
    void rejectsAnUnknownFlag() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServerOptions.parse(new String[]{"-s", "2"}));
        assertEquals("Unknown option: -s", e.getMessage());
    }

    @Test
    void rejectsAPortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[]{"-p", "70000"}));
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(new String[]{"-p"}));
    }
}
