package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.model.PlayerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All SQL of the plugin. Statements are prepared; the table prefix is the only
 * concatenated part and is validated when the configuration is read.
 */
public final class ScoreRepository {

    /**
     * The one ranking order: higher score first, then the older update, then the UUID text.
     * rank_score is the negated score, so every part is ascending and index-ordered.
     * The ranking list and individual positions are both derived from this order.
     */
    public static final Comparator<PlayerRecord> RANK_ORDER = Comparator
            .comparingInt((PlayerRecord record) -> -record.score())
            .thenComparingLong(PlayerRecord::updatedAt)
            .thenComparing(record -> record.uuid().toString());

    private static final String RANK_ORDER_SQL = "rank_score ASC, updated_at ASC, uuid ASC";

    private static final String COLUMNS =
            "uuid, last_name, score, armor_score, weapon_score, tier, source_server, rules_version, updated_at";

    private final String table;
    private final String upsert;
    private final String byUuid;
    private final String byName;
    private final String top;
    private final String histogram;

    public ScoreRepository(String prefix) {
        this.table = prefix + "players";
        this.upsert = "INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), score = VALUES(score), "
                + "armor_score = VALUES(armor_score), weapon_score = VALUES(weapon_score), tier = VALUES(tier), "
                + "source_server = VALUES(source_server), rules_version = VALUES(rules_version), "
                + "updated_at = VALUES(updated_at)";
        this.byUuid = "SELECT " + COLUMNS + " FROM " + table + " WHERE uuid = ?";
        this.byName = "SELECT " + COLUMNS + " FROM " + table + " WHERE last_name = ? ORDER BY updated_at DESC LIMIT 1";
        this.top = "SELECT " + COLUMNS + " FROM " + table + " ORDER BY " + RANK_ORDER_SQL + " LIMIT ?";
        this.histogram = "SELECT rank_score, COUNT(*) FROM " + table + " GROUP BY rank_score ORDER BY rank_score";
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
            rollbackQuietly(connection);
            throw e;
        } finally {
            restoreAutoCommit(connection, autoCommit);
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

    /** One primary-key query for a group of players. Players without a row are absent from the map. */
    public Map<UUID, PlayerRecord> findByUuids(Connection connection, List<UUID> uuids) throws SQLException {
        Map<UUID, PlayerRecord> found = new HashMap<>(uuids.size() * 2);
        if (uuids.isEmpty()) {
            return found;
        }
        String sql = "SELECT " + COLUMNS + " FROM " + table + " WHERE uuid IN (" + placeholders(uuids.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < uuids.size(); i++) {
                statement.setString(i + 1, uuids.get(i).toString());
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    PlayerRecord record = read(result);
                    found.put(record.uuid(), record);
                }
            }
        }
        return found;
    }

    public PlayerRecord findByName(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(byName)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    /** The first {@code limit} rows of the ranking, read in index order. */
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

    /**
     * 1-based ranking positions of a group of players, in the same order as {@link #top}.
     *
     * Two statements inside one read snapshot, whatever the group size:
     * the players' own rows with the count of rows that share their score but rank ahead
     * (a short index range each), and one index-only pass that counts rows per score.
     * position = 1 + rows with a better score + rows ahead within the same score.
     * Players without a row are absent from the map.
     */
    public Map<UUID, Integer> positions(Connection connection, List<UUID> uuids) throws SQLException {
        Map<UUID, Integer> positions = new HashMap<>(uuids.size() * 2);
        if (uuids.isEmpty()) {
            return positions;
        }
        String ties = "SELECT p.uuid, p.rank_score, (SELECT COUNT(*) FROM " + table + " r "
                + "WHERE r.rank_score = p.rank_score AND (r.updated_at < p.updated_at "
                + "OR (r.updated_at = p.updated_at AND r.uuid < p.uuid))) "
                + "FROM " + table + " p WHERE p.uuid IN (" + placeholders(uuids.size()) + ")";

        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Map<UUID, int[]> own = new HashMap<>(uuids.size() * 2);
            Map<Integer, Integer> betterByRank = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(ties)) {
                for (int i = 0; i < uuids.size(); i++) {
                    statement.setString(i + 1, uuids.get(i).toString());
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        int rank = result.getInt(2);
                        own.put(UUID.fromString(result.getString(1)), new int[]{rank, result.getInt(3)});
                        betterByRank.put(rank, 0);
                    }
                }
            }
            if (!own.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(histogram);
                     ResultSet result = statement.executeQuery()) {
                    int better = 0;
                    while (result.next()) {
                        int rank = result.getInt(1);
                        if (betterByRank.containsKey(rank)) {
                            betterByRank.put(rank, better);
                        }
                        better += result.getInt(2);
                    }
                }
                for (Map.Entry<UUID, int[]> entry : own.entrySet()) {
                    int[] row = entry.getValue();
                    positions.put(entry.getKey(), 1 + betterByRank.get(row[0]) + row[1]);
                }
            }
            connection.commit();
            return positions;
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            restoreAutoCommit(connection, autoCommit);
        }
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ",?");
        }
        return builder.toString();
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the original failure is the one worth reporting
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // connection is going back to the pool, which resets it
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
