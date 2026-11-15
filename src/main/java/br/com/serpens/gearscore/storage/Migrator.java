package br.com.serpens.gearscore.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * Versioned schema migrations. Scripts live in {@code db/migration} and are applied in
 * order; the applied versions are recorded in {@code <prefix>schema_version}.
 */
public final class Migrator {

    private record Migration(int version, String description, String resource) {
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "create players table", "V1__create_players.sql"),
            new Migration(2, "add ranking and name indexes", "V2__add_indexes.sql"),
            new Migration(3, "stable ranking order", "V3__stable_ranking_order.sql"));

    // MySQL/MariaDB codes for "already done", so a migration that was interrupted halfway, or
    // raced by another server starting at the same time, can finish.
    private static final Set<Integer> ALREADY_APPLIED = Set.of(
            1050,  // table already exists
            1060,  // duplicate column name
            1061,  // duplicate key name
            1091); // cannot drop: key does not exist

    private final String prefix;

    public Migrator(String prefix) {
        this.prefix = prefix;
    }

    public static int latestVersion() {
        return MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    }

    /** @return number of migrations applied by this call */
    public int migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + prefix + "schema_version ("
                    + "version INT NOT NULL, "
                    + "description VARCHAR(100) NOT NULL, "
                    + "applied_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (version))");
        }
        int current = currentVersion(connection);
        int applied = 0;
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            apply(connection, migration);
            applied++;
        }
        return applied;
    }

    public int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM " + prefix + "schema_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        String script = load(migration.resource()).replace("${prefix}", prefix);
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                String trimmed = stripComments(sql).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    statement.execute(trimmed);
                } catch (SQLException e) {
                    if (!ALREADY_APPLIED.contains(e.getErrorCode())) {
                        throw e;
                    }
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO " + prefix + "schema_version (version, description, applied_at) VALUES (?, ?, ?)")) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.description());
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String load(String resource) throws SQLException {
        String path = "/db/migration/" + resource;
        try (InputStream in = Migrator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new SQLException("missing migration script " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("cannot read migration script " + path, e);
        }
    }
}
