package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that only make sense on a real MariaDB server: the upgrade from the 1.0.0 schema,
 * the query plans and the cost of the ranking queries on a large table.
 *
 * Run with: mvnw test -Pmariadb -Dgearscore.test.jdbc="jdbc:mariadb://host:port/db?user=...&password=..."
 */
@Tag("mariadb")
class MariaDbIntegrationTest {

    private final Metrics metrics = new Metrics();
    private TestDatabase source;
    private ScoreRepository repository;

    @BeforeEach
    void setUp() {
        assumeTrue(TestDatabase.realMariaDb(), "needs -D" + TestDatabase.JDBC_PROPERTY);
        source = new TestDatabase();
        repository = new ScoreRepository(source.prefix());
    }

    @AfterEach
    void cleanUp() {
        if (source != null) {
            source.drop();
        }
    }

    private static String script(String name, String prefix) throws Exception {
        try (InputStream in = MariaDbIntegrationTest.class.getResourceAsStream("/db/migration/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("${prefix}", prefix);
        }
    }

    private static void run(Connection connection, String script) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                StringBuilder body = new StringBuilder();
                for (String line : sql.split("\n")) {
                    if (!line.trim().startsWith("--")) {
                        body.append(line).append('\n');
                    }
                }
                if (!body.toString().isBlank()) {
                    statement.execute(body.toString());
                }
            }
        }
    }

    private static List<String> explain(Connection connection, String sql) throws Exception {
        List<String> plan = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("EXPLAIN " + sql)) {
            while (result.next()) {
                plan.add("table=" + result.getString("table") + " type=" + result.getString("type")
                        + " key=" + result.getString("key") + " rows=" + result.getString("rows")
                        + " extra=" + result.getString("Extra"));
            }
        }
        return plan;
    }

    @Test
    void upgradeFromTheFirstReleaseKeepsTheData() throws Exception {
        String prefix = source.prefix();
        UUID id = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            // The schema exactly as version 1.0.0 left it.
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + prefix + "schema_version (version INT NOT NULL, "
                        + "description VARCHAR(100) NOT NULL, applied_at BIGINT NOT NULL, PRIMARY KEY (version))");
                statement.execute("INSERT INTO " + prefix + "schema_version VALUES (1, 'v1', 0), (2, 'v2', 0)");
            }
            run(connection, script("V1__create_players.sql", prefix));
            run(connection, script("V2__add_indexes.sql", prefix));
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + prefix + "players "
                    + "(uuid, last_name, score, armor_score, weapon_score, tier, source_server, rules_version, "
                    + "updated_at) VALUES (?, 'Zwo', 1408, 1046, 362, 'mitico', 'newera', 1, 123)")) {
                insert.setString(1, id.toString());
                insert.executeUpdate();
            }

            Migrator migrator = new Migrator(prefix);
            assertEquals(2, migrator.currentVersion(connection));
            assertEquals(1, migrator.migrate(connection));
            assertEquals(3, migrator.currentVersion(connection));
            assertEquals(0, migrator.migrate(connection));

            // The existing row is intact and already ranks.
            PlayerRecord row = repository.findByUuid(connection, id);
            assertEquals(1408, row.score());
            assertEquals(123L, row.updatedAt());
            assertEquals(1, repository.positions(connection, List.of(id)).get(id));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT rank_score FROM " + prefix + "players WHERE uuid = '" + id + "'")) {
                assertTrue(result.next());
                assertEquals(-1408, result.getInt(1));
            }

            // The superseded index is gone, the new one is there.
            List<String> indexes = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SHOW INDEX FROM " + prefix + "players")) {
                while (result.next()) {
                    indexes.add(result.getString("Key_name"));
                }
            }
            assertTrue(indexes.contains(prefix + "idx_rank"), indexes.toString());
            assertFalse(indexes.contains(prefix + "idx_score"), indexes.toString());
        }
    }

    @Test
    void rankingQueriesUseTheIndexAndStayCheapOnALargeTable() throws Exception {
        int rows = Integer.getInteger("gearscore.test.rows", 200_000);
        String table = source.prefix() + "players";
        Database database = source.open(metrics, 50L, 200L);
        database.run(connection -> 1);

        Random random = new Random(11);
        List<UUID> all = new ArrayList<>(rows);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            List<PlayerRecord> batch = new ArrayList<>(2_000);
            for (int i = 0; i < rows; i++) {
                UUID id = new UUID(random.nextLong(), random.nextLong());
                all.add(id);
                // About 1500 distinct scores and few distinct times: plenty of ties.
                batch.add(new PlayerRecord(id, "P" + i, random.nextInt(1500), 0, 0, "elite", "newera", 1,
                        1_700_000_000_000L + random.nextInt(50_000)));
                if (batch.size() == 2_000) {
                    repository.upsert(connection, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                repository.upsert(connection, batch);
            }
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE TABLE " + table);
            }

            String version;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT VERSION()")) {
                result.next();
                version = result.getString(1);
            }
            System.out.println("MARIADB # server " + version + ", " + rows + " rows");

            // Plans.
            List<String> topPlan = explain(connection,
                    "SELECT uuid FROM " + table + " ORDER BY rank_score ASC, updated_at ASC, uuid ASC LIMIT 100");
            System.out.println("MARIADB-PLAN | top 100 | " + topPlan);
            assertEquals(1, topPlan.size());
            assertTrue(topPlan.get(0).contains("key=" + source.prefix() + "idx_rank"), topPlan.toString());
            assertFalse(topPlan.get(0).toLowerCase(Locale.ROOT).contains("filesort"), topPlan.toString());

            List<String> histogramPlan = explain(connection,
                    "SELECT rank_score, COUNT(*) FROM " + table + " GROUP BY rank_score ORDER BY rank_score");
            System.out.println("MARIADB-PLAN | rows per score | " + histogramPlan);
            assertTrue(histogramPlan.get(0).contains("Using index"), histogramPlan.toString());
            assertFalse(histogramPlan.get(0).toLowerCase(Locale.ROOT).contains("filesort"),
                    histogramPlan.toString());

            List<String> tiePlan = explain(connection, "SELECT p.uuid, (SELECT COUNT(*) FROM " + table + " r "
                    + "WHERE r.rank_score = p.rank_score AND (r.updated_at < p.updated_at OR "
                    + "(r.updated_at = p.updated_at AND r.uuid < p.uuid))) FROM " + table + " p "
                    + "WHERE p.uuid IN ('" + all.get(0) + "','" + all.get(1) + "')");
            System.out.println("MARIADB-PLAN | ahead within the same score | " + tiePlan);
            assertTrue(tiePlan.stream().anyMatch(line -> line.contains("table=r") && line.contains("type=ref")
                    && line.contains("idx_rank")), tiePlan.toString());

            List<String> groupPlan = explain(connection, "SELECT uuid FROM " + table + " WHERE uuid IN ('"
                    + all.get(0) + "','" + all.get(1) + "','" + all.get(2) + "')");
            System.out.println("MARIADB-PLAN | grouped lookup by uuid | " + groupPlan);
            assertTrue(groupPlan.get(0).contains("key=PRIMARY"), groupPlan.toString());

            // Timings: median of 5 runs each, on a warm connection.
            List<UUID> online = new ArrayList<>(all.subList(0, 200));
            List<UUID> hundred = new ArrayList<>(all.subList(0, 100));
            repository.top(connection, 100);
            repository.positions(connection, online);
            repository.findByUuids(connection, hundred);
            double top = median(() -> repository.top(connection, 100));
            double positions = median(() -> repository.positions(connection, online));
            double grouped = median(() -> repository.findByUuids(connection, hundred));
            double single = median(() -> repository.findByUuid(connection, all.get(7)));
            System.out.printf(Locale.ROOT, "MARIADB-TIME | top 100 %.2f ms | positions of 200 players %.2f ms "
                    + "| 100 players by uuid %.2f ms | 1 player by uuid %.2f ms%n", top, positions, grouped, single);

            // Correctness on the big table: positions agree with the list, ties included.
            List<PlayerRecord> first = repository.top(connection, 300);
            List<UUID> firstIds = first.stream().map(PlayerRecord::uuid).toList();
            Map<UUID, Integer> ranked = repository.positions(connection, firstIds);
            boolean tie = false;
            for (int i = 0; i < first.size(); i++) {
                assertEquals(i + 1, ranked.get(firstIds.get(i)), "row " + i);
                tie |= i > 0 && first.get(i).score() == first.get(i - 1).score();
            }
            assertTrue(tie, "the sample must contain tied scores");
            List<PlayerRecord> sorted = new ArrayList<>(first);
            sorted.sort(ScoreRepository.RANK_ORDER);
            assertEquals(firstIds, sorted.stream().map(PlayerRecord::uuid).toList(),
                    "database order and the in-memory order are the same policy");

            // A sample from the whole table, far beyond any cached page.
            Map<UUID, Integer> sample = repository.positions(connection, online);
            for (UUID id : online.subList(0, 20)) {
                PlayerRecord row = repository.findByUuid(connection, id);
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + table
                        + " WHERE score > ? OR (score = ? AND updated_at < ?) "
                        + "OR (score = ? AND updated_at = ? AND uuid < ?)")) {
                    count.setInt(1, row.score());
                    count.setInt(2, row.score());
                    count.setLong(3, row.updatedAt());
                    count.setInt(4, row.score());
                    count.setLong(5, row.updatedAt());
                    count.setString(6, id.toString());
                    try (ResultSet result = count.executeQuery()) {
                        result.next();
                        assertEquals(result.getInt(1) + 1, sample.get(id), "position of " + id);
                    }
                }
            }
        }
    }

    private interface Query {
        void run() throws Exception;
    }

    private static double median(Query query) throws Exception {
        long[] nanos = new long[5];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            query.run();
            nanos[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(nanos);
        return nanos[nanos.length / 2] / 1_000_000.0;
    }
}
