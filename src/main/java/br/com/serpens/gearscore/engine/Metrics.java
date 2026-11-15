package br.com.serpens.gearscore.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters behind the admin diagnostics. Cheap enough to stay always on.
 */
public final class Metrics {

    private static final int MAX_UNKNOWN_KEYS = 50;

    /** Equipment events dropped by the listener because nothing the rules read had changed. */
    public final LongAdder filteredEvents = new LongAdder();
    /** Evaluation requests accepted into the queue. */
    public final LongAdder requests = new LongAdder();
    /** Requests merged into an evaluation that was already pending. */
    public final LongAdder coalesced = new LongAdder();
    /** Snapshots captured. */
    public final LongAdder captures = new LongAdder();
    /** Captures discarded because the signature matched the last evaluated one. */
    public final LongAdder unchanged = new LongAdder();
    /** Scores actually recalculated. */
    public final LongAdder evaluations = new LongAdder();
    public final LongAdder evaluationNanos = new LongAdder();
    public final AtomicLong maxEvaluationNanos = new AtomicLong();

    public final LongAdder cacheHits = new LongAdder();
    public final LongAdder cacheMisses = new LongAdder();

    public final LongAdder writesQueued = new LongAdder();
    public final LongAdder writesMerged = new LongAdder();
    public final LongAdder writesDropped = new LongAdder();
    public final LongAdder rowsWritten = new LongAdder();
    public final LongAdder writeBatches = new LongAdder();
    public final LongAdder readQueries = new LongAdder();
    public final LongAdder readsRejected = new LongAdder();
    public final LongAdder databaseFailures = new LongAdder();

    /** Batched reads sent by a query server to refresh the official scores of its players. */
    public final LongAdder remoteRefreshes = new LongAdder();
    /** Official scores stored or replaced in a query server's cache. */
    public final LongAdder remoteUpdates = new LongAdder();
    /** Answers ignored because the session had ended or newer data was already cached. */
    public final LongAdder remoteDiscarded = new LongAdder();

    private final Set<String> unknownEnchantments = ConcurrentHashMap.newKeySet();

    public void recordEvaluationTime(long nanos) {
        evaluationNanos.add(nanos);
        long current = maxEvaluationNanos.get();
        while (nanos > current && !maxEvaluationNanos.compareAndSet(current, nanos)) {
            current = maxEvaluationNanos.get();
        }
    }

    public void unknownEnchantment(String key) {
        if (unknownEnchantments.size() < MAX_UNKNOWN_KEYS) {
            unknownEnchantments.add(key);
        }
    }

    public Set<String> unknownEnchantments() {
        return unknownEnchantments;
    }

    public void clearUnknownEnchantments() {
        unknownEnchantments.clear();
    }
}
