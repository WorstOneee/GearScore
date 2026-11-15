package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.model.PlayerRecord;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of a server that only reads the official score (hub, events).
 */
class RemoteScoresTest {

    private static final long RECHECK = 5_000L;
    private static final long INTERVAL = 30_000L;

    /** Stand-in for the database: answers from a map, immediately or when released. */
    private static final class FakeDatabase implements RemoteScores.Loader {
        final Map<UUID, PlayerRecord> rows = new HashMap<>();
        final List<List<UUID>> requests = new ArrayList<>();
        final List<CompletableFuture<Map<UUID, PlayerRecord>>> held = new ArrayList<>();
        final List<Map<UUID, PlayerRecord>> pendingAnswers = new ArrayList<>();
        boolean available = true;
        boolean failing;
        boolean hold;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public CompletableFuture<Map<UUID, PlayerRecord>> load(List<UUID> ids) {
            requests.add(List.copyOf(ids));
            if (failing) {
                return CompletableFuture.failedFuture(new SQLException("simulated failure"));
            }
            Map<UUID, PlayerRecord> answer = new HashMap<>();
            for (UUID id : ids) {
                PlayerRecord row = rows.get(id);
                if (row != null) {
                    answer.put(id, row);
                }
            }
            if (hold) {
                CompletableFuture<Map<UUID, PlayerRecord>> future = new CompletableFuture<>();
                // The answer is fixed now and delivered later, like a slow query.
                held.add(future);
                pendingAnswers.add(answer);
                return future;
            }
            return CompletableFuture.completedFuture(answer);
        }

        void release(int index) {
            held.get(index).complete(pendingAnswers.get(index));
        }
    }

    private final FakeDatabase database = new FakeDatabase();
    private final Metrics metrics = new Metrics();
    private final RemoteScores remote = new RemoteScores(database, metrics, RECHECK, INTERVAL, 100, 3);
    private final UUID player = UUID.randomUUID();

    private static PlayerRecord record(UUID id, int score, long updatedAt) {
        return new PlayerRecord(id, "Zwo", score, score, 0, "elite", "newera", 1, updatedAt);
    }

    @Test
    void scoreWrittenAfterTheJoinReadArrivesOnTheRecheck() {
        // The player reaches the hub before New Era has flushed the latest result.
        database.rows.put(player, record(player, 900, 100L));
        remote.open(player, 0L);
        assertEquals(1, remote.poll(0L));
        assertEquals(900, remote.get(player).record().score());

        database.rows.put(player, record(player, 1408, 200L));
        assertEquals(0, remote.poll(RECHECK - 1), "nothing is asked before the recheck is due");
        assertEquals(900, remote.get(player).record().score());
        assertEquals(1, remote.poll(RECHECK));
        assertEquals(1408, remote.get(player).record().score());
    }

    @Test
    void playerWithoutARowAtJoinGetsItLater() {
        remote.open(player, 0L);
        remote.poll(0L);
        assertNull(remote.get(player));
        remote.poll(RECHECK);
        assertNull(remote.get(player));

        // First evaluation on the official server happens later.
        database.rows.put(player, record(player, 500, 300L));
        assertEquals(0, remote.poll(INTERVAL - 1));
        assertEquals(1, remote.poll(INTERVAL));
        assertEquals(500, remote.get(player).record().score());
    }

    @Test
    void laterChangesKeepArrivingAtTheInterval() {
        database.rows.put(player, record(player, 500, 100L));
        remote.open(player, 0L);
        remote.poll(0L);
        remote.poll(RECHECK);
        long now = 0L;
        for (int i = 1; i <= 3; i++) {
            database.rows.put(player, record(player, 500 + i, 100L + i));
            now += INTERVAL;
            assertEquals(0, remote.poll(now - 1));
            assertEquals(1, remote.poll(now));
            assertEquals(500 + i, remote.get(player).record().score());
        }
        assertEquals(5, database.requests.size());
    }

    @Test
    void failedReadAtJoinIsRetriedAndRecovers() {
        database.rows.put(player, record(player, 700, 100L));
        database.failing = true;
        remote.open(player, 0L);
        assertEquals(1, remote.poll(0L));
        assertNull(remote.get(player));

        database.failing = false;
        assertEquals(1, remote.poll(RECHECK));
        assertEquals(700, remote.get(player).record().score());
    }

    @Test
    void nothingIsAskedWhileTheDatabaseIsDownAndReadsResumeAfterwards() {
        database.rows.put(player, record(player, 700, 100L));
        database.available = false;
        remote.open(player, 0L);
        for (long now = 0; now <= 120_000L; now += 1_000L) {
            assertEquals(0, remote.poll(now));
        }
        assertTrue(database.requests.isEmpty());
        assertNull(remote.get(player));

        database.available = true;
        assertEquals(1, remote.poll(121_000L));
        assertEquals(700, remote.get(player).record().score());
    }

