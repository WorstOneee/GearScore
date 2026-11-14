package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;

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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Pending writes, consolidated by player: however many times a score changes before a
 * flush, only the latest state is written. One dedicated thread performs every write, in
 * batches. Nothing runs while the queue is empty.
 */
public final class WriteQueue {

    private static final long DROP_LOG_INTERVAL_MILLIS = 60_000L;

    private final Database database;
    private final ScoreRepository repository;
    private final Metrics metrics;
    private final Logger logger;
    private final long flushIntervalMillis;
    private final int batchSize;
    private final int maxPending;

    private final Object lock = new Object();
    private final LinkedHashMap<UUID, PlayerRecord> pending = new LinkedHashMap<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;
    private long lastDropLogAt;

    public WriteQueue(Database database, ScoreRepository repository, Metrics metrics, Logger logger,
                      long flushIntervalMillis, int batchSize, int maxPending) {
        this.database = database;
        this.repository = repository;
        this.metrics = metrics;
        this.logger = logger;
        this.flushIntervalMillis = Math.max(50L, flushIntervalMillis);
        this.batchSize = Math.max(1, batchSize);
        this.maxPending = Math.max(this.batchSize, maxPending);
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

    /** Safe from any thread; never blocks on the database. */
    public void submit(PlayerRecord record) {
        if (closed) {
            return;
        }
        synchronized (lock) {
            if (pending.put(record.uuid(), record) != null) {
                metrics.writesMerged.increment();
            } else {
                metrics.writesQueued.increment();
                if (pending.size() > maxPending) {
                    dropOldest();
                }
            }
        }
        schedule(flushIntervalMillis);
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    private void dropOldest() {
        Iterator<UUID> oldest = pending.keySet().iterator();
        oldest.next();
        oldest.remove();
        metrics.writesDropped.increment();
        long now = System.currentTimeMillis();
        if (now - lastDropLogAt >= DROP_LOG_INTERVAL_MILLIS) {
            lastDropLogAt = now;
            logger.warning("Pending write limit reached (" + maxPending
                    + "); the oldest updates are being discarded until the database recovers.");
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
            schedule(Math.max(50L, database.retryInMillis()));
            return;
        }
        boolean ok = flushBatch();
        if (pendingCount() > 0) {
            schedule(ok ? 0L : Math.max(50L, database.retryInMillis()));
        }
    }

    /** Writes one batch. On failure the rows go back, unless a newer state arrived meanwhile. */
    private boolean flushBatch() {
        List<PlayerRecord> batch = takeBatch();
        if (batch.isEmpty()) {
            return true;
        }
        try {
            database.run(connection -> {
                repository.upsert(connection, batch);
                return null;
            });
            metrics.writeBatches.increment();
            metrics.rowsWritten.add(batch.size());
            return true;
        } catch (SQLException | RuntimeException e) {
            restore(batch);
            return false;
        }
    }

    private List<PlayerRecord> takeBatch() {
        synchronized (lock) {
            List<PlayerRecord> batch = new ArrayList<>(Math.min(batchSize, pending.size()));
            Iterator<Map.Entry<UUID, PlayerRecord>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext() && batch.size() < batchSize) {
                batch.add(iterator.next().getValue());
                iterator.remove();
            }
            return batch;
        }
    }

    private void restore(List<PlayerRecord> batch) {
        synchronized (lock) {
            for (PlayerRecord record : batch) {
                pending.putIfAbsent(record.uuid(), record);
            }
        }
    }

    /**
     * Stops accepting writes and tries to persist what is pending, waiting at most
     * {@code timeoutMillis}.
     *
     * @return number of updates that could not be written
     */
    public int shutdown(long timeoutMillis) {
        closed = true;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        try {
            Future<?> lastFlush = executor.submit(() -> {
                while (pendingCount() > 0 && System.currentTimeMillis() < deadline) {
                    if (!flushBatch()) {
                        break;
                    }
                }
            });
            lastFlush.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // whatever is still pending is reported by the caller
        }
        executor.shutdownNow();
        return pendingCount();
    }
}
