package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.ScoreEngine;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static br.com.serpens.gearscore.TestRules.armorSet;
import static br.com.serpens.gearscore.TestRules.gear;
import static br.com.serpens.gearscore.TestRules.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evaluation engine and the write queue working together against a database: a result
 * only counts as persisted once its transaction commits, and nothing the queue refuses or
 * drops is forgotten. Runs on H2 by default and on a real MariaDB with -Dgearscore.test.jdbc.
 */
class WriteRecoveryTest {

    private static final Logger LOGGER = Logger.getLogger("GearScoreTest");

    static {
        LOGGER.setUseParentHandlers(false);
    }

    @TempDir
    Path folder;

    private final RuleSet rules = TestRules.defaults();
    private final Metrics metrics = new Metrics();
    private final TestDatabase source = new TestDatabase();
    private final ScoreRepository repository = new ScoreRepository(source.prefix());
    private final Map<UUID, GearSnapshot> inventories = new HashMap<>();
    private final List<WriteQueue> queues = new ArrayList<>();
    private Database database;
    private WriteQueue queue;
    private ScoreEngine engine;
    private long clock = 1_000_000L;

    @AfterEach
    void cleanUp() {
        source.down = false;
        source.outageDelayMillis = 0L;
        source.connectDelayMillis = 0L;
        queues.forEach(q -> q.shutdown(200L));
        source.drop();
    }

    private Path spoolFile() {
        return folder.resolve("pending-writes.dat");
    }

    /** One official server: engine, queue and database, like the plugin wires them. */
    private void start(int maxPending, int batchSize, long flushMillis, long retryMillis, boolean spool) {
        database = source.open(metrics, retryMillis, retryMillis);
        queue = new WriteQueue(database, repository, metrics, LOGGER, flushMillis, batchSize, maxPending,
                spool ? new PendingSpool(spoolFile()) : null, 0L);
        queues.add(queue);
        WriteQueue target = queue;
        engine = new ScoreEngine(rules, "newera", inventories::get, new ScoreEngine.Sink() {
            @Override
            public boolean submit(PlayerRecord record, long sequence, boolean recoverable) {
                return target.submit(record, sequence, recoverable);
            }

            @Override
            public boolean hasRoom() {
                return target.hasRoom();
            }
        }, metrics, () -> ++clock);
        queue.setListener(engine);
    }

    private UUID join(String name, String sword) {
        UUID id = UUID.randomUUID();
        engine.track(id, name);
        inventories.put(id, loadout(sword));
        return id;
    }

    private GearSnapshot loadout(String sword) {
        return gear(armorSet(rules, "netherite", "protection", 4), item(rules, sword, "sharpness", 5));
    }

