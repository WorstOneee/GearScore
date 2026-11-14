package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.config.Settings;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests against H2 in MySQL mode.
 */
class StorageTest {

    private static final Logger LOGGER = quietLogger();

    private final Metrics metrics = new Metrics();
    private final ScoreRepository repository = new ScoreRepository("gearscore_");

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("GearScoreTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    private static PlayerRecord record(UUID id, String name, int score, String server, long updatedAt) {
        return new PlayerRecord(id, name, score, score * 3 / 4, score / 4, "elite", server, 1, updatedAt);
    }

    private WriteQueue queue(Database database, long flushMillis, int batch, int maxPending) {
        return new WriteQueue(database, repository, metrics, LOGGER, flushMillis, batch, maxPending);
    }

    private LookupService lookups(Database database) {
        return new LookupService(database, repository, metrics, 1, 16, 60_000L, 100, 10, 60_000L, 60_000L);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.sleep(10L);
        }
    }

    @Test
    void migrationsCreateTheSchemaOnceAndAreVersioned() throws Exception {
        TestDatabase source = new TestDatabase();
        Database database = source.open(metrics, 50L, 200L);
        int version = database.run(connection -> new Migrator("gearscore_").currentVersion(connection));
        assertEquals(Migrator.latestVersion(), version);
        assertEquals(2, version);

        // A second start finds everything applied.
        int applied = source.open(metrics, 50L, 200L)
                .run(connection -> new Migrator("gearscore_").migrate(connection));
        assertEquals(0, applied);

        int rows = database.run(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM gearscore_schema_version")) {
                result.next();
                return result.getInt(1);
            }
        });
        assertEquals(2, rows);
    }

    @Test
    void scoreSurvivesARestart() throws Exception {
        TestDatabase source = new TestDatabase();
        UUID id = UUID.randomUUID();

        Database first = source.open(metrics, 50L, 200L);
        WriteQueue writer = queue(first, 50L, 100, 1000);
        writer.submit(record(id, "Zwo", 1408, "newera", 111L));
        assertEquals(0, writer.shutdown(5_000L));

        // New pool, new services: what a server restart looks like to the plugin.
        Database second = source.open(new Metrics(), 50L, 200L);
        PlayerRecord loaded = second.run(connection -> repository.findByUuid(connection, id));
        assertNotNull(loaded);
        assertEquals("Zwo", loaded.name());
        assertEquals(1408, loaded.score());
        assertEquals(1056, loaded.armorScore());
        assertEquals(352, loaded.weaponScore());
        assertEquals("elite", loaded.tier());
        assertEquals("newera", loaded.server());
        assertEquals(1, loaded.rulesVersion());
        assertEquals(111L, loaded.updatedAt());
    }

    @Test
    void manyChangesOfOnePlayerBecomeOneRowWithTheLatestState() throws Exception {
        Database database = new TestDatabase().open(metrics, 50L, 200L);
        WriteQueue writer = queue(database, 200L, 100, 1000);
        UUID id = UUID.randomUUID();
        for (int i = 1; i <= 50; i++) {
            writer.submit(record(id, "Zwo", i * 10, "newera", i));
        }
        assertEquals(1, writer.pendingCount());
        await(() -> metrics.rowsWritten.sum() == 1);
        assertEquals(1, metrics.writeBatches.sum());
        assertEquals(49, metrics.writesMerged.sum());
        assertEquals(500, database.run(connection -> repository.findByUuid(connection, id)).score());
        writer.shutdown(1_000L);
    }

    @Test
    void writesAreBatched() throws Exception {
        Database database = new TestDatabase().open(metrics, 50L, 200L);
        WriteQueue writer = queue(database, 100L, 100, 1000);
        for (int i = 0; i < 250; i++) {
            writer.submit(record(UUID.randomUUID(), "P" + i, i, "newera", i));
        }
        await(() -> metrics.rowsWritten.sum() == 250);
        assertEquals(3, metrics.writeBatches.sum());
        writer.shutdown(1_000L);
    }

    @Test
    void outageKeepsUpdatesPendingAndRecoveryFlushesThem() throws Exception {
        TestDatabase source = new TestDatabase();
        Database database = source.open(metrics, 40L, 160L);
        WriteQueue writer = queue(database, 50L, 100, 1000);
        UUID id = UUID.randomUUID();
        writer.submit(record(id, "Zwo", 100, "newera", 1L));
        await(() -> metrics.rowsWritten.sum() == 1);

        source.down = true;
        writer.submit(record(id, "Zwo", 200, "newera", 2L));
        writer.submit(record(UUID.randomUUID(), "Other", 300, "newera", 3L));
        await(() -> metrics.databaseFailures.sum() >= 2);
        assertFalse(database.isAvailable());
        assertEquals(2, writer.pendingCount());
        assertEquals(1, metrics.rowsWritten.sum());

        // A newer state submitted during the outage replaces the pending one.
        writer.submit(record(id, "Zwo", 250, "newera", 4L));
        assertEquals(2, writer.pendingCount());

        // Backoff: attempts are spaced out instead of retried in a tight loop.
        long failuresBefore = metrics.databaseFailures.sum();
        int connectionsBefore = source.connectionsOpened();
        Thread.sleep(300L);
        assertTrue(metrics.databaseFailures.sum() - failuresBefore <= 4,
                "too many attempts during the outage: " + (metrics.databaseFailures.sum() - failuresBefore));
        assertEquals(connectionsBefore, source.connectionsOpened());

        source.down = false;
        await(() -> writer.pendingCount() == 0 && metrics.rowsWritten.sum() == 3);
        assertTrue(database.isAvailable());
        assertEquals(0, database.consecutiveFailures());
        assertEquals(250, database.run(connection -> repository.findByUuid(connection, id)).score());
        writer.shutdown(1_000L);
    }

    @Test
    void readsFailFastDuringTheBackoffWindow() throws Exception {
        TestDatabase source = new TestDatabase();
        Database database = source.open(metrics, 5_000L, 5_000L);
        source.down = true;
        assertThrows(SQLException.class, () -> database.run(connection -> 1));
        int attempts = source.connectionsOpened();
        assertThrows(Database.UnavailableException.class, () -> database.run(connection -> 1));
        assertEquals(attempts, source.connectionsOpened());
        assertEquals(1, metrics.databaseFailures.sum());

        LookupService service = lookups(database);
        assertTrue(service.lookupByName("Zwo").isCompletedExceptionally());
        assertEquals(-1, service.position(UUID.randomUUID(), 10));
        service.shutdown();
    }

    @Test
    void pendingWritesAreBoundedDuringALongOutage() throws Exception {
        TestDatabase source = new TestDatabase();
        source.down = true;
        Database database = source.open(metrics, 5_000L, 5_000L);
        WriteQueue writer = queue(database, 50L, 10, 100);
        for (int i = 0; i < 500; i++) {
            writer.submit(record(UUID.randomUUID(), "P" + i, i, "newera", i));
        }
        assertEquals(100, writer.pendingCount());
        assertEquals(400, metrics.writesDropped.sum());
        // Shutdown must not hang while the database is unreachable.
        long start = System.nanoTime();
        assertEquals(100, writer.shutdown(2_000L));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_500L);
    }

    @Test
    void rankingIsLimitedOrderedAndCached() throws Exception {
        Database database = new TestDatabase().open(metrics, 50L, 200L);
        WriteQueue writer = queue(database, 50L, 100, 1000);
        UUID best = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        for (int i = 1; i <= 30; i++) {
            UUID id = i == 30 ? best : i == 1 ? low : UUID.randomUUID();
            writer.submit(record(id, "P" + i, i * 10, "newera", i));
        }
        assertEquals(0, writer.shutdown(5_000L));

        LookupService service = lookups(database);
        long queriesBefore = metrics.readQueries.sum();
        LookupService.TopSnapshot top = service.top().get(5, TimeUnit.SECONDS);
        assertEquals(10, top.rows().size(), "only the configured limit is loaded");
        assertEquals(300, top.rows().get(0).score());
        assertEquals(210, top.rows().get(9).score());
        assertEquals(1, top.positions().get(best));

        // Every further viewer shares the cached snapshot.
        for (int i = 0; i < 20; i++) {
            assertTrue(service.top().isDone());
        }
        assertEquals(queriesBefore + 1, metrics.readQueries.sum());

        // Position inside the top comes from the snapshot; outside it, one counted query.
        assertEquals(1, service.position(best, 300));
        assertEquals(-1, service.position(low, 10));
        await(() -> service.position(low, 10) == 30);
        assertEquals(queriesBefore + 2, metrics.readQueries.sum());
        service.shutdown();
    }

    @Test
    void offlineLookupIsCached() throws Exception {
        Database database = new TestDatabase().open(metrics, 50L, 200L);
        WriteQueue writer = queue(database, 50L, 100, 1000);
        writer.submit(record(UUID.randomUUID(), "Zwo", 900, "newera", 7L));
        assertEquals(0, writer.shutdown(5_000L));

        LookupService service = lookups(database);
        long before = metrics.readQueries.sum();
        Optional<PlayerRecord> found = service.lookupByName("Zwo").get(5, TimeUnit.SECONDS);
        assertEquals(900, found.orElseThrow().score());
        assertTrue(service.lookupByName("Zwo").isDone());
        assertTrue(service.lookupByName("Nobody").get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.lookupByName("nobody").isDone(), "negative results are cached too");
        assertEquals(before + 2, metrics.readQueries.sum());
        service.shutdown();
    }

    @Test
    void onlyConfiguredSourcesAreOfficial() {
        YamlConfiguration config = TestRules.defaultConfig();
        assertTrue(Settings.from(config).official(), "newera is the default official source");
        for (String other : List.of("hub", "eventos")) {
            config.set("server.name", other);
            assertFalse(Settings.from(config).official(), other);
        }
        config.set("server.official-sources", List.of("newera", "eventos"));
        assertTrue(Settings.from(config).official());
    }

    @Test
    void officialScoreIsReadUnchangedFromOtherServers() throws Exception {
        TestDatabase source = new TestDatabase();
        UUID id = UUID.randomUUID();

        // New Era, an official source, persists the score.
        WriteQueue newEra = queue(source.open(metrics, 50L, 200L), 50L, 100, 1000);
        newEra.submit(record(id, "Zwo", 1408, "newera", 500L));
        assertEquals(0, newEra.shutdown(5_000L));

        // Hub and events run read-only: they are built without a write queue, so whatever
        // temporary kit the player wears there, the row stays as New Era left it.
        for (String server : List.of("hub", "eventos")) {
            Metrics readOnly = new Metrics();
            LookupService service = lookups(source.open(readOnly, 50L, 200L));
            PlayerRecord seen = service.loadByUuid(id).get(5, TimeUnit.SECONDS);
            assertEquals(1408, seen.score(), server);
            assertEquals("newera", seen.server(), server);
            assertEquals(500L, seen.updatedAt(), server);
            assertEquals(0, readOnly.rowsWritten.sum(), server);
            service.shutdown();
        }
        assertNull(lookups(source.open(metrics, 50L, 200L))
                .loadByUuid(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
    }
}
