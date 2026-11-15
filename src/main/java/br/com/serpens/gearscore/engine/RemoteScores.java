package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Official scores of the players online on a server that is not an official source.
 *
 * Such a server never reads inventories. It asks the database for a player's row when they
 * join, once more shortly after (the origin server may still be flushing the write), and
 * then on a slow shared interval, so a score read too early, missing, or lost to a failed
 * query is corrected later. Refreshes are grouped into primary-key batches and capped per
 * cycle.
 *
 * {@link #open}, {@link #close} and {@link #poll} belong to the server thread; results are
 * applied from the database thread; {@link #get} is safe anywhere.
 */
public final class RemoteScores {

    /** Batched read of official rows. */
    public interface Loader {
        /** False while the database is in its retry window; nothing is asked meanwhile. */
        boolean available();

        /** Players without a row are absent from the resulting map. */
        CompletableFuture<Map<UUID, PlayerRecord>> load(List<UUID> ids);
    }

    private static final class Session {
        final long token;
        volatile CachedScore score;
        int polls;

        Session(long token) {
            this.token = token;
        }
    }

    private record Due(long at, long order, UUID id, long token) {
    }

    private record Ticket(UUID id, long token) {
    }

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final PriorityQueue<Due> schedule = new PriorityQueue<>((a, b) -> {
        int byTime = Long.compare(a.at(), b.at());
        return byTime != 0 ? byTime : Long.compare(a.order(), b.order());
    });
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Loader loader;
    private final Metrics metrics;
    private final long recheckMillis;
    private final long intervalMillis;
    private final int batchSize;
    private final int maxBatches;
    private long nextToken;
    private long nextOrder;

    public RemoteScores(Loader loader, Metrics metrics, long recheckMillis, long intervalMillis,
                        int batchSize, int maxBatches) {
        this.loader = loader;
        this.metrics = metrics;
        this.recheckMillis = Math.max(1L, recheckMillis);
        this.intervalMillis = Math.max(this.recheckMillis, intervalMillis);
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /** Starts a session for a player who joined; their first read is due immediately. */
    public void open(UUID id, long now) {
        Session session = new Session(++nextToken);
        sessions.put(id, session);
        schedule.add(new Due(now, ++nextOrder, id, session.token));
    }

    /** Ends the session. Answers still on their way for it will be discarded. */
    public void close(UUID id) {
        sessions.remove(id);
        if (sessions.isEmpty()) {
            schedule.clear();
        }
    }

    public CachedScore get(UUID id) {
        Session session = sessions.get(id);
        return session == null ? null : session.score;
    }

    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    public int size() {
        return sessions.size();
    }

    /**
     * Sends the reads that are due, at most {@code maxBatches} batches minus those still
     * running. Players left over stay due and go out on a following call.
     *
     * @return number of batches started
     */
    public int poll(long now) {
        if (schedule.isEmpty() || !loader.available()) {
            return 0;
        }
        int started = 0;
        while (inFlight.get() < maxBatches) {
            List<Ticket> batch = takeDue(now);
            if (batch.isEmpty()) {
                break;
            }
            List<UUID> ids = new ArrayList<>(batch.size());
            for (Ticket ticket : batch) {
                ids.add(ticket.id());
            }
            inFlight.incrementAndGet();
            started++;
            metrics.remoteRefreshes.increment();
            loader.load(ids).whenComplete((found, error) -> {
                inFlight.decrementAndGet();
                if (error != null) {
                    return;
                }
                for (Ticket ticket : batch) {
                    apply(ticket.id(), ticket.token(), found.get(ticket.id()));
                }
            });
        }
        return started;
    }

    private List<Ticket> takeDue(long now) {
        List<Ticket> batch = new ArrayList<>();
        while (batch.size() < batchSize) {
            Due head = schedule.peek();
            if (head == null || head.at() > now) {
                break;
            }
            schedule.poll();
            Session session = sessions.get(head.id());
            if (session == null || session.token != head.token()) {
                continue;
            }
            // The next read is scheduled now, so a failed or empty answer needs no bookkeeping.
            // Periodic reads land on a shared time grid: players who joined at different
            // moments come due together and travel in the same batch.
            long next = session.polls++ == 0
                    ? now + recheckMillis
                    : (now / intervalMillis + 1) * intervalMillis;
            schedule.add(new Due(next, ++nextOrder, head.id(), head.token()));
            batch.add(new Ticket(head.id(), head.token()));
        }
        return batch;
    }

    /**
     * Stores a row read for a session. Rejected when the session ended or was replaced by a
     * reconnect, and when the cache already holds a result at least as recent.
     *
     * @return true when the cache changed
     */
    public boolean apply(UUID id, long token, PlayerRecord record) {
        if (record == null) {
            return false;
        }
        Session session = sessions.get(id);
        if (session == null || session.token != token) {
            metrics.remoteDiscarded.increment();
            return false;
        }
        synchronized (session) {
            CachedScore current = session.score;
            if (current != null && current.record().updatedAt() >= record.updatedAt()) {
                if (current.record().updatedAt() > record.updatedAt()) {
                    metrics.remoteDiscarded.increment();
                }
                return false;
            }
            session.score = new CachedScore(record, null);
        }
        metrics.remoteUpdates.increment();
        return true;
    }

    /** Token of the current session, or -1. */
    public long token(UUID id) {
        Session session = sessions.get(id);
        return session == null ? -1L : session.token;
    }
}