    private PlayerRecord row(UUID id) {
        try {
            // Read through a separate gate so the queue's backoff does not get in the way.
            return source.open(new Metrics(), 1L, 1L).run(connection -> repository.findByUuid(connection, id));
        } catch (SQLException e) {
            return null;
        }
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

    /** What the plugin's once-a-second task does, until the condition holds. */
    private void pump(BooleanSupplier condition) throws InterruptedException {
        await(() -> {
            engine.recover(50);
            return condition.getAsBoolean();
        });
    }

    // 1 ---------------------------------------------------------------------------------

    @Test
    void fullQueueDoesNotLoseTheOtherPlayerWhoseGearNeverChanges() throws Exception {
        // The reported reproduction: room for one player, two players evaluated.
        start(1, 1, 50L, 100L, false);
        source.down = true;
        UUID a = join("A", "netherite_sword");
        UUID b = join("B", "iron_sword");
        engine.evaluateNow(a);
        engine.evaluateNow(b);
        assertEquals(1, queue.pendingCount());
        assertEquals(1, engine.owedCount(), "the result that did not fit is still owed");

        // Both are evaluated again with the same equipment: nothing new is calculated.
        engine.evaluateNow(a);
        engine.evaluateNow(b);
        assertEquals(2, metrics.evaluations.sum());

        source.down = false;
        pump(() -> row(a) != null && row(b) != null);
        assertEquals(1100, row(a).score());
        assertEquals(1065, row(b).score());
        pump(() -> queue.pendingCount() == 0 && !engine.hasOwed());
        assertEquals(0, metrics.writesLost.sum());
        assertEquals(2, metrics.captures.sum() - metrics.unchanged.sum(), "inventories were not read again");
    }

    @Test
    void anEntryEvictedForALeavingPlayerIsRecovered() throws Exception {
        // The same loss through the other door: A is in the queue and gets evicted.
        start(1, 1, 50L, 100L, false);
        source.down = true;
        UUID a = join("A", "netherite_sword");
        UUID c = join("C", "diamond_sword");
        engine.evaluateNow(a);
        engine.evaluateNow(c);
        assertEquals(1, engine.owedCount());

        // C leaves: its result has no other holder, so it takes A's place. A is told.
        engine.forget(c);
        inventories.remove(c);
        assertEquals(1, queue.pendingCount());
        assertEquals(1, metrics.writesDropped.sum());
        assertEquals(1, engine.owedCount());
        assertTrue(engine.hasOwed());

        source.down = false;
        pump(() -> row(a) != null && row(c) != null);
        assertEquals(1100, row(a).score());
        assertEquals(1085, row(c).score());
        assertEquals(0, metrics.writesLost.sum());
    }

    // 2 ---------------------------------------------------------------------------------

    @Test
    void saturatedQueueDuringAnOutageRecoversEveryoneWhenTheDatabaseReturns() throws Exception {
        start(5, 5, 50L, 100L, false);
        source.down = true;
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(join("P" + i, "netherite_sword"));
        }
        // Three rounds of changes while the database is down.
        String[] swords = {"netherite_sword", "diamond_sword", "iron_sword"};
        for (String sword : swords) {
            for (UUID id : players) {
                inventories.put(id, loadout(sword));
                engine.evaluateNow(id);
                engine.recover(50);
                assertTrue(queue.pendingCount() <= 5, "queue limit");
                assertTrue(engine.owedCount() <= players.size(), "one owed place per player");
            }
        }
        assertEquals(5, queue.pendingCount());
        assertEquals(15, engine.owedCount());
        assertEquals(0, metrics.rowsWritten.sum());

        source.down = false;
        pump(() -> players.stream().allMatch(id -> row(id) != null && row(id).score() == 1065));
        pump(() -> queue.pendingCount() == 0 && !engine.hasOwed());
        assertEquals(0, metrics.writesLost.sum());
        // Each player was written once per state that actually reached the database.
        assertTrue(metrics.rowsWritten.sum() <= 20 + 5, "rows=" + metrics.rowsWritten.sum());
    }

    // 3 and 4 ---------------------------------------------------------------------------

    @Test
    void aNewerResultArrivingDuringTheWriteOfTheOldOneIsAlsoWritten() throws Exception {
        start(10, 10, 50L, 100L, false);
        database.run(connection -> 1);
        UUID a = join("A", "netherite_sword");
        source.connectDelayMillis = 400L;
        engine.evaluateNow(a);
        int attempts = source.attempts();
        await(() -> source.attempts() > attempts);

        // The first write is on its way; the player changes equipment.
        inventories.put(a, loadout("iron_sword"));
        engine.evaluateNow(a);
        source.connectDelayMillis = 0L;

        await(() -> metrics.rowsWritten.sum() >= 2);
        assertEquals(1065, row(a).score());
        assertFalse(engine.hasOwed());
        assertEquals(0, queue.pendingCount());
        // The commit of the first result did not make the engine stop caring about the second.
        engine.evaluateNow(a);
        assertEquals(2, metrics.rowsWritten.sum());
    }

    // 5 ---------------------------------------------------------------------------------

    @Test
    void aFailedBatchIsRestoredWithoutReplacingANewerResult() throws Exception {
        start(10, 10, 50L, 300L, false);
        database.run(connection -> 1);
        UUID a = join("A", "netherite_sword");
        UUID b = join("B", "netherite_sword");
        // The write hangs and then fails.
        source.outageDelayMillis = 400L;
        source.down = true;
        engine.evaluateNow(a);
        engine.evaluateNow(b);
        int attempts = source.attempts();
        await(() -> source.attempts() > attempts);

        // While the batch is failing, A changes equipment.
        inventories.put(a, loadout("iron_sword"));
        engine.evaluateNow(a);
        await(() -> metrics.databaseFailures.sum() >= 1);
        Thread.sleep(50L);
        assertEquals(2, queue.pendingCount());

        source.outageDelayMillis = 0L;
        source.down = false;
        pump(() -> queue.pendingCount() == 0 && !engine.hasOwed());
        assertEquals(1065, row(a).score(), "the newer result won");
        assertEquals(1100, row(b).score());
    }

