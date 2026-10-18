package com.undyingdominion.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Saves in one SQLite file: a row per save, with the listing's columns beside the whole game as JSON.
 * Each call opens its own connection, so the DAO is safe from any thread, and every write is one
 * statement, so a save is either all there or not there at all.
 */
public final class SqliteSaveDao implements SaveDao {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS saves (
                name           TEXT    PRIMARY KEY,
                campaign_title TEXT    NOT NULL,
                chapter        INTEGER NOT NULL,
                format         INTEGER NOT NULL,
                saved_at       TEXT    NOT NULL,
                state          TEXT    NOT NULL
            )""";

    private final String url;

    public SqliteSaveDao(Path database) {
        this.url = "jdbc:sqlite:" + database;
    }

    @Override
    public boolean write(SaveGame save, boolean replace) {
        String sql = replace
                ? "INSERT OR REPLACE INTO saves VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO saves VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(name) DO NOTHING";
        try (Connection connection = open(); PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, save.name());
            insert.setString(2, save.campaignTitle());
            insert.setInt(3, save.story().chapterIndex() + 1);
            insert.setInt(4, save.format());
            insert.setString(5, save.savedAt().toString());
            insert.setString(6, SaveGameCodec.toJson(save));
            return insert.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SaveStoreException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<SaveGame> read(String name) {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement("SELECT format, state FROM saves WHERE name = ?")) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) return Optional.empty();
                int format = row.getInt("format");
                if (format != SaveGame.FORMAT) throw new SaveFormatException("Unsupported save format " + format);
                return Optional.of(SaveGameCodec.fromJson(row.getString("state")));
            }
        } catch (SQLException e) {
            throw new SaveStoreException(e.getMessage(), e);
        }
    }

    @Override
    public List<SaveSummary> list() {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT name, campaign_title, chapter, saved_at FROM saves ORDER BY saved_at DESC, name");
             ResultSet rows = select.executeQuery()) {
            List<SaveSummary> saves = new ArrayList<>();
            while (rows.next()) {
                saves.add(new SaveSummary(rows.getString("name"), rows.getString("campaign_title"),
                        rows.getInt("chapter"), Instant.parse(rows.getString("saved_at"))));
            }
            return saves;
        } catch (SQLException e) {
            throw new SaveStoreException(e.getMessage(), e);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
