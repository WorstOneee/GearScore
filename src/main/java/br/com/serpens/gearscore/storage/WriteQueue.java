package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.model.WriteListener;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Pending writes, consolidated by player: however many times a score changes before a
 * flush, only the latest state is written. One dedicated thread performs every write, in
 * batches. Nothing runs while the queue is empty.
 *
 * The queue never holds more than {@code maxPending} players and never loses a result
 * silently. A result is reported back as confirmed only after its transaction commits.
 * When the queue is full:
 * <ul>
 * <li>a result whose owner still holds it (an online player) is refused, and the owner
 *     submits it again later;</li>
 * <li>a result nobody else holds (a player who left, a shutdown hand-over) is let in by
 *     giving up an entry that can be recovered, and its owner is told;</li>
 * <li>only when every pending entry is unrecoverable is the oldest one lost; that is
 *     counted and logged, and the player is evaluated again on their next join.</li>
 * </ul>
 * Unconfirmed entries are also kept in a small file while the database is down and at
 * shutdown, so they survive a restart. On the next start they are fed back into the queue
 * as room appears and stay in the file until then.
 */
public final class WriteQueue {

    private static final long LOST_LOG_INTERVAL_MILLIS = 60_000L;
    /** How far from the oldest entry a recoverable one is looked for when making room. */
    private static final int EVICTION_SCAN = 256;

    private record Entry(PlayerRecord record, long sequence) {
    }

    private final Database database;
    private final ScoreRepository repository;
    private final Metrics metrics;
    private final Logger logger;
    private final long flushIntervalMillis;
    private final int batchSize;
    private final int maxPending;
    private final PendingSpool spool;
    private final long spoolIntervalMillis;

    private final Object lock = new Object();
    private final LinkedHashMap<UUID, Entry> pending = new LinkedHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ScheduledExecutorService executor;
    private volatile WriteListener listener;
    private volatile boolean closed;
    private long lastLostLogAt;
    // Writer thread only.
    private long lastSpoolAt;
    private boolean spoolWritten;
    /**
     * Records read from the spool file that have not found room in the queue yet. They stay
     * here, and in the file, until they are admitted; never more than {@code maxPending}.
     */
    private final LinkedHashMap<UUID, PlayerRecord> backlog = new LinkedHashMap<>();
    private volatile int backlogSize;

    public WriteQueue(Database database, ScoreRepository repository, Metrics metrics, Logger logger,
                      long flushIntervalMillis, int batchSize, int maxPending) {
        this(database, repository, metrics, logger, flushIntervalMillis, batchSize, maxPending, null, 30_000L);
    }