    @Test
    void theDatabaseNeverLetsAnOlderResultReplaceANewerOne() throws Exception {
        start(10, 10, 50L, 100L, false);
        UUID id = UUID.randomUUID();
        PlayerRecord newer = new PlayerRecord(id, "Zwo", 1400, 1046, 354, "mitico", "newera", 3, 2_000L);
        PlayerRecord older = new PlayerRecord(id, "Old", 300, 200, 100, "aprendiz", "newera", 2, 1_000L);
        database.run(connection -> {
            repository.upsert(connection, List.of(newer));
            repository.upsert(connection, List.of(older));
            return null;
        });
        assertEquals(newer, row(id));

        // Same time: the later write wins. Newer time: replaced as usual.
        PlayerRecord sameTime = new PlayerRecord(id, "Zwo", 1405, 1046, 359, "mitico", "newera", 3, 2_000L);
        PlayerRecord latest = new PlayerRecord(id, "Zwo", 900, 700, 200, "elite", "newera", 3, 3_000L);
        database.run(connection -> {
            repository.upsert(connection, List.of(sameTime));
            return null;
        });
        assertEquals(sameTime, row(id));
        database.run(connection -> {
            repository.upsert(connection, List.of(latest, older));
            return null;
        });
        assertEquals(latest, row(id));
    }

    // 6 ---------------------------------------------------------------------------------

    @Test
    void quitAndReconnectDuringAWriteKeepsTheNewSessionsResult() throws Exception {
        start(10, 10, 50L, 100L, false);
        database.run(connection -> 1);
        UUID a = UUID.randomUUID();
        engine.track(a, "A");
        inventories.put(a, loadout("netherite_sword"));
        source.connectDelayMillis = 400L;
        engine.evaluateNow(a);
        int attempts = source.attempts();
        await(() -> source.attempts() > attempts);

        // Leaves and comes back with other equipment while the first write is in flight.
        engine.forget(a);
        engine.track(a, "A");
        inventories.put(a, loadout("iron_sword"));
        engine.evaluateNow(a);
        source.connectDelayMillis = 0L;

        pump(() -> queue.pendingCount() == 0 && !engine.hasOwed() && metrics.rowsWritten.sum() >= 2);
        assertEquals(1065, row(a).score());
        // The late confirmation of the first session did not settle the second one early:
        // a further unchanged evaluation writes nothing more.
        engine.evaluateNow(a);
        Thread.sleep(150L);
        assertEquals(2, metrics.rowsWritten.sum());
    }

    // 7 ---------------------------------------------------------------------------------

    @Test
    void aPlayerWhoLeftWithARefusedResultIsStillWritten() throws Exception {
        start(1, 1, 50L, 100L, false);
        source.down = true;
        UUID stays = join("Stays", "netherite_sword");
        UUID leaves = join("Leaves", "iron_sword");
        engine.evaluateNow(stays);
        engine.evaluateNow(leaves);
        assertEquals(1, engine.owedCount());

        // The refused player disconnects. Nobody will evaluate them again.
        engine.forget(leaves);
        inventories.remove(leaves);

        source.down = false;
        pump(() -> row(leaves) != null && row(stays) != null);
        assertEquals(1065, row(leaves).score());
        assertEquals(1100, row(stays).score());
        assertEquals(0, metrics.writesLost.sum());
    }

    @Test
    void aPlayerWhoLeftWhileQueuedIsWrittenWhenTheDatabaseReturns() throws Exception {
        start(10, 10, 50L, 100L, false);
        source.down = true;
        UUID a = join("A", "netherite_sword");
        engine.evaluateNow(a);
        engine.forget(a);
        assertEquals(1, queue.pendingCount());
        source.down = false;
        await(() -> row(a) != null);
        assertEquals(1100, row(a).score());
    }

    // 8 ---------------------------------------------------------------------------------

