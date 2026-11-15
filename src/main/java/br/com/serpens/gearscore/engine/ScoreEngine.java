package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.model.WriteListener;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import br.com.serpens.gearscore.score.ScoreCalculator;
import br.com.serpens.gearscore.score.ScoreResult;
import br.com.serpens.gearscore.score.ScoreResult.EnchantScore;
import br.com.serpens.gearscore.score.ScoreResult.EnchantStatus;
import br.com.serpens.gearscore.score.ScoreResult.ItemScore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Evaluation pipeline, free of server classes so it can be tested and benchmarked alone:
 * debounce, snapshot signature check, calculation, result cache and delivery of results to
 * the write queue.
 *
 * A result goes through three stages. It is <em>calculated</em> when an evaluation produces
 * it, <em>queued</em> once the write queue accepts it, and <em>confirmed</em> only when the
 * queue reports that its transaction committed. A result that was refused or dropped by the
 * queue stays owed and is submitted again by {@link #recover(int)} from the copy kept here,
 * without reading the inventory again and without waiting for a new event.
 *
 * Every mutating method must be called from the server thread. {@link #cached(UUID)} and
 * the {@link WriteListener} methods are safe from any thread.
 */
public final class ScoreEngine implements WriteListener {

    /** Captures the relevant equipment of a tracked player on the server thread. */
    public interface Source {
        /** @return null when the player must not be evaluated right now */
        GearSnapshot capture(UUID id);
    }

    /** Where results that still have to reach the database are handed over. Never blocks. */
    public interface Sink {
        /**
         * @param sequence    identifies this result in later confirmations and drops
         * @param recoverable true while the engine keeps the result and can submit it again;
         *                    false for a last hand-over (player leaving, shutdown)
         * @return false when the result was not accepted and is still owed
         */
        boolean submit(PlayerRecord record, long sequence, boolean recoverable);

        /** Whether a new player's result would be accepted right now. */
        boolean hasRoom();
    }

    /** Written by the queue's threads, read by the server thread. */
    private static final class Tracker {
        volatile long confirmed;
        volatile long dropped;
        final AtomicBoolean awaitingRecovery = new AtomicBoolean();
    }

    private static final class State {
        final Tracker tracker = new Tracker();
        long session;
        String name;
        GearSnapshot last;
        int generation = -1;
        /** Latest known result: read from the database at join, or calculated here. */
        PlayerRecord latest;
        /** Sequence of {@link #latest}; 0 when it came from the database. */
        long sequence;
        /** Sequence the queue last accepted for this player. */
        long queued;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final ConcurrentHashMap<UUID, Tracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CachedScore> cache = new ConcurrentHashMap<>();
    // At most one entry per tracked player, guarded by Tracker.awaitingRecovery.
    private final ConcurrentLinkedQueue<UUID> owed = new ConcurrentLinkedQueue<>();
    private final EvaluationQueue queue = new EvaluationQueue();
    private final Source source;
    private final Sink sink;
    private final Metrics metrics;
    private final String serverName;
    private final LongSupplier clock;

    private ScoreCalculator calculator;
    private int generation;
    private long nextSession;
    private long nextSequence;

    public ScoreEngine(RuleSet rules, String serverName, Source source, Sink sink, Metrics metrics, LongSupplier clock) {
        this.calculator = new ScoreCalculator(rules);
        this.serverName = serverName;
        this.source = source;
        this.sink = sink;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RuleSet rules() {
        return calculator.rules();
    }

    /**
     * Starts tracking a player.
     *
     * @return the session token; asynchronous results must present it to be accepted
     */
    public long track(UUID id, String name) {
        State state = states.get(id);
        if (state == null) {
            state = new State();
            state.session = ++nextSession;
            states.put(id, state);
            trackers.put(id, state.tracker);
        }
        state.name = name;
        return state.session;
    }

    public boolean isTracked(UUID id) {
        return states.containsKey(id);
    }

    /**
     * Stops tracking a player. A result that is still owed is handed to the queue for good,
     * since after this the engine no longer holds it.
     */
    public void forget(UUID id) {
        State state = states.remove(id);
        trackers.remove(id);
        queue.remove(id);
        cache.remove(id);
        if (state == null) {
            return;
        }
        if (state.tracker.awaitingRecovery.get()) {
            // Leaves the recovery list with the player, so it only ever names tracked players.
            owed.remove(id);
        }
        if (owes(state)) {
            sink.submit(state.latest, state.sequence, false);
        }
    }

    public CachedScore cached(UUID id) {
        return cache.get(id);
    }

    public int trackedCount() {
        return states.size();
    }

    public int pendingEvaluations() {
        return queue.size();
    }

    public boolean hasPending() {
        return !queue.isEmpty();
    }

    public boolean isPending(UUID id) {
        return queue.isPending(id);
    }

    /**
     * Registers what the database holds for a player so an identical result is not written again.
     * Until the first evaluation it also backs the cache. A result that belongs to an earlier
     * session of the player (late answer after a quit or reconnect) is ignored.
     */
    public void seedPersisted(UUID id, long session, PlayerRecord record) {
        State state = states.get(id);
        if (state == null || record == null || state.session != session) {
            return;
        }
        if (state.latest == null) {
            state.latest = record;
            state.sequence = 0L;
        }
        cache.putIfAbsent(id, new CachedScore(record, null));
    }

    /** @return true when a new evaluation was queued, false when merged into a pending one */
    public boolean request(UUID id, long tick, int delayTicks) {
        if (!states.containsKey(id)) {
            return false;
        }
        if (queue.request(id, tick, delayTicks)) {
            metrics.requests.increment();
            return true;
        }
        metrics.coalesced.increment();
        return false;
    }

    /** Processes due evaluations, at most {@code max} per call. */
    public int tick(long tick, int max) {
        return queue.drain(tick, max, this::evaluate);
    }

    /** Evaluates immediately, bypassing the debounce. */
    public void evaluateNow(UUID id) {
        queue.remove(id);
        evaluate(id);
    }

    /** Swaps the rules. Cached results stay visible until each player is evaluated again. */
    public void setRules(RuleSet rules) {
        this.calculator = new ScoreCalculator(rules);
        this.generation++;
    }

    /** Queues every tracked player; the per-tick limit spreads the work. */
    public void requestAll(long tick, int delayTicks) {
        List<UUID> ids = new ArrayList<>(states.keySet());
        for (UUID id : ids) {
            request(id, tick, delayTicks);
        }
    }

    private void evaluate(UUID id) {
        State state = states.get(id);
        if (state == null) {
            return;
        }
        long start = System.nanoTime();
        GearSnapshot snapshot = source.capture(id);
        if (snapshot == null) {
            return;
        }
        metrics.captures.increment();
        if (state.generation == generation && snapshot.equals(state.last)) {
            metrics.unchanged.increment();
            metrics.recordEvaluationTime(System.nanoTime() - start);
            return;
        }

        ScoreResult result = calculator.calculate(snapshot);
        state.last = snapshot;
        state.generation = generation;

        String tier = result.tier().id();
        PlayerRecord record = state.latest;
        if (record == null || !record.sameResult(state.name, result.total(), result.armorScore(),
                result.weaponScore(), tier, serverName, result.rulesVersion())) {
            // Never older than what it replaces, so the database can tell them apart.
            long now = record == null ? clock.getAsLong() : Math.max(clock.getAsLong(), record.updatedAt() + 1);
            record = new PlayerRecord(id, state.name, result.total(), result.armorScore(), result.weaponScore(),
                    tier, serverName, result.rulesVersion(), now);
            state.latest = record;
            state.sequence = ++nextSequence;
            state.queued = 0L;
        }
        cache.put(id, new CachedScore(record, result));
        if (owes(state)) {
            send(id, state);
        }

        metrics.evaluations.increment();
        metrics.recordEvaluationTime(System.nanoTime() - start);
        reportUnknown(result);
    }

    // ---- delivery to the write queue -----------------------------------------------------

    /**
     * True while the latest result was calculated here, is not confirmed by the database and
     * is not sitting in the queue either: never accepted, or accepted and then dropped.
     */
    private static boolean owes(State state) {
        if (state.latest == null || state.sequence == 0L) {
            return false;
        }
        Tracker tracker = state.tracker;
        if (tracker.confirmed == state.sequence) {
            return false;
        }
        return state.queued != state.sequence || tracker.dropped == state.sequence;
    }

    private boolean send(UUID id, State state) {
        // Cleared before submitting: a drop reported from here on refers to this submission.
        state.tracker.dropped = 0L;
        if (sink.submit(state.latest, state.sequence, true)) {
            state.queued = state.sequence;
            return true;
        }
        state.queued = 0L;
        awaitRecovery(id, state.tracker);
        return false;
    }

    private void awaitRecovery(UUID id, Tracker tracker) {
        if (tracker.awaitingRecovery.compareAndSet(false, true)) {
            owed.add(id);
        }
    }

    /**
     * Submits again results the queue refused or dropped, oldest request first, at most
     * {@code max} per call and only while the queue has room. Uses the results already
     * calculated: no inventory is read and no player has to do anything.
     *
     * @return number of results accepted by the queue
     */
    public int recover(int max) {
        int sent = 0;
        int examined = 0;
        int limit = owed.size();
        while (sent < max && examined++ < limit && sink.hasRoom()) {
            UUID id = owed.poll();
            if (id == null) {
                break;
            }
            State state = states.get(id);
            if (state == null) {
                continue;
            }
            state.tracker.awaitingRecovery.set(false);
            if (!owes(state)) {
                continue;
            }
            if (!send(id, state)) {
                break;
            }
            sent++;
            metrics.writesRecovered.increment();
        }
        return sent;
    }

    /** Whether some result is waiting to be submitted again. */
    public boolean hasOwed() {
        return !owed.isEmpty();
    }

    /** Results waiting to be submitted again. */
    public int owedCount() {
        return owed.size();
    }

    /**
     * Last hand-over before shutdown: every result still owed goes to the queue for good.
     *
     * @return number of results handed over
     */
    public int handOver() {
        int handed = 0;
        for (State state : states.values()) {
            if (owes(state)) {
                sink.submit(state.latest, state.sequence, false);
                state.queued = state.sequence;
                state.tracker.dropped = 0L;
                handed++;
            }
        }
        owed.clear();
        return handed;
    }

    @Override
    public void confirmed(UUID id, long sequence) {
        Tracker tracker = trackers.get(id);
        // Only ever raised, so an old confirmation cannot hide a newer result; and a
        // tracker of a new session never has a latest result with an old sequence.
        if (tracker != null && sequence > tracker.confirmed) {
            tracker.confirmed = sequence;
        }
    }

    @Override
    public void dropped(UUID id, long sequence) {
        Tracker tracker = trackers.get(id);
        if (tracker != null) {
            tracker.dropped = sequence;
            awaitRecovery(id, tracker);
        }
    }

    @Override
    public boolean recoverable(UUID id) {
        return trackers.containsKey(id);
    }

    // ---- diagnostics ---------------------------------------------------------------------

    private void reportUnknown(ScoreResult result) {
        for (int i = 0; i < ScoreResult.SET_SIZE; i++) {
            reportUnknown(result.piece(i));
        }
    }

    private void reportUnknown(ItemScore item) {
        if (item == null) {
            return;
        }
        for (EnchantScore enchant : item.enchants()) {
            if (enchant.status() == EnchantStatus.UNKNOWN) {
                metrics.unknownEnchantment(item.item().def().type().id() + " -> " + enchant.key());
            }
        }
    }
}