    /**
     * @param spool               file for unconfirmed entries, or null to keep them in memory only
     * @param spoolIntervalMillis minimum time between rewrites of that file during an outage
     */
    public WriteQueue(Database database, ScoreRepository repository, Metrics metrics, Logger logger,
                      long flushIntervalMillis, int batchSize, int maxPending,
                      PendingSpool spool, long spoolIntervalMillis) {
        this.database = database;
        this.repository = repository;
        this.metrics = metrics;
        this.logger = logger;
        this.flushIntervalMillis = Math.max(50L, flushIntervalMillis);
        this.batchSize = Math.max(1, batchSize);
        this.maxPending = Math.max(this.batchSize, maxPending);
        this.spool = spool;
        this.spoolIntervalMillis = Math.max(0L, spoolIntervalMillis);
        this.spoolWritten = spool != null && spool.exists();
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "GearScore-DB-Writer");
            thread.setDaemon(true);
            return thread;
        });
        // A flush waiting for its delay must not hold up the shutdown.
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    public void setListener(WriteListener listener) {
        this.listener = listener;
    }

    /** Hand-over of a result nobody else holds; see {@link #submit(PlayerRecord, long, boolean)}. */
    public void submit(PlayerRecord record) {
        submit(record, 0L, false);
    }

    /**
     * Safe from any thread; never blocks on the database or the disk.
     *
     * @param sequence    reported back through the listener when this result is confirmed or dropped
     * @param recoverable true when the caller keeps the result and can submit it again
     * @return false when the result was not accepted (queue full, or closed) and is still owed
     */
    public boolean submit(PlayerRecord record, long sequence, boolean recoverable) {
        if (closed) {
            return false;
        }
        synchronized (lock) {
            Entry current = pending.get(record.uuid());
            if (current != null) {
                // Consolidation never needs room, and never goes back in time.
                if (!older(record, sequence, current)) {
                    pending.put(record.uuid(), new Entry(record, sequence));
                }
                metrics.writesMerged.increment();
            } else {
                if (pending.size() >= maxPending) {
                    if (recoverable) {
                        metrics.writesRejected.increment();
                        return false;
                    }
                    evictOne();
                }
                pending.put(record.uuid(), new Entry(record, sequence));
                metrics.writesQueued.increment();
            }
        }
        schedule(flushIntervalMillis);
        return true;
    }

    private static boolean older(PlayerRecord record, long sequence, Entry current) {
        long difference = record.updatedAt() - current.record().updatedAt();
        return difference < 0 || (difference == 0 && sequence < current.sequence());
    }

    /** Whether a result for a player not yet in the queue would be accepted. */
    public boolean hasRoom() {
        synchronized (lock) {
            return pending.size() < maxPending;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /**
     * Frees one place. Prefers the oldest entry whose owner can submit it again; only when
     * there is none nearby is the oldest entry given up for good. Called with the lock held.
     */
    private void evictOne() {
        WriteListener owner = listener;
        Iterator<Map.Entry<UUID, Entry>> iterator = pending.entrySet().iterator();
        if (owner != null) {
            for (int scanned = 0; scanned < EVICTION_SCAN && iterator.hasNext(); scanned++) {
                Map.Entry<UUID, Entry> candidate = iterator.next();
                if (owner.recoverable(candidate.getKey())) {
                    iterator.remove();
                    metrics.writesDropped.increment();
                    owner.dropped(candidate.getKey(), candidate.getValue().sequence());
                    return;
                }
            }
            iterator = pending.entrySet().iterator();
        }
        iterator.next();
        iterator.remove();
        metrics.writesDropped.increment();
        metrics.writesLost.increment();
        long now = System.currentTimeMillis();
        if (now - lastLostLogAt >= LOST_LOG_INTERVAL_MILLIS) {
            lastLostLogAt = now;
            logger.warning("Pending write limit reached (" + maxPending + ") with no recoverable entry left; "
                    + "the oldest updates are being discarded. Those players are evaluated again on their "
                    + "next join.");
        }
    }

    private void schedule(long delayMillis) {
        if (closed || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(this::flushTask, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            scheduled.set(false);
        }
    }

    private void flushTask() {
        scheduled.set(false);
        if (!database.isAvailable()) {
            spoolIfDue();
            schedule(Math.max(50L, database.retryInMillis()));
            return;
        }
        boolean ok = flushBatch();
        if (ok) {
            // Room was just freed: let waiting spool records in before anything else.
            admitBacklog();
        }
        if (pendingCount() > 0) {
            if (!ok) {
                spoolIfDue();
            }
            schedule(ok ? 0L : Math.max(50L, database.retryInMillis()));
        } else if (ok) {
            clearSpool();
        }
    }

    /** Writes one batch. On failure the rows go back, unless a newer state arrived meanwhile. */
    private boolean flushBatch() {
        List<Entry> batch = takeBatch();
        if (batch.isEmpty()) {
            return true;
        }
        List<PlayerRecord> records = new ArrayList<>(batch.size());
        for (Entry entry : batch) {
            records.add(entry.record());
        }
        try {
            database.run(connection -> {
                repository.upsert(connection, records);
                return null;
            });
        } catch (SQLException | RuntimeException e) {
            restore(batch);
            return false;
        }
        metrics.writeBatches.increment();
        metrics.rowsWritten.add(batch.size());
        // Only now, with the transaction committed, is anything reported as persisted.
        WriteListener owner = listener;
        if (owner != null) {
            for (Entry entry : batch) {
                owner.confirmed(entry.record().uuid(), entry.sequence());
            }
        }
        return true;
    }

    private List<Entry> takeBatch() {
        synchronized (lock) {
            List<Entry> batch = new ArrayList<>(Math.min(batchSize, pending.size()));
            Iterator<Map.Entry<UUID, Entry>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < batchSize) {
                batch.add(iterator.next().getValue());
                iterator.remove();
            }
            return batch;
        }
    }

    /**
     * Puts a failed batch back in front of what arrived meanwhile, never replacing a newer
     * state of the same player, and re-applies the memory limit.
     */
    private void restore(List<Entry> batch) {
        synchronized (lock) {
            LinkedHashMap<UUID, Entry> newer = new LinkedHashMap<>(pending);
            pending.clear();
            for (Entry entry : batch) {
                if (!newer.containsKey(entry.record().uuid())) {
                    pending.put(entry.record().uuid(), entry);
                }
            }
            pending.putAll(newer);
            while (pending.size() > maxPending) {
                evictOne();
            }
        }
    }

    // ---- disk spool (writer thread only) -------------------------------------------------

    /** Everything still unconfirmed: spool records waiting for room, then the queue itself. */
    private List<PlayerRecord> unconfirmedRecords() {
        synchronized (lock) {
            List<PlayerRecord> records = new ArrayList<>(backlog.size() + pending.size());
            for (PlayerRecord record : backlog.values()) {
                if (!pending.containsKey(record.uuid())) {
                    records.add(record);
                }
            }
            for (Entry entry : pending.values()) {
                records.add(entry.record());
            }
            return records;
        }
    }

    /**
     * Moves spool records into the queue while it has room, oldest first. A record whose
     * player already has a pending entry is superseded by it.
     *
     * @return number of records admitted
     */
    private int admitBacklog() {
        if (backlog.isEmpty()) {
            return 0;
        }
        int admitted = 0;
        synchronized (lock) {
            Iterator<PlayerRecord> iterator = backlog.values().iterator();
            while (iterator.hasNext()) {
                PlayerRecord record = iterator.next();
                if (pending.containsKey(record.uuid())) {
                    iterator.remove();
                    continue;
                }
                if (pending.size() >= maxPending) {
                    break;
                }
                pending.put(record.uuid(), new Entry(record, 0L));
                iterator.remove();
                admitted++;
            }
        }
        backlogSize = backlog.size();
        if (admitted > 0) {
            metrics.spoolRecovered.add(admitted);
        }
        return admitted;
    }

    /** Spool records still waiting for room in the queue. */
    public int backlogCount() {
        return backlogSize;
    }

    private void spoolIfDue() {
        if (spool == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSpoolAt >= spoolIntervalMillis) {
            saveSpool();
        }
    }

    /** @return number of records written, or -1 when nothing was written */
    private int saveSpool() {
        if (spool == null) {
            return -1;
        }
        List<PlayerRecord> records = unconfirmedRecords();
        try {
            if (records.isEmpty()) {
                spool.delete();
                spoolWritten = false;
                return 0;
            }
            spool.save(records);
            spoolWritten = true;
            lastSpoolAt = System.currentTimeMillis();
            metrics.spoolSaves.increment();
            return records.size();
        } catch (IOException e) {
            metrics.spoolErrors.increment();
            return -1;
        }
    }

    private void clearSpool() {
        if (spool == null || !spoolWritten || !backlog.isEmpty()) {
            return;
        }
        try {
            spool.delete();
            spoolWritten = false;
        } catch (IOException e) {
            metrics.spoolErrors.increment();
        }
    }

    /**
     * Loads, on the writer thread, what a previous run left unconfirmed. Records enter the
     * queue as room appears; until then they wait in the backlog and remain in the file, so
     * a full queue or another restart does not lose them. A loaded record never replaces a
     * pending one.
     */
    public void recoverSpool() {
        if (spool == null || closed) {
            return;
        }
        try {
            executor.execute(() -> {
                List<PlayerRecord> records;
                try {
                    // Read whole: the file was written by this queue, so it holds at most the
                    // queue plus the backlog of the previous run, and anything beyond what
                    // fits must be counted below rather than left unread.
                    records = spool.load(Integer.MAX_VALUE);
                } catch (IOException e) {
                    metrics.spoolErrors.increment();
                    return;
                }
                if (records.isEmpty()) {
                    clearSpool();
                    return;
                }
                int overflow = 0;
                for (PlayerRecord record : records) {
                    PlayerRecord known = backlog.get(record.uuid());
                    if (known != null) {
                        if (record.updatedAt() > known.updatedAt()) {
                            backlog.put(record.uuid(), record);
                        }
                    } else {
                        backlog.put(record.uuid(), record);
                    }
                }
                int found = backlog.size();
                admitBacklog();
                // Capacity policy: what neither the queue nor the backlog can hold is given up,
                // newest kept, and reported instead of vanishing.
                Iterator<PlayerRecord> oldest = backlog.values().iterator();
                while (backlog.size() > maxPending && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                    overflow++;
                }
                if (overflow > 0) {
                    metrics.writesLost.add(overflow);
                }
                backlogSize = backlog.size();
                logger.info("Found " + found + " pending score update(s) left by the previous run"
                        + (backlogSize > 0 ? "; " + backlogSize + " waiting for room in the write queue." : "."));
                if (overflow > 0) {
                    logger.warning(overflow + " pending score update(s) from the previous run exceeded the "
                            + "limit and were discarded. Those players are evaluated again on their next join.");
                }
                schedule(0L);
            });
        } catch (RuntimeException ignored) {
            // executor already stopped
        }
    }

    // ---- shutdown ------------------------------------------------------------------------

    /** What {@link #shutdown(long)} achieved. */
    public record ShutdownResult(int unconfirmed, int savedToDisk) {
    }

    /**
     * Stops accepting writes and tries to persist what is pending, waiting at most
     * {@code timeoutMillis}. What is pending is first written to the spool file, so it
     * survives even if the database does not answer in time.
     */
    public ShutdownResult shutdown(long timeoutMillis) {
        closed = true;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        int[] saved = {0};
        try {
            Future<?> lastFlush = executor.submit(() -> {
                saved[0] = Math.max(0, saveSpool());
                admitBacklog();
                while (pendingCount() > 0 && System.currentTimeMillis() < deadline && database.isAvailable()) {
                    if (!flushBatch()) {
                        break;
                    }
                    admitBacklog();
                }
                // Keep on disk exactly what is still unconfirmed.
                saved[0] = Math.max(0, saveSpool());
            });
            lastFlush.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // whatever is still pending is reported by the caller
        }
        executor.shutdownNow();
        int unconfirmed = pendingCount() + backlogSize;
        return new ShutdownResult(unconfirmed, unconfirmed == 0 ? 0 : saved[0]);
    }
}
