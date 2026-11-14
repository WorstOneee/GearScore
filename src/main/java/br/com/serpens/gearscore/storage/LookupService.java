package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.model.PlayerRecord;

import java.sql.SQLException;
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

    private static final class Position {
        volatile int value = -1;
        volatile int score = Integer.MIN_VALUE;
        volatile long loadedAt;
        volatile boolean loading;
    }

    private final Database database;
    private final ScoreRepository repository;
    private final Metrics metrics;
    private final ThreadPoolExecutor executor;
    private final long lookupCacheMillis;
    private final int topLimit;
    private final long topCacheMillis;
    private final long positionCacheMillis;

    private final LinkedHashMap<String, CachedLookup> lookupCache;
    private final ConcurrentHashMap<String, CompletableFuture<Optional<PlayerRecord>>> lookupsInFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Position> positions = new ConcurrentHashMap<>();

    private final Object topLock = new Object();
    private volatile TopSnapshot top;
    private CompletableFuture<TopSnapshot> topInFlight;

    public LookupService(Database database, ScoreRepository repository, Metrics metrics,
                         int threads, int maxQueued, long lookupCacheMillis, int lookupCacheSize,
                         int topLimit, long topCacheMillis, long positionCacheMillis) {
        this.database = database;
        this.repository = repository;
        this.metrics = metrics;
        this.lookupCacheMillis = lookupCacheMillis;
        this.topLimit = Math.max(1, topLimit);
        this.topCacheMillis = topCacheMillis;
        this.positionCacheMillis = positionCacheMillis;

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

    /**
     * Ranking position without blocking: answers from memory and, when the value is missing
     * or expired, starts one background refresh for that player.
     *
     * @return 1-based position, or -1 while unknown
     */
    public int position(UUID uuid, int score) {
        long now = System.currentTimeMillis();
        TopSnapshot snapshot = top;
        if (snapshot != null && now - snapshot.loadedAt() < topCacheMillis) {
            Integer listed = snapshot.positions().get(uuid);
            if (listed != null) {
                return listed;
            }
        }
        Position position = positions.computeIfAbsent(uuid, key -> new Position());
        boolean expired = position.score != score || now - position.loadedAt >= positionCacheMillis;
        if (expired && !position.loading) {
            position.loading = true;
            position.score = score;
            query(connection -> repository.position(connection, score)).whenComplete((value, error) -> {
                if (error == null) {
                    position.value = value;
                }
                // A failed attempt also waits for the cache window before trying again.
                position.loadedAt = System.currentTimeMillis();
                position.loading = false;
            });
        }
        return position.value;
    }

    public void forget(UUID uuid) {
        positions.remove(uuid);
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