    @Test
    void limitsHoldWhileDroppingRestoringAndRecovering() throws Exception {
        start(10, 4, 20L, 40L, false);
        database.run(connection -> 1);
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            players.add(join("P" + i, "netherite_sword"));
        }
        String[] swords = {"netherite_sword", "diamond_sword", "iron_sword", "golden_sword"};
        // The database flaps while everybody keeps changing and some players leave.
        for (int round = 0; round < 24; round++) {
            source.down = round % 3 != 2;
            for (int i = 0; i < players.size(); i++) {
                UUID id = players.get(i);
                if (!engine.isTracked(id)) {
                    continue;
                }
                inventories.put(id, loadout(swords[(round + i) % swords.length]));
                engine.evaluateNow(id);
                if (round == 10 && i % 6 == 0) {
                    engine.forget(id);
                }
                assertTrue(queue.pendingCount() <= 10, "queue limit exceeded: " + queue.pendingCount());
                assertTrue(engine.owedCount() <= engine.trackedCount(), "owed exceeds tracked players");
            }
            engine.recover(50);
            Thread.sleep(15L);
        }
        source.down = false;
        pump(() -> queue.pendingCount() == 0 && !engine.hasOwed());
        // Every player who stayed online has the last state in the database.
        for (int i = 0; i < players.size(); i++) {
            UUID id = players.get(i);
            if (engine.isTracked(id)) {
                assertEquals(engine.cached(id).record().score(), row(id).score(), "player " + i);
            } else {
                assertNotNull(row(id), "left player " + i + " was written before or after leaving");
            }
        }
    }

    // 9 ---------------------------------------------------------------------------------

    @Test
    void unchangedAndConfirmedEquipmentIsNeverWrittenAgain() throws Exception {
        start(10, 10, 30L, 100L, false);
        UUID a = join("A", "netherite_sword");
        engine.evaluateNow(a);
        await(() -> row(a) != null);
        await(() -> queue.pendingCount() == 0);

        for (int i = 0; i < 20; i++) {
            inventories.put(a, loadout("netherite_sword"));
            engine.evaluateNow(a);
            engine.recover(50);
        }
        Thread.sleep(150L);
        assertEquals(1, metrics.rowsWritten.sum());
        assertEquals(1, metrics.writesQueued.sum());
        assertEquals(0, metrics.writesMerged.sum());
        assertEquals(0, metrics.writesRecovered.sum());
        assertFalse(engine.hasOwed());
    }

    // 10 --------------------------------------------------------------------------------

    @Test
    void unconfirmedWritesSurviveACleanShutdownAndRestart() throws Exception {
        start(10, 10, 50L, 100L, true);
        source.down = true;
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = join("P" + i, "netherite_sword");
            players.add(id);
            engine.evaluateNow(id);
        }
        // One more result is owed (not in the queue) when the server stops.
        assertEquals(3, queue.pendingCount());
        engine.handOver();
        long begin = System.nanoTime();
        WriteQueue.ShutdownResult result = queue.shutdown(2_000L);
        assertTrue((System.nanoTime() - begin) / 1_000_000L < 2_500L, "shutdown is bounded");
        assertEquals(3, result.unconfirmed());
        assertEquals(3, result.savedToDisk());
        assertTrue(Files.exists(spoolFile()));
        for (UUID id : players) {
            assertNull(row(id));
        }

        // Restart with the database back: a new queue reads the file and writes the rows.
        source.down = false;
        Metrics restarted = new Metrics();
        WriteQueue next = new WriteQueue(source.open(restarted, 100L, 100L), repository, restarted, LOGGER,
                50L, 10, 10, new PendingSpool(spoolFile()), 0L);
        queues.add(next);
        next.recoverSpool();
        await(() -> players.stream().allMatch(id -> row(id) != null));
        assertEquals(3, restarted.spoolRecovered.sum());
        for (UUID id : players) {
            assertEquals(1100, row(id).score());
        }
        await(() -> !Files.exists(spoolFile()));
    }

    @Test
    void theSpoolIsWrittenDuringAnOutageAndReplayedWithoutUndoingNewerRows() throws Exception {
        start(10, 10, 30L, 60L, true);
        database.run(connection -> 1);
        source.down = true;
        UUID a = join("A", "netherite_sword");
        engine.evaluateNow(a);
        // No clean shutdown here: the file appears while the database is down.
        await(() -> Files.exists(spoolFile()));
        assertTrue(metrics.spoolSaves.sum() >= 1);
        List<PlayerRecord> saved = new PendingSpool(spoolFile()).load(100);
        assertEquals(1, saved.size());
        assertEquals(1100, saved.get(0).score());
        Path copy = folder.resolve("copy.dat");
        Files.copy(spoolFile(), copy);

        // The database returns, the row is written and the file is removed.
        source.down = false;
        await(() -> row(a) != null && !Files.exists(spoolFile()));

        // Later the player improves; then a stale copy of the old file shows up at a restart.
        inventories.put(a, gear(armorSet(rules, "netherite", "protection", 4, "unbreaking", 3),
                item(rules, "netherite_sword", "sharpness", 5)));
        engine.evaluateNow(a);
        await(() -> row(a).score() == 1160);
        queue.shutdown(1_000L);
        Files.copy(copy, spoolFile());
        Metrics restarted = new Metrics();
        WriteQueue next = new WriteQueue(source.open(restarted, 100L, 100L), repository, restarted, LOGGER,
                30L, 10, 10, new PendingSpool(spoolFile()), 0L);
        queues.add(next);
        next.recoverSpool();
        await(() -> restarted.rowsWritten.sum() == 1);
        assertEquals(1160, row(a).score(), "the replayed older record did not replace the newer row");
        await(() -> !Files.exists(spoolFile()));
    }

    private static PlayerRecord saved(UUID id, String name, int score, long updatedAt) {
        return new PlayerRecord(id, name, score, score, 0, "elite", "newera", 3, updatedAt);
    }

    private WriteQueue restart(Metrics counters, int maxPending, int batchSize) {
        WriteQueue next = new WriteQueue(source.open(counters, 60L, 60L), repository, counters, LOGGER,
                30L, batchSize, maxPending, new PendingSpool(spoolFile()), 0L);
        queues.add(next);
        return next;
    }

    @Test
    void aSpoolRecordThatFindsTheQueueFullWaitsInsteadOfBeingLost() throws Exception {
        // The reported case: an old pending record in the file, and new updates filling the
        // queue before the file is restored.
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        new PendingSpool(spoolFile()).save(List.of(saved(old, "Old", 700, 100L)));
        source.open(new Metrics(), 1L, 1L).run(connection -> 1);

        source.down = true;
        Metrics counters = new Metrics();
        WriteQueue next = restart(counters, 1, 1);
        assertTrue(next.submit(saved(fresh, "Fresh", 900, 200L), 0L, false));
        next.recoverSpool();

        // No room: the record waits, and it is still in the file after the file is rewritten.
        await(() -> next.backlogCount() == 1);
        assertEquals(1, next.pendingCount());
        await(() -> counters.spoolSaves.sum() >= 1);
        List<UUID> onDisk = new PendingSpool(spoolFile()).load(100).stream().map(PlayerRecord::uuid).toList();
        assertTrue(onDisk.contains(old), "the unprocessed record must stay in the file");
        assertTrue(onDisk.contains(fresh));
        assertNull(row(old));

        // The database returns: the new update is written, then the old one gets its turn.
        source.down = false;
        await(() -> row(fresh) != null && row(old) != null);
        assertEquals(900, row(fresh).score());
        assertEquals(700, row(old).score());
        assertEquals(0, counters.writesLost.sum());
        assertEquals(0, counters.writesDropped.sum());
        assertEquals(1, counters.spoolRecovered.sum());
        await(() -> next.backlogCount() == 0 && next.pendingCount() == 0 && !Files.exists(spoolFile()));
    }

    @Test
    void recordsStillWaitingSurviveAnotherRestart() throws Exception {
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        new PendingSpool(spoolFile()).save(List.of(saved(old, "Old", 700, 100L)));
        source.open(new Metrics(), 1L, 1L).run(connection -> 1);

        source.down = true;
        Metrics first = new Metrics();
        WriteQueue one = restart(first, 1, 1);
        one.submit(saved(fresh, "Fresh", 900, 200L), 0L, false);
        one.recoverSpool();
        await(() -> one.backlogCount() == 1);

        // The server stops again with the old record still waiting for room.
        WriteQueue.ShutdownResult result = one.shutdown(1_000L);
        assertEquals(2, result.unconfirmed());
        assertEquals(2, result.savedToDisk());
        assertEquals(2, new PendingSpool(spoolFile()).load(100).size());

        source.down = false;
        Metrics second = new Metrics();
        WriteQueue two = restart(second, 1, 1);
        two.recoverSpool();
        await(() -> row(fresh) != null && row(old) != null);
        assertEquals(0, second.writesLost.sum());
        await(() -> !Files.exists(spoolFile()));
    }

    @Test
    void aLargeSpoolIsFedInGraduallyWithinTheQueueLimit() throws Exception {
        List<PlayerRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(saved(new UUID(8L, i), "P" + i, 500 + i, 100L + i));
        }
        new PendingSpool(spoolFile()).save(records);
        source.open(new Metrics(), 1L, 1L).run(connection -> 1);

        source.down = true;
        Metrics counters = new Metrics();
        WriteQueue next = restart(counters, 5, 2);
        next.recoverSpool();
        await(() -> next.pendingCount() == 5 && next.backlogCount() == 5);

        // New updates arriving meanwhile do not push the waiting records out.
        UUID live = UUID.randomUUID();
        assertFalse(next.submit(saved(live, "Live", 999, 900L), 7L, true), "full: an online result is refused");
        assertEquals(5, next.backlogCount());

        source.down = false;
        await(() -> records.stream().allMatch(record -> row(record.uuid()) != null));
        for (PlayerRecord record : records) {
            assertEquals(record.score(), row(record.uuid()).score());
        }
        assertEquals(0, counters.writesLost.sum());
        assertEquals(10, counters.spoolRecovered.sum());
        await(() -> !Files.exists(spoolFile()));
    }

    @Test
    void whatExceedsQueueAndBacklogIsCountedAsLost() throws Exception {
        List<PlayerRecord> records = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            records.add(saved(new UUID(9L, i), "P" + i, 500 + i, 100L + i));
        }
        new PendingSpool(spoolFile()).save(records);
        source.open(new Metrics(), 1L, 1L).run(connection -> 1);

        // Room for 3 in the queue and 3 waiting: capacity 6, the file brings 9.
        source.down = true;
        Metrics counters = new Metrics();
        WriteQueue next = restart(counters, 3, 3);
        next.recoverSpool();
        await(() -> next.pendingCount() == 3 && next.backlogCount() == 3);
        assertEquals(3, counters.writesLost.sum(), "the excess is reported, not hidden");

        source.down = false;
        await(() -> counters.rowsWritten.sum() == 6);
        // The three oldest of the excess are the ones given up.
        long written = records.stream().filter(record -> row(record.uuid()) != null).count();
        assertEquals(6, written);
    }

    @Test
    void aSpoolRecordSupersededByANewerPendingResultIsDropped() throws Exception {
        UUID id = UUID.randomUUID();
        new PendingSpool(spoolFile()).save(List.of(saved(id, "Zwo", 700, 100L)));
        source.open(new Metrics(), 1L, 1L).run(connection -> 1);

        source.down = true;
        Metrics counters = new Metrics();
        WriteQueue next = restart(counters, 5, 5);
        // The player already produced a newer result in this run.
        next.submit(saved(id, "Zwo", 1200, 500L), 3L, true);
        next.recoverSpool();
        Thread.sleep(150L);
        assertEquals(1, next.pendingCount());
        assertEquals(0, next.backlogCount());

        source.down = false;
        await(() -> row(id) != null);
        assertEquals(1200, row(id).score());
        assertEquals(0, counters.writesLost.sum());
    }

    @Test
    void aDamagedSpoolFileIsIgnoredSafely() throws Exception {
        Files.writeString(spoolFile(), "GEARSCORE-PENDING\t1\nnot-a-uuid\tx\t1\t1\t1\tt\ts\t1\t1\nshort line\n"
                + UUID.randomUUID() + "\tOk\t10\t5\t5\taprendiz\tnewera\t3\t50\n");
        assertEquals(1, new PendingSpool(spoolFile()).load(100).size());
        Files.writeString(spoolFile(), "something else entirely");
        assertTrue(new PendingSpool(spoolFile()).load(100).isEmpty());
        assertTrue(new PendingSpool(folder.resolve("missing.dat")).load(100).isEmpty());
    }

    @Test
    void whenNothingIsRecoverableTheLossIsCountedNotHidden() throws Exception {
        start(2, 2, 50L, 5_000L, false);
        source.down = true;
        // Three players evaluated and gone: no holder left for any of their results.
        List<UUID> gone = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = join("G" + i, "netherite_sword");
            gone.add(id);
            engine.evaluateNow(id);
            engine.forget(id);
        }
        assertEquals(2, queue.pendingCount());
        assertEquals(1, metrics.writesLost.sum());
        assertEquals(1, metrics.writesDropped.sum());
        assertFalse(engine.hasOwed());
    }
}
