package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read side of the database: on-demand lookups with short-lived caches, a shared ranking
 * snapshot and ranking positions. Runs on a small bounded pool; when the pool is saturated
 * or the database is down, requests fail fast instead of piling up.
 */
public final class LookupService {

    /** The read pool queue is full. */
    public static final class BusyException extends SQLException {
        public BusyException() {
            super("too many pending database lookups");
        }
    }

    /** Ranking rows shared by everyone who opens the ranking while it is fresh. */
    public record TopSnapshot(List<PlayerRecord> rows, long loadedAt, Map<UUID, Integer> positions) {
    }

    private record CachedLookup(PlayerRecord record, long loadedAt) {
    }

    /** Value kept for a tracked player whose position is not known yet. */
    private static final Integer UNKNOWN = 0;
    /** Players per position refresh; larger populations are covered over successive cycles. */
    private static final int POSITION_BATCH = 500;

    private final Database database;
    private final ScoreRepository repository;
    private final Metrics metrics;
    private final ThreadPoolExecutor executor;
    private final long lookupCacheMillis;
    private final int topLimit;
    private final long topCacheMillis;
    private final long positionRefreshMillis;

    private final LinkedHashMap<String, CachedLookup> lookupCache;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<PlayerRecord>>> lookupsInFlight =
            new ConcurrentHashMap<>();
    // Holds an entry only for players online on this server.
    private final ConcurrentHashMap<UUID, Integer> positions = new ConcurrentHashMap<>();
    private final AtomicBoolean positionsLoading = new AtomicBoolean();
    private volatile boolean positionRequested;
    private long lastPositionRefresh = Long.MIN_VALUE / 2;
    private int positionCursor;

    private final Object topLock = new Object();
    private volatile TopSnapshot top;
    private CompletableFuture<TopSnapshot> topInFlight;

