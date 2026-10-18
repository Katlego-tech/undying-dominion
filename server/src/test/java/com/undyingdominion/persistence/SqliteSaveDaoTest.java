package com.undyingdominion.persistence;

import com.undyingdominion.game.EngineState;
import com.undyingdominion.game.PlayerState;
import com.undyingdominion.story.ChapterRun;
import com.undyingdominion.story.ChapterRunState;
import com.undyingdominion.story.StoryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSaveDaoTest {

    @TempDir
    Path dir;

    @Test
    void aWrittenSaveReadsBackEqual() {
        SaveDao dao = new SqliteSaveDao(dir.resolve("saves.db"));
        SaveGame crypt = save("crypt", 1, 64, "2026-10-18T09:00:00Z");

        assertTrue(dao.write(crypt, false));
        assertEquals(Optional.of(crypt), dao.read("crypt"));
    }

    @Test
    void anUnknownNameReadsAsEmpty() {
        assertEquals(Optional.empty(), new SqliteSaveDao(dir.resolve("saves.db")).read("nosuch"));
    }

    @Test
    void aTakenNameIsKeptUnlessReplaced() {
        SaveDao dao = new SqliteSaveDao(dir.resolve("saves.db"));
        SaveGame first = save("crypt", 0, 10, "2026-10-18T09:00:00Z");
        SaveGame second = save("crypt", 1, 99, "2026-10-18T10:00:00Z");
        dao.write(first, false);

        assertFalse(dao.write(second, false), "refused without replace");
        assertEquals(Optional.of(first), dao.read("crypt"), "the old save is untouched");
        assertTrue(dao.write(second, true));
        assertEquals(Optional.of(second), dao.read("crypt"));
    }

    @Test
    void theListIsNewestFirst() {
        SaveDao dao = new SqliteSaveDao(dir.resolve("saves.db"));
        dao.write(save("old", 0, 10, "2026-10-17T09:00:00Z"), false);
        dao.write(save("new", 1, 10, "2026-10-18T09:00:00Z"), false);

        assertEquals(List.of(
                new SaveSummary("new", "A Trial Campaign", 2, Instant.parse("2026-10-18T09:00:00Z")),
                new SaveSummary("old", "A Trial Campaign", 1, Instant.parse("2026-10-17T09:00:00Z"))), dao.list());
    }

    @Test
    void savesOutliveTheConnectionThatWroteThem() {
        Path file = dir.resolve("saves.db");
        SaveGame crypt = save("crypt", 0, 10, "2026-10-18T09:00:00Z");
        new SqliteSaveDao(file).write(crypt, false);

        assertEquals(Optional.of(crypt), new SqliteSaveDao(file).read("crypt"));
    }

    @Test
    void aSaveOfAnotherFormatIsRefused() throws Exception {
        Path file = dir.resolve("saves.db");
        SaveDao dao = new SqliteSaveDao(file);
        dao.write(save("crypt", 0, 10, "2026-10-18T09:00:00Z"), false);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE saves SET format = 0");
        }

        assertThrows(SaveFormatException.class, () -> dao.read("crypt"));
    }

    @Test
    void anUnusableDatabaseFailsLoudly() {
        SaveDao dao = new SqliteSaveDao(dir.resolve("no/such/folder/saves.db"));
        assertThrows(SaveStoreException.class, () -> dao.write(save("crypt", 0, 10, "2026-10-18T09:00:00Z"), false));
    }

    private static SaveGame save(String name, int chapterIndex, int soul, String savedAt) {
        PlayerState vesper = new PlayerState(1, "Vesper", "#a78bfa", null, 12, soul, 0, 1, 0, 0);
        EngineState engine = new EngineState(40, 2, 3, 1, 1, List.of(1, 1, 3, 3), List.of(vesper),
                List.of(), List.of(), List.of());
        ChapterRunState run = new ChapterRunState(0, List.of(), List.of(), List.of(), List.of(), 0, false,
                ChapterRun.Outcome.PLAYING);
        return new SaveGame(SaveGame.FORMAT, name, Instant.parse(savedAt), "/somewhere/trial", "A Trial Campaign",
                "9f2c", RandomState.of(new Random(4)), new StoryState(chapterIndex, -1, 0, run), engine);
    }
}
