package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
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
import java.util.function.LongSupplier;

/**
 * Evaluation pipeline, free of server classes so it can be tested and benchmarked alone:
 * debounce, snapshot signature check, calculation, result cache and change notification.
 * Every mutating method must be called from the server thread; {@link #cached(UUID)} is
 * safe from any thread.
 */
public final class ScoreEngine {

    /** Captures the relevant equipment of a tracked player on the server thread. */
    public interface Source {
        /** @return null when the player must not be evaluated right now */
        GearSnapshot capture(UUID id);
    }

    /** Receives results that differ from the last persisted state. */
    public interface Sink {
        void changed(PlayerRecord record);
    }

    private static final class State {
        String name;
        GearSnapshot last;
        int generation = -1;
        PlayerRecord persisted;
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final ConcurrentHashMap<UUID, CachedScore> cache = new ConcurrentHashMap<>();
    private final EvaluationQueue queue = new EvaluationQueue();
    private final Source source;
    private final Sink sink;
    private final Metrics metrics;
    private final String serverName;
    private final LongSupplier clock;

    private ScoreCalculator calculator;
    private int generation;

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

    public void track(UUID id, String name) {
        states.computeIfAbsent(id, key -> new State()).name = name;
    }

    public boolean isTracked(UUID id) {
        return states.containsKey(id);
    }

    public void forget(UUID id) {
        states.remove(id);
        queue.remove(id);
        cache.remove(id);
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
     * Until the first evaluation it also backs the cache.
     */
    public void seedPersisted(UUID id, PlayerRecord record) {
        State state = states.get(id);
        if (state == null || record == null) {
            return;
        }
        if (state.persisted == null) {
            state.persisted = record;
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
        PlayerRecord record = state.persisted;
        if (record == null || !record.sameResult(state.name, result.total(), result.armorScore(),
                result.weaponScore(), tier, serverName, result.rulesVersion())) {
            record = new PlayerRecord(id, state.name, result.total(), result.armorScore(), result.weaponScore(),
                    tier, serverName, result.rulesVersion(), clock.getAsLong());
            state.persisted = record;
            sink.changed(record);
        }
        cache.put(id, new CachedScore(record, result));

        metrics.evaluations.increment();
        metrics.recordEvaluationTime(System.nanoTime() - start);
        reportUnknown(result);
    }

    private void reportUnknown(ScoreResult result) {
        for (int i = 0; i < 4; i++) {
            reportUnknown(result.armor(i));
        }
        reportUnknown(result.weapon());
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