    public LookupService(Database database, ScoreRepository repository, Metrics metrics,
                         int threads, int maxQueued, long lookupCacheMillis, int lookupCacheSize,
                         int topLimit, long topCacheMillis, long positionRefreshMillis) {
        this.database = database;
        this.repository = repository;
        this.metrics = metrics;
        this.lookupCacheMillis = lookupCacheMillis;
        this.topLimit = Math.max(1, topLimit);
        this.topCacheMillis = topCacheMillis;
        this.positionRefreshMillis = positionRefreshMillis;

        int size = Math.max(1, threads);
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(size, size, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, maxQueued)), runnable -> {
            Thread thread = new Thread(runnable, "GearScore-DB-Reader-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.executor.allowCoreThreadTimeOut(true);

        int capacity = Math.max(1, lookupCacheSize);
        this.lookupCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedLookup> eldest) {
                return size() > capacity;
            }
        };
    }

    private <T> CompletableFuture<T> query(Database.SqlWork<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!database.isAvailable()) {
            future.completeExceptionally(new Database.UnavailableException(database.retryInMillis()));
            return future;
        }
        try {
            executor.execute(() -> {
                try {
                    metrics.readQueries.increment();
                    future.complete(database.run(work));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.readsRejected.increment();
            future.completeExceptionally(new BusyException());
        }
        return future;
    }

    /** One primary-key read, used when a player joins. Completes with null when there is no row. */
    public CompletableFuture<PlayerRecord> loadByUuid(UUID uuid) {
        return query(connection -> repository.findByUuid(connection, uuid));
    }

    /** One primary-key query for a group of players; those without a row are absent from the map. */
    public CompletableFuture<Map<UUID, PlayerRecord>> loadByUuids(List<UUID> uuids) {
        List<UUID> ids = List.copyOf(uuids);
        return query(connection -> repository.findByUuids(connection, ids));
    }

    /** Lookup by last known name for players that are not online on this server. */
    public CompletableFuture<Optional<PlayerRecord>> lookupByName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (lookupCache) {
            CachedLookup cached = lookupCache.get(key);
            if (cached != null && now - cached.loadedAt() < lookupCacheMillis) {
                metrics.cacheHits.increment();
                return CompletableFuture.completedFuture(Optional.ofNullable(cached.record()));
            }
        }
        metrics.cacheMisses.increment();
        CompletableFuture<Optional<PlayerRecord>> running = lookupsInFlight.get(key);
        if (running != null) {
            return running;
        }
        CompletableFuture<Optional<PlayerRecord>> future =
                query(connection -> repository.findByName(connection, name)).thenApply(record -> {
                    synchronized (lookupCache) {
                        lookupCache.put(key, new CachedLookup(record, System.currentTimeMillis()));
                    }
                    return Optional.ofNullable(record);
                });
        lookupsInFlight.put(key, future);
        future.whenComplete((result, error) -> lookupsInFlight.remove(key, future));
        return future;
    }

    /**
     * Ranking snapshot. A fresh one is returned without touching the database; concurrent
     * callers share a single refresh; if the refresh fails the previous snapshot is served.
     */
    public CompletableFuture<TopSnapshot> top() {
        TopSnapshot current = top;
        if (current != null && System.currentTimeMillis() - current.loadedAt() < topCacheMillis) {
            metrics.cacheHits.increment();
            return CompletableFuture.completedFuture(current);
        }
        metrics.cacheMisses.increment();
        synchronized (topLock) {
            if (topInFlight != null) {
                return topInFlight;
            }
            CompletableFuture<TopSnapshot> future = query(connection -> repository.top(connection, topLimit))
                    .thenApply(rows -> {
                        Map<UUID, Integer> index = new HashMap<>(rows.size() * 2);
                        for (int i = 0; i < rows.size(); i++) {
                            index.put(rows.get(i).uuid(), i + 1);
                        }
                        TopSnapshot fresh = new TopSnapshot(List.copyOf(rows), System.currentTimeMillis(), index);
                        top = fresh;
                        // Same order as the list just read, so online players agree with it at once.
                        index.forEach(positions::replace);
                        return fresh;
                    })
                    .exceptionallyCompose(error -> current != null
                            ? CompletableFuture.completedFuture(current)
                            : CompletableFuture.failedFuture(error));
            topInFlight = future;
            future.whenComplete((result, error) -> {
                synchronized (topLock) {
                    if (topInFlight == future) {
                        topInFlight = null;
                    }
                }
            });
            return future;
        }
    }

    /** Starts keeping a ranking position for a player who is online on this server. */
    public void trackPosition(UUID uuid) {
        positions.putIfAbsent(uuid, UNKNOWN);
    }

    public void forget(UUID uuid) {
        positions.remove(uuid);
    }

    /**
     * Ranking position from memory. Never queries, schedules or waits: it only notes that
     * positions are in use, which lets {@link #refreshPositions(long)} run on its own clock.
     *
     * @return 1-based position, or -1 while unknown
     */
    public int position(UUID uuid) {
        positionRequested = true;
        Integer position = positions.get(uuid);
        return position == null || position.intValue() == 0 ? -1 : position;
    }

    /**
     * Refreshes the positions of the tracked players with one batched read. Meant to be
     * called periodically from the server thread; it does nothing unless positions were
     * requested since the last refresh, the interval has elapsed, the database is reachable
     * and no refresh is running. How often placeholders are read has no effect on how often
     * this queries.
     *
     * @return true when a refresh was started
     */
    public boolean refreshPositions(long now) {
        if (!positionRequested || positions.isEmpty() || now - lastPositionRefresh < positionRefreshMillis
                || !database.isAvailable() || !positionsLoading.compareAndSet(false, true)) {
            return false;
        }
        lastPositionRefresh = now;
        positionRequested = false;
        List<UUID> ids = new ArrayList<>(positions.keySet());
        if (ids.size() > POSITION_BATCH) {
            int start = positionCursor % ids.size();
            List<UUID> window = new ArrayList<>(POSITION_BATCH);
            for (int i = 0; i < POSITION_BATCH; i++) {
                window.add(ids.get((start + i) % ids.size()));
            }
            positionCursor = (start + POSITION_BATCH) % ids.size();
            ids = window;
        }
        List<UUID> batch = ids;
        query(connection -> repository.positions(connection, batch)).whenComplete((found, error) -> {
            if (error == null) {
                for (UUID id : batch) {
                    Integer position = found.get(id);
                    // replace() only touches players still tracked, so a late answer cannot
                    // bring back someone who already left.
                    positions.replace(id, position == null ? UNKNOWN : position);
                }
            }
            positionsLoading.set(false);
        });
        return true;
    }

    /** Players whose position is being kept. */
    int trackedPositions() {
        return positions.size();
    }

    public int queuedReads() {
        return executor.getQueue().size();
    }

    public long topAgeMillis() {
        TopSnapshot snapshot = top;
        return snapshot == null ? -1L : System.currentTimeMillis() - snapshot.loadedAt();
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
