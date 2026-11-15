package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.config.Settings;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.RemoteScores;
import br.com.serpens.gearscore.model.PlayerRecord;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * Persistence tests. They run against H2 in MySQL mode by default and against a real MariaDB
 * server when -Dgearscore.test.jdbc is given (see {@link TestDatabase}).
 */
class StorageTest {

    private static final Logger LOGGER = quietLogger();
    private static final long POSITION_INTERVAL = 10_000L;

    private final Metrics metrics = new Metrics();
    private final TestDatabase source = new TestDatabase();
    private final ScoreRepository repository = new ScoreRepository(source.prefix());
    private final List<LookupService> services = new ArrayList<>();

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger("GearScoreTest");
        logger.setUseParentHandlers(false);
        return logger;
    }

    @AfterEach
    void cleanUp() {
        services.forEach(LookupService::shutdown);
        source.down = false;
        source.outageDelayMillis = 0L;
        source.drop();
    }

    private static PlayerRecord record(UUID id, String name, int score, String server, long updatedAt) {
        return new PlayerRecord(id, name, score, score * 3 / 4, score / 4, "elite", server, 1, updatedAt);
    }

    private WriteQueue queue(Database database, long flushMillis, int batch, int maxPending) {
        return new WriteQueue(database, repository, metrics, LOGGER, flushMillis, batch, maxPending);
    }

    private LookupService lookups(Database database, Metrics counters) {
        LookupService service = new LookupService(database, repository, counters, 1, 16, 60_000L, 100, 10,
                60_000L, POSITION_INTERVAL);
        services.add(service);
        return service;
    }

    private LookupService lookups(Database database) {
        return lookups(database, metrics);
    }

    private void store(Database database, List<PlayerRecord> records) throws SQLException {
        database.run(connection -> {
            repository.upsert(connection, records);
            return null;
        });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(10L);
        }
    }

    // ---- schema --------------------------------------------------------------------------

    @Test
    void migrationsCreateTheSchemaOnceAndAreVersioned() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        int version = database.run(connection -> new Migrator(source.prefix()).currentVersion(connection));
        assertEquals(Migrator.latestVersion(), version);
        assertEquals(3, version);

        // A second start finds everything applied.
        int applied = source.open(metrics, 50L, 200L)
                .run(connection -> new Migrator(source.prefix()).migrate(connection));
        assertEquals(0, applied);

        int rows = database.run(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COUNT(*) FROM " + source.prefix() + "schema_version")) {
                result.next();
                return result.getInt(1);
            }
        });
        assertEquals(3, rows);
    }

    // ---- writes --------------------------------------------------------------------------

    @Test
    void scoreSurvivesARestart() throws Exception {
        UUID id = UUID.randomUUID();

        Database first = source.open(metrics, 50L, 200L);
        WriteQueue writer = queue(first, 50L, 100, 1000);
        writer.submit(record(id, "Zwo", 1408, "newera", 111L));
        assertEquals(0, writer.shutdown(5_000L).unconfirmed());

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
        Database database = source.open(metrics, 50L, 200L);
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
        Database database = source.open(metrics, 50L, 200L);
        WriteQueue writer = queue(database, 100L, 100, 1000);
        for (int i = 0; i < 250; i++) {
            writer.submit(record(UUID.randomUUID(), "P" + i, i, "newera", i));
        }
        await(() -> metrics.rowsWritten.sum() == 250);
        assertEquals(3, metrics.writeBatches.sum());
        writer.shutdown(1_000L);
    }

    // ---- outages -------------------------------------------------------------------------

    @Test
    void outageKeepsUpdatesPendingAndRecoveryFlushesThem() throws Exception {
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
        Database database = source.open(metrics, 5_000L, 5_000L);
        source.down = true;
        assertThrows(SQLException.class, () -> database.run(connection -> 1));
        assertThrows(Database.UnavailableException.class, () -> database.run(connection -> 1));
        assertEquals(1, source.attempts(), "the second call must not reach for a connection");
        assertEquals(1, metrics.databaseFailures.sum());

        LookupService service = lookups(database);
        UUID id = UUID.randomUUID();
        assertTrue(service.lookupByName("Zwo").isCompletedExceptionally());
        assertTrue(service.loadByUuids(List.of(id)).isCompletedExceptionally());
        service.trackPosition(id);
        assertEquals(-1, service.position(id));
        assertFalse(service.refreshPositions(1_000_000L), "no position refresh while the database is down");
        assertEquals(1, source.attempts());
    }

    @Test
    void pendingWritesAreBoundedDuringALongOutage() throws Exception {
        source.down = true;
        Database database = source.open(metrics, 5_000L, 5_000L);
        WriteQueue writer = queue(database, 50L, 10, 100);
        for (int i = 0; i < 500; i++) {
            writer.submit(record(UUID.randomUUID(), "P" + i, i, "newera", i));
            assertTrue(writer.pendingCount() <= 100);
        }
        assertEquals(100, writer.pendingCount());
        assertEquals(400, metrics.writesDropped.sum());
        // Shutdown must not hang while the database is unreachable.
        long start = System.nanoTime();
        assertEquals(100, writer.shutdown(2_000L).unconfirmed());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2_500L);
    }

    @Test
    void failedBatchIsRestoredWithinTheLimitAndNeverOverNewerState() throws Exception {
        // A long retry window keeps the writer from taking a second batch during the checks.
        Database database = source.open(metrics, 1_500L, 1_500L);
        // Create the schema first so the failure below happens on the batch itself.
        database.run(connection -> 1);
        WriteQueue writer = queue(database, 50L, 20, 100);

        List<UUID> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            first.add(new UUID(1L, i));
        }
        // The write attempt hangs for a while and then fails, like a dying connection.
        source.outageDelayMillis = 500L;
        source.down = true;
        for (int i = 0; i < 100; i++) {
            writer.submit(record(first.get(i), "A" + i, 100 + i, "newera", i));
        }
        int attemptsBefore = source.attempts();
        await(() -> source.attempts() > attemptsBefore);

        // While the first 20 rows are out with the failing batch: a newer state for one of
        // them, and enough new players to fill the queue to its limit.
        UUID updated = first.get(5);
        writer.submit(record(updated, "A5", 9_999, "newera", 5_000L));
        List<UUID> second = new ArrayList<>();
        for (int i = 0; i < 99; i++) {
            UUID id = new UUID(2L, i);
            second.add(id);
            writer.submit(record(id, "B" + i, 500 + i, "newera", 1_000L + i));
        }
        assertTrue(writer.pendingCount() <= 100);

        await(() -> metrics.databaseFailures.sum() >= 1);
        Thread.sleep(50L);
        // The 20 restored rows did not push the queue past its limit.
        assertEquals(100, writer.pendingCount());

        source.outageDelayMillis = 0L;
        source.down = false;
        await(() -> writer.pendingCount() == 0);
        assertEquals(100, metrics.rowsWritten.sum());

        // The newer state won over the copy that was in the failed batch.
        assertEquals(9_999, database.run(connection -> repository.findByUuid(connection, updated)).score());
        // What had to go was the oldest data: the rest of the failed batch.
        assertNull(database.run(connection -> repository.findByUuid(connection, first.get(0))));
        Map<UUID, PlayerRecord> newest = database.run(connection -> repository.findByUuids(connection, second));
        assertEquals(99, newest.size());
        writer.shutdown(1_000L);
    }

    // ---- ranking -------------------------------------------------------------------------

    @Test
    void rankingIsLimitedOrderedAndCached() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        List<PlayerRecord> records = new ArrayList<>();
        UUID best = UUID.randomUUID();
        for (int i = 1; i <= 30; i++) {
            records.add(record(i == 30 ? best : UUID.randomUUID(), "P" + i, i * 10, "newera", i));
        }
        store(database, records);

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
    }

    @Test
    void tiedPlayersGetDistinctPositionsThatMatchTheList() throws Exception {
        // The reported case: two players with the same score. The one who reached it first
        // is ahead, and the individual position must say so too.
        Database database = source.open(metrics, 50L, 200L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        store(database, List.of(
                record(second, "Second", 1000, "newera", 200L),
                record(first, "First", 1000, "newera", 100L),
                record(third, "Third", 900, "newera", 50L)));

        LookupService service = lookups(database);
        LookupService.TopSnapshot top = service.top().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(first, second, third), top.rows().stream().map(PlayerRecord::uuid).toList());

        Map<UUID, Integer> positions = database.run(
                connection -> repository.positions(connection, List.of(first, second, third)));
        assertEquals(1, positions.get(first));
        assertEquals(2, positions.get(second));
        assertEquals(3, positions.get(third));
        assertEquals(top.positions(), positions);
    }

    @Test
    void positionsFollowTheListAcrossThePageLimitAndBeyondTheCache() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        // 60 players in groups of 8 with the same score; inside a group, pairs also share
        // the update time, so all three criteria decide somewhere. The cached ranking
        // holds 10 rows, which cuts through the second group of ties.
        List<PlayerRecord> records = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            records.add(record(UUID.randomUUID(), "P" + i, 2000 - (i / 8) * 100, "newera", 100L + (i % 8) / 2));
        }
        store(database, records);
        List<PlayerRecord> expected = new ArrayList<>(records);
        expected.sort(ScoreRepository.RANK_ORDER);
        List<UUID> order = expected.stream().map(PlayerRecord::uuid).toList();

        LookupService service = lookups(database);
        LookupService.TopSnapshot top = service.top().get(5, TimeUnit.SECONDS);
        assertEquals(order.subList(0, 10), top.rows().stream().map(PlayerRecord::uuid).toList());

        // Individual positions straight from the database, for everyone.
        Map<UUID, Integer> positions = database.run(connection -> repository.positions(connection, order));
        for (int i = 0; i < order.size(); i++) {
            assertEquals(i + 1, positions.get(order.get(i)), "position of row " + i);
        }

        // The same through the cache the placeholder reads.
        order.forEach(service::trackPosition);
        service.position(order.get(0));
        assertTrue(service.refreshPositions(POSITION_INTERVAL));
        await(() -> service.position(order.get(59)) == 60);
        for (int i = 0; i < order.size(); i++) {
            assertEquals(i + 1, service.position(order.get(i)), "cached position of row " + i);
        }
        // Rows 10 and 11 are tied on score, on either side of the cached page.
        assertEquals(expected.get(9).score(), expected.get(10).score());
        assertEquals(10, service.position(order.get(9)));
        assertEquals(11, service.position(order.get(10)));

        // A player without a row has no position.
        UUID unknown = UUID.randomUUID();
        assertTrue(database.run(connection -> repository.positions(connection, List.of(unknown))).isEmpty());
    }

    // ---- placeholders --------------------------------------------------------------------

    @Test
    void readingPositionsNeverQueriesAndRefreshesFollowTheirOwnClock() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        UUID player = UUID.randomUUID();
        store(database, List.of(
                record(UUID.randomUUID(), "Top", 2000, "newera", 1L),
                record(player, "Zwo", 1000, "newera", 2L)));
        LookupService service = lookups(database);
        service.trackPosition(player);
        int connectionsBefore = source.connectionsOpened();
        long queriesBefore = metrics.readQueries.sum();

        // A TAB refreshing as fast as it likes: still no query, no queued work.
        for (int i = 0; i < 100_000; i++) {
            assertEquals(-1, service.position(player));
        }
        assertEquals(queriesBefore, metrics.readQueries.sum());
        assertEquals(connectionsBefore, source.connectionsOpened());
        assertEquals(0, service.queuedReads());

        // The refresh is a separate, clocked step: one batched read.
        long now = 1_000_000L;
        assertTrue(service.refreshPositions(now));
        await(() -> service.position(player) == 2);
        assertEquals(queriesBefore + 1, metrics.readQueries.sum());

        // More placeholder traffic and more refresh calls inside the interval: nothing.
        for (int i = 0; i < 100_000; i++) {
            assertEquals(2, service.position(player));
        }
        for (long t = now; t < now + POSITION_INTERVAL; t += 50L) {
            assertFalse(service.refreshPositions(t));
        }
        assertEquals(queriesBefore + 1, metrics.readQueries.sum());

        // Next interval: one more.
        assertTrue(service.refreshPositions(now + POSITION_INTERVAL));
        await(() -> metrics.readQueries.sum() == queriesBefore + 2);

        // Nobody asked for a position since then: the refresh stays idle.
        Thread.sleep(100L);
        assertFalse(service.refreshPositions(now + 10 * POSITION_INTERVAL));
        assertEquals(queriesBefore + 2, metrics.readQueries.sum());
    }

    @Test
    void latePositionAnswerDoesNotBringBackAPlayerWhoLeft() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        UUID stays = UUID.randomUUID();
        UUID leaves = UUID.randomUUID();
        store(database, List.of(
                record(stays, "Stays", 1000, "newera", 1L),
                record(leaves, "Leaves", 900, "newera", 2L)));
        LookupService service = lookups(database);
        service.trackPosition(stays);
        service.trackPosition(leaves);
        service.position(stays);

        assertTrue(service.refreshPositions(1_000_000L));
        service.forget(leaves);
        await(() -> service.position(stays) == 1);
        assertEquals(-1, service.position(leaves));
        assertEquals(1, service.trackedPositions());
    }

    // ---- lookups -------------------------------------------------------------------------

    @Test
    void offlineLookupIsCached() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        store(database, List.of(record(UUID.randomUUID(), "Zwo", 900, "newera", 7L)));

        LookupService service = lookups(database);
        long before = metrics.readQueries.sum();
        Optional<PlayerRecord> found = service.lookupByName("Zwo").get(5, TimeUnit.SECONDS);
        assertEquals(900, found.orElseThrow().score());
        assertTrue(service.lookupByName("Zwo").isDone());
        assertTrue(service.lookupByName("Nobody").get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(service.lookupByName("nobody").isDone(), "negative results are cached too");
        assertEquals(before + 2, metrics.readQueries.sum());
    }

    @Test
    void groupedLookupReturnsOnlyExistingRows() throws Exception {
        Database database = source.open(metrics, 50L, 200L);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        store(database, List.of(record(a, "A", 10, "newera", 1L), record(b, "B", 20, "newera", 2L)));

        LookupService service = lookups(database);
        long before = metrics.readQueries.sum();
        Map<UUID, PlayerRecord> found = service.loadByUuids(List.of(a, missing, b)).get(5, TimeUnit.SECONDS);
        assertEquals(2, found.size());
        assertEquals(10, found.get(a).score());
        assertEquals(20, found.get(b).score());
        assertEquals(before + 1, metrics.readQueries.sum(), "one query for the whole group");
    }

    // ---- official source versus query servers --------------------------------------------

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

    private RemoteScores queryServer(Database database, LookupService service, Metrics counters) {
        return new RemoteScores(new RemoteScores.Loader() {
            @Override
            public boolean available() {
                return database.isAvailable();
            }

            @Override
            public CompletableFuture<Map<UUID, PlayerRecord>> load(List<UUID> ids) {
                return service.loadByUuids(ids);
            }
        }, counters, 5_000L, 30_000L, 100, 3);
    }

    @Test
    void queryServerPicksUpAScoreWrittenAfterItsJoinRead() throws Exception {
        UUID id = UUID.randomUUID();
        Database origin = source.open(metrics, 50L, 200L);
        origin.run(connection -> 1);

        Metrics hubMetrics = new Metrics();
        Database hubDatabase = source.open(hubMetrics, 50L, 200L);
        LookupService hubReader = lookups(hubDatabase, hubMetrics);
        RemoteScores hub = queryServer(hubDatabase, hubReader, hubMetrics);

        // The player lands on the hub first; New Era has not flushed yet.
        hub.open(id, 0L);
        assertEquals(1, hub.poll(0L));
        await(() -> hubMetrics.readQueries.sum() == 1);
        assertNull(hub.get(id));

        // New Era flushes the result of the quit evaluation.
        WriteQueue newEra = queue(origin, 50L, 100, 1000);
        newEra.submit(record(id, "Zwo", 1408, "newera", 500L));
        assertEquals(0, newEra.shutdown(5_000L).unconfirmed());

        // The recheck brings it in.
        assertEquals(1, hub.poll(5_000L));
        await(() -> hub.get(id) != null);
        assertEquals(1408, hub.get(id).record().score());
        assertEquals("newera", hub.get(id).record().server());

        // A later change on New Era arrives with the periodic refresh.
        WriteQueue later = queue(origin, 50L, 100, 1000);
        later.submit(record(id, "Zwo", 1500, "newera", 900L));
        assertEquals(0, later.shutdown(5_000L).unconfirmed());
        assertEquals(1, hub.poll(35_000L));
        await(() -> hub.get(id).record().score() == 1500);

        // The hub only ever read: three grouped queries, no row written by it.
        assertEquals(3, hubMetrics.readQueries.sum());
        assertEquals(0, hubMetrics.rowsWritten.sum());
        assertEquals(0, hubMetrics.writesQueued.sum());
    }

    @Test
    void queryServerRecoversAfterAFailedJoinRead() throws Exception {
        UUID id = UUID.randomUUID();
        Database origin = source.open(metrics, 50L, 200L);
        store(origin, List.of(record(id, "Zwo", 1408, "newera", 500L)));

        Metrics hubMetrics = new Metrics();
        Database hubDatabase = source.open(hubMetrics, 800L, 800L);
        hubDatabase.run(connection -> 1);
        LookupService hubReader = lookups(hubDatabase, hubMetrics);
        RemoteScores hub = queryServer(hubDatabase, hubReader, hubMetrics);

        source.down = true;
        hub.open(id, 0L);
        assertEquals(1, hub.poll(0L));
        await(() -> hubMetrics.databaseFailures.sum() == 1);
        assertNull(hub.get(id));
        // While the database is in its retry window nothing is sent at all.
        assertEquals(0, hub.poll(5_000L));

        source.down = false;
        await(hubDatabase::isAvailable);
        assertEquals(1, hub.poll(6_000L));
        await(() -> hub.get(id) != null);
        assertEquals(1408, hub.get(id).record().score());
    }

    @Test
    void officialScoreIsReadUnchangedFromOtherServers() throws Exception {
        UUID id = UUID.randomUUID();

        // New Era, an official source, persists the score.
        WriteQueue newEra = queue(source.open(metrics, 50L, 200L), 50L, 100, 1000);
        newEra.submit(record(id, "Zwo", 1408, "newera", 500L));
        assertEquals(0, newEra.shutdown(5_000L).unconfirmed());

        // Hub and events run read-only: they are built without a write queue, so whatever
        // temporary kit the player wears there, the row stays as New Era left it.
        for (String server : List.of("hub", "eventos")) {
            Metrics readOnly = new Metrics();
            LookupService service = lookups(source.open(readOnly, 50L, 200L), readOnly);
            PlayerRecord seen = service.loadByUuid(id).get(5, TimeUnit.SECONDS);
            assertEquals(1408, seen.score(), server);
            assertEquals("newera", seen.server(), server);
            assertEquals(500L, seen.updatedAt(), server);
            assertEquals(0, readOnly.rowsWritten.sum(), server);
        }
        assertNull(lookups(source.open(metrics, 50L, 200L))
                .loadByUuid(UUID.randomUUID()).get(5, TimeUnit.SECONDS));
    }
}