    @Test
    void repeatedPollsDoNotRepeatQueries() {
        database.rows.put(player, record(player, 700, 100L));
        remote.open(player, 0L);
        for (long now = 0; now < RECHECK; now += 50L) {
            remote.poll(now);
        }
        assertEquals(1, database.requests.size());
    }

    @Test
    void lateAnswerAfterQuitDoesNotRepopulateTheCache() {
        database.rows.put(player, record(player, 700, 100L));
        database.hold = true;
        remote.open(player, 0L);
        remote.poll(0L);
        remote.close(player);

        database.release(0);
        assertNull(remote.get(player));
        assertEquals(0, remote.size());
        assertEquals(1, metrics.remoteDiscarded.sum());
    }

    @Test
    void lateAnswerFromThePreviousSessionIsIgnoredAfterReconnect() {
        database.rows.put(player, record(player, 700, 100L));
        database.hold = true;
        remote.open(player, 0L);
        remote.poll(0L);
        long firstToken = remote.token(player);

        // Quit and come back while the first query is still running.
        remote.close(player);
        database.rows.put(player, record(player, 1200, 400L));
        remote.open(player, 1_000L);
        assertTrue(remote.token(player) != firstToken);
        remote.poll(1_000L);

        // The new session's answer lands first, then the old, slower one.
        database.release(1);
        assertEquals(1200, remote.get(player).record().score());
        database.release(0);
        assertEquals(1200, remote.get(player).record().score());
        assertEquals(1, metrics.remoteDiscarded.sum());
    }

    @Test
    void olderAnswerNeverReplacesNewerData() {
        remote.open(player, 0L);
        long token = remote.token(player);
        assertTrue(remote.apply(player, token, record(player, 1200, 400L)));
        assertEquals(false, remote.apply(player, token, record(player, 700, 100L)));
        assertEquals(false, remote.apply(player, token, record(player, 1200, 400L)));
        assertEquals(1200, remote.get(player).record().score());
        assertTrue(remote.apply(player, token, record(player, 800, 500L)), "a newer, lower score does replace");
        assertEquals(800, remote.get(player).record().score());
        assertEquals(false, remote.apply(player, token + 99, record(player, 1, 900L)));
        assertEquals(false, remote.apply(player, token, null));
        assertNotNull(remote.get(player));
    }

    @Test
    void readsAreGroupedAndCappedPerCycle() {
        RemoteScores limited = new RemoteScores(database, metrics, RECHECK, INTERVAL, 100, 3);
        database.hold = true;
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            UUID id = new UUID(1L, i);
            players.add(id);
            database.rows.put(id, record(id, i, 100L));
            limited.open(id, 0L);
        }
        // 450 players are due at once: three batches of 100 go out, the rest wait.
        assertEquals(3, limited.poll(0L));
        assertEquals(0, limited.poll(1_000L), "no new batch while three are still running");
        assertEquals(3, database.requests.size());
        for (List<UUID> request : database.requests) {
            assertEquals(100, request.size());
        }

        database.release(0);
        database.release(1);
        database.release(2);
        assertEquals(2, limited.poll(2_000L));
        assertEquals(100, database.requests.get(3).size());
        assertEquals(50, database.requests.get(4).size());
        database.release(3);
        database.release(4);

        for (UUID id : players) {
            assertNotNull(limited.get(id));
        }
        // 450 players cost 5 queries, not 450.
        assertEquals(5, database.requests.size());
    }

    @Test
    void playersWhoJoinedAtDifferentTimesShareThePeriodicQuery() {
        List<UUID> players = List.of(new UUID(3L, 1), new UUID(3L, 2), new UUID(3L, 3));
        long[] joins = {0L, 7_000L, 13_000L};
        for (long now = 0L; now < INTERVAL; now += 1_000L) {
            for (int i = 0; i < 3; i++) {
                if (joins[i] == now) {
                    remote.open(players.get(i), now);
                }
            }
            remote.poll(now);
        }
        // Each player: one read at join and one recheck.
        assertEquals(6, database.requests.size());

        // From then on a single grouped read per interval serves all of them.
        for (long now = INTERVAL; now < 3 * INTERVAL; now += 1_000L) {
            remote.poll(now);
        }
        assertEquals(8, database.requests.size());
        assertEquals(3, database.requests.get(6).size());
        assertEquals(3, database.requests.get(7).size());
    }

    @Test
    void closingTheLastSessionLeavesNothingBehind() {
        remote.open(player, 0L);
        remote.poll(0L);
        remote.close(player);
        assertTrue(remote.isEmpty());
        assertEquals(0, remote.poll(RECHECK));
        assertEquals(0, remote.poll(RECHECK + INTERVAL));
        assertEquals(1, database.requests.size());
    }
}
