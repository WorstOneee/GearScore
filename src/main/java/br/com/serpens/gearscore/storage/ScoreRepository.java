package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.model.PlayerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * All SQL of the plugin. Statements are prepared; the table prefix is the only
 * concatenated part and is validated when the configuration is read.
 */
public final class ScoreRepository {

    private static final String COLUMNS =
            "uuid, last_name, score, armor_score, weapon_score, tier, source_server, rules_version, updated_at";

    private final String upsert;
    private final String byUuid;
    private final String byName;
    private final String top;
    private final String position;

    public ScoreRepository(String prefix) {
        String table = prefix + "players";
        this.upsert = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), score = VALUES(score), "
                + "armor_score = VALUES(armor_score), weapon_score = VALUES(weapon_score), tier = VALUES(tier), "
                + "source_server = VALUES(source_server), rules_version = VALUES(rules_version), "
                + "updated_at = VALUES(updated_at)";
        this.byUuid = "SELECT " + COLUMNS + " FROM " + table + " WHERE uuid = ?";
        this.byName = "SELECT " + COLUMNS + " FROM " + table + " WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1";
        this.top = "SELECT " + COLUMNS + " FROM " + table + " ORDER BY score DESC, updated_at ASC LIMIT ?";
        this.position = "SELECT COUNT(*) FROM " + table + " WHERE score > ?";
    }

    public void upsert(Connection connection, Collection<PlayerRecord> records) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(upsert)) {
            for (PlayerRecord record : records) {
                statement.setString(1, record.uuid().toString());
                statement.setString(2, record.name());
                statement.setInt(3, record.score());
                statement.setInt(4, record.armorScore());
                statement.setInt(5, record.weaponScore());
                statement.setString(6, record.tier());
                statement.setString(7, record.server());
                statement.setInt(8, record.rulesVersion());
                statement.setLong(9, record.updatedAt());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // the original failure is the one worth reporting
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // connection is going back to the pool, which resets it
            }
        }
    }

    public PlayerRecord findByUuid(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(byUuid)) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public PlayerRecord findByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(byName)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    public List<PlayerRecord> top(Connection connection, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(top)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<PlayerRecord> rows = new ArrayList<>(limit);
                while (result.next()) {
                    rows.add(read(result));
                }
                return rows;
            }
        }
    }

    /** 1-based ranking position of a score. */
    public int position(Connection connection, int score) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(position)) {
            statement.setInt(1, score);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) + 1 : 1;
            }
        }
    }

    private static PlayerRecord read(ResultSet result) throws SQLException {
        return new PlayerRecord(
                UUID.fromString(result.getString(1)),
                result.getString(2),
                result.getInt(3),
                result.getInt(4),
                result.getInt(5),
                result.getString(6),
                result.getString(7),
                result.getInt(8),
                result.getLong(9));
    }
}
