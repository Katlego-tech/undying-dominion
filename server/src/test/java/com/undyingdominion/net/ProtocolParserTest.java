package com.undyingdominion.net;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.world.Position;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolParserTest {

    private static Command parse(String json) throws ProtocolException {
        return ProtocolParser.parse(new JSONObject(json), 3);
    }

    @Test
    void parsesASummon() throws ProtocolException {
        assertEquals(new Command.Summon(3, UnitType.LICH), parse("{\"command\":\"summon\",\"unit\":\"lich\"}"));
    }

    @Test
    void parsesAnAttackMove() throws ProtocolException {
        Command move = parse("{\"command\":\"move\",\"units\":[4,5],\"x\":10,\"y\":2,\"attackMove\":true}");
        assertEquals(new Command.Move(3, List.of(4, 5), new Position(10, 2), true), move);
    }

    @Test
    void commandNamesAreCaseInsensitive() throws ProtocolException {
        assertEquals(new Command.Raise(3, 9), parse("{\"command\":\"RAISE\",\"corpse\":9}"));
    }

    @Test
    void anUnknownCommandIsUnsupported() {
        ProtocolException e = assertThrows(ProtocolException.class, () -> parse("{\"command\":\"luanch\"}"));
        assertEquals("Unsupported command", e.getMessage());
    }

    @Test
    void missingOrMistypedArgumentsCannotBeParsed() {
        assertEquals("Could not parse arguments",
                assertThrows(ProtocolException.class, () -> parse("{\"command\":\"move\",\"units\":[1]}")).getMessage());
        assertEquals("Could not parse arguments",
                assertThrows(ProtocolException.class, () -> parse("{\"command\":\"summon\",\"unit\":\"dragon\"}")).getMessage());
        assertEquals("Could not parse arguments",
                assertThrows(ProtocolException.class, () -> ProtocolParser.read("not json")).getMessage());
    }

    @Test
    void namesAreTrimmedAndLimited() throws ProtocolException {
        assertEquals("Vesper Corvin", ProtocolParser.validName("  Vesper   Corvin "));
        assertThrows(ProtocolException.class, () -> ProtocolParser.validName(""));
        assertThrows(ProtocolException.class, () -> ProtocolParser.validName("<script>"));
        assertThrows(ProtocolException.class, () -> ProtocolParser.validName("a".repeat(17)));
    }
}
