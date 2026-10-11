package com.undyingdominion.net;

import com.undyingdominion.game.Command;
import com.undyingdominion.game.GameEngine;
import com.undyingdominion.game.GameRules;
import com.undyingdominion.game.Player;
import com.undyingdominion.game.Unit;
import com.undyingdominion.game.UnitType;
import com.undyingdominion.world.GameMap;
import com.undyingdominion.world.Obstacle;
import com.undyingdominion.world.Position;
import com.undyingdominion.world.Terrain;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotWriterTest {

    /** A single walkable tile at [25,6] sealed inside a ring of ruins, so nothing outside can see into it. */
    private static final Position POCKET = new Position(25, 6);
    private static final GameMap MAP = new GameMap(30, 12, List.of(
            new Obstacle(24, 5, 26, 5, Terrain.RUINS),
            new Obstacle(24, 7, 26, 7, Terrain.RUINS),
            new Obstacle(24, 6, 24, 6, Terrain.RUINS),
            new Obstacle(26, 6, 26, 6, Terrain.RUINS)));

    @Test
    void aPlayerIsNeverToldAboutWhatIsUnderTheirFog() {
        GameEngine engine = new GameEngine(MAP, GameRules.defaults().maxNeutrals(0), new Random(3));
        Unit hidden = engine.spawn(UnitType.LICH, 2, POCKET);
        Player vesper = join(engine, "Vesper");
        join(engine, "Corvin");

        JSONObject state = new JSONObject(SnapshotWriter.state(engine, vesper, SnapshotWriter.scoreboard(engine)));

        JSONArray units = state.getJSONArray("units");
        boolean sawHidden = false;
        boolean sawOwnNecromancer = false;
        for (int i = 0; i < units.length(); i++) {
            JSONObject unit = units.getJSONObject(i);
            if (unit.getInt("id") == hidden.getId()) sawHidden = true;
            if (unit.getInt("id") == vesper.getNecromancerId()) sawOwnNecromancer = true;
        }
        assertFalse(sawHidden);
        assertTrue(sawOwnNecromancer);
        assertEquals((30 * 12 + 7) / 8, Base64.getDecoder().decode(state.getString("fog")).length);
    }

    @Test
    void noticesAreDeliveredOnce() {
        GameEngine engine = new GameEngine(MAP, GameRules.defaults().maxNeutrals(0), new Random(3));
        Player vesper = join(engine, "Vesper");

        JSONObject first = new JSONObject(SnapshotWriter.state(engine, vesper, new JSONArray()));
        JSONObject second = new JSONObject(SnapshotWriter.state(engine, vesper, new JSONArray()));

        assertEquals(1, first.getJSONObject("you").getJSONArray("notices").length());
        assertEquals(0, second.getJSONObject("you").getJSONArray("notices").length());
    }

    private static Player join(GameEngine engine, String name) {
        int id = engine.allocatePlayerId();
        engine.submit(new Command.Join(id, name));
        engine.tick();
        return engine.getPlayer(id);
    }
}
