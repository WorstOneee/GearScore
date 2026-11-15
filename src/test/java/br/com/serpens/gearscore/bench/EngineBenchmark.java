package br.com.serpens.gearscore.bench;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.engine.Metrics;
import br.com.serpens.gearscore.engine.RemoteScores;
import br.com.serpens.gearscore.engine.ScoreEngine;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.ItemSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import br.com.serpens.gearscore.storage.Database;
import br.com.serpens.gearscore.storage.ScoreRepository;
import br.com.serpens.gearscore.storage.TestDatabase;
import br.com.serpens.gearscore.storage.WriteQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static br.com.serpens.gearscore.TestRules.armorSet;
import static br.com.serpens.gearscore.TestRules.gear;
import static br.com.serpens.gearscore.TestRules.item;

/**
 * SIMULATED load test of the evaluation pipeline, run in a plain JVM without a Paper server.
 *
 * It exercises the real debounce queue, signature comparison, calculator, result cache and
 * change detection. It does NOT include the cost of reading inventories through the server
 * API, event dispatch, or anything else a live server adds, and it says nothing about TPS.
 * Run with: mvnw test -Pbench
 */
@Tag("benchmark")
class EngineBenchmark {

    private static final int DEBOUNCE = 10;
    private static final int MAX_PER_TICK = 20;
    private static final int WARMUP_TICKS = 400;
    private static final int MEASURED_TICKS = 1200;
    private static final int FLUSH_TICKS = 60;
    private static final int BATCH_SIZE = 100;

    private final RuleSet rules = TestRules.defaults();
    // Inventories are prepared up front so the timed section only contains plugin work.
    private final GearSnapshot setA = buildSetA();
    private final GearSnapshot setB = buildSetB();
    private final GearSnapshot setC = gear(armorSet(rules, "iron", "protection", 2), item(rules, "iron_axe"));

    private enum Scenario {
        /** Durability-only events at 4/s per player, plus 5% of players swapping weapon each second. */
        COMBAT,
        /** Every player switches to a random one of three full sets every 2 ticks. */
        RAPID_SWAP,
        /** 10 inventory events/s per player; 20% move gear to other slots, composition unchanged. */
        ITEM_MOVEMENT
    }

    private final class Simulation {
        final ScoreEngine engine;
        final Metrics metrics = new Metrics();
        final List<UUID> players = new ArrayList<>();
        final Map<UUID, GearSnapshot> inventories = new HashMap<>();
        final Map<UUID, Integer> layout = new HashMap<>();
        final Map<UUID, PlayerRecord> pendingWrites = new HashMap<>();
        final Random random = new Random(42);
        long changes;
        long batches;
        long rows;
        long tick;

        Simulation(int count, ScoreEngine.Sink extraSink) {
            this.engine = new ScoreEngine(rules, "newera",
                    id -> fresh(inventories.get(id), layout.getOrDefault(id, 0)), record -> {
                changes++;
                pendingWrites.put(record.uuid(), record);
                if (extraSink != null) {
                    extraSink.changed(record);
                }
            }, metrics, System::currentTimeMillis);
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(7L, i);
                players.add(id);
                engine.track(id, "Player" + i);
                inventories.put(id, setA);
            }
        }

        void step(Scenario scenario) {
            tick++;
            switch (scenario) {
                case COMBAT -> {
                    if (tick % 5 == 0) {
                        for (UUID id : players) {
                            engine.request(id, tick, DEBOUNCE);
                        }
                    }
                    if (tick % 20 == 0) {
                        for (UUID id : players) {
                            if (random.nextInt(20) == 0) {
                                inventories.put(id, random.nextBoolean() ? setA : setB);
                                engine.request(id, tick, DEBOUNCE);
                            }
                        }
                    }
                }
                case RAPID_SWAP -> {
                    if (tick % 2 == 0) {
                        for (UUID id : players) {
                            int pick = random.nextInt(3);
                            inventories.put(id, pick == 0 ? setA : pick == 1 ? setB : setC);
                            engine.request(id, tick, DEBOUNCE);
                        }
                    }
                }
                case ITEM_MOVEMENT -> {
                    if (tick % 2 == 0) {
                        for (UUID id : players) {
                            if (random.nextInt(5) == 0) {
                                layout.put(id, random.nextInt(41));
                            }
                            engine.request(id, tick, DEBOUNCE);
                        }
                    }
                }
            }
            engine.tick(tick, MAX_PER_TICK);
            // Mirrors the write queue policy: latest state per player, flushed in batches.
            if (tick % FLUSH_TICKS == 0 && !pendingWrites.isEmpty()) {
                rows += pendingWrites.size();
                batches += (pendingWrites.size() + BATCH_SIZE - 1) / BATCH_SIZE;
                pendingWrites.clear();
            }
        }
    }

    /** A full PvP inventory: worn armor, six weapons and a spare armor set, 14 scored items. */
    private GearSnapshot buildSetA() {
        ItemSnapshot[] armor = armorSet(rules, "netherite", "protection", 4, "unbreaking", 3, "mending", 1);
        armor[3] = item(rules, "netherite_boots", "protection", 4, "unbreaking", 3, "mending", 1,
                "feather_falling", 4, "depth_strider", 3, "soul_speed", 3);
        ItemSnapshot[] spare = armorSet(rules, "netherite", "protection", 4, "unbreaking", 3);
        return gear(armor,
                item(rules, "netherite_sword", "sharpness", 5, "fire_aspect", 2, "knockback", 2,
                        "sweeping_edge", 3, "looting", 3, "unbreaking", 3, "mending", 1),
                item(rules, "netherite_axe", "sharpness", 5, "unbreaking", 3),
                item(rules, "bow", "power", 5, "flame", 1, "punch", 2),
                item(rules, "crossbow", "quick_charge", 3, "piercing", 4),
                item(rules, "mace", "density", 5, "wind_burst", 3),
                item(rules, "trident", "loyalty", 3, "impaling", 5),
                spare[0], spare[1], spare[2], spare[3]);
    }

    private GearSnapshot buildSetB() {
        return gear(armorSet(rules, "diamond", "protection", 3, "unbreaking", 3),
                item(rules, "diamond_sword", "sharpness", 4, "unbreaking", 3),
                item(rules, "crossbow", "quick_charge", 3, "piercing", 4),
                item(rules, "bow", "power", 4));
    }

    /**
     * New objects on every capture, as a real inventory read would produce. Items are handed
     * over starting at a different slot each time the layout changes, so the snapshot has to
     * put them back into its canonical order.
     */
    private static GearSnapshot fresh(GearSnapshot template, int firstSlot) {
        int size = template.size();
        ItemSnapshot[] items = new ItemSnapshot[size];
        for (int i = 0; i < size; i++) {
            items[i] = copy(template.item((i + firstSlot) % size));
        }
        return new GearSnapshot(items, size);
    }

    private static ItemSnapshot copy(ItemSnapshot item) {
        int count = item.enchantCount();
        String[] keys = new String[count];
        int[] levels = new int[count];
        for (int i = 0; i < count; i++) {
            keys[i] = item.enchantKey(i);
            levels[i] = item.enchantLevel(i);
        }
        return new ItemSnapshot(item.def(), keys, levels, item.custom());
    }

    @Test
    void simulatedLoad() {
        System.out.println("BENCH # JVM " + System.getProperty("java.version") + ", "
                + System.getProperty("os.name") + ", " + Runtime.getRuntime().availableProcessors() + " threads. "
                + MEASURED_TICKS + " measured ticks (" + MEASURED_TICKS / 20 + " s of game time), debounce "
                + DEBOUNCE + " ticks, max " + MAX_PER_TICK + " evaluations/tick.");
        System.out.println("BENCH | scenario | players | events | merged by debounce | captures | unchanged "
                + "| recalculated | avg us/tick | p99 us/tick | max us/tick | busy ticks | score changes "
                + "| SQL batches | rows written |");
        for (Scenario scenario : Scenario.values()) {
            for (int players : new int[]{50, 100, 200}) {
                run(scenario, players);
            }
        }
        memory();
        queryServer();
        outage();
    }

    private void run(Scenario scenario, int count) {
        // Warm the JIT on a throwaway simulation first.
        Simulation warmup = new Simulation(count, null);
        for (int i = 0; i < WARMUP_TICKS; i++) {
            warmup.step(scenario);
        }

        Simulation simulation = new Simulation(count, null);
        long[] nanos = new long[MEASURED_TICKS];
        int busy = 0;
        for (int i = 0; i < MEASURED_TICKS; i++) {
            long start = System.nanoTime();
            simulation.step(scenario);
            nanos[i] = System.nanoTime() - start;
            if (simulation.engine.hasPending()) {
                busy++;
            }
        }
        long total = Arrays.stream(nanos).sum();
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        Metrics m = simulation.metrics;
        System.out.printf(Locale.ROOT, "BENCH | %s | %d | %d | %d | %d | %d | %d | %.1f | %.1f | %.1f | %d | %d | %d | %d |%n",
                scenario, count, m.requests.sum() + m.coalesced.sum(), m.coalesced.sum(), m.captures.sum(),
                m.unchanged.sum(), m.evaluations.sum(),
                total / (double) MEASURED_TICKS / 1000.0,
                sorted[(int) (MEASURED_TICKS * 0.99)] / 1000.0,
                sorted[MEASURED_TICKS - 1] / 1000.0,
                busy, simulation.changes, simulation.batches, simulation.rows);
    }

    private void memory() {
        long before = usedHeap();
        Simulation simulation = new Simulation(2000, null);
        for (UUID id : simulation.players) {
            simulation.engine.evaluateNow(id);
        }
        long after = usedHeap();
        System.out.printf(Locale.ROOT, "BENCH-MEMORY | about %.1f KB of heap per tracked player "
                        + "(state + last snapshot + cached result with breakdown), measured over %d players%n",
                (after - before) / 1024.0 / simulation.players.size(), simulation.engine.trackedCount());
    }

    /**
     * A server that only reads the official score: players join over the first minute and
     * stay for ten. The database is a map; what is measured is the scheduling work on the
     * server thread and how many grouped reads it asks for.
     */
    private void queryServer() {
        System.out.println("BENCH-QUERY | players | polls (1/s) | grouped reads | players per read "
                + "| avg us/poll | max us/poll |");
        for (int count : new int[]{50, 100, 200}) {
            Map<UUID, PlayerRecord> rows = new HashMap<>();
            long[] reads = new long[2];
            RemoteScores remote = new RemoteScores(new RemoteScores.Loader() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public CompletableFuture<Map<UUID, PlayerRecord>> load(List<UUID> ids) {
                    reads[0]++;
                    reads[1] += ids.size();
                    Map<UUID, PlayerRecord> found = new HashMap<>();
                    for (UUID id : ids) {
                        found.put(id, rows.get(id));
                    }
                    return CompletableFuture.completedFuture(found);
                }
            }, new Metrics(), 5_000L, 30_000L, 100, 3);

            int polls = 600;
            long total = 0L;
            long max = 0L;
            int joined = 0;
            for (int second = 0; second < polls; second++) {
                long now = second * 1_000L;
                long start = System.nanoTime();
                // Joins spread over the first 60 seconds.
                while (joined < count && joined * 60L / count <= second) {
                    UUID id = new UUID(9L, joined++);
                    rows.put(id, new PlayerRecord(id, "P", 1000, 800, 200, "elite", "newera", 1, 1L));
                    remote.open(id, now);
                }
                remote.poll(now);
                long elapsed = System.nanoTime() - start;
                total += elapsed;
                max = Math.max(max, elapsed);
            }
            System.out.printf(Locale.ROOT, "BENCH-QUERY | %d | %d | %d | %.1f | %.1f | %.1f |%n", count, polls,
                    reads[0], reads[1] / (double) reads[0], total / (double) polls / 1000.0, max / 1000.0);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Rapid swaps for 200 players while the database (H2 stand-in) refuses every connection. */
    private void outage() {
        Metrics metrics = new Metrics();
        TestDatabase source = new TestDatabase();
        source.down = true;
        Database database = source.open(metrics, 2000L, 60_000L);
        Logger logger = Logger.getLogger("GearScoreTest");
        WriteQueue writer = new WriteQueue(database, new ScoreRepository(source.prefix()), metrics, logger,
                50L, BATCH_SIZE, 5000);

        Simulation simulation = new Simulation(200, writer::submit);
        long[] nanos = new long[MEASURED_TICKS];
        long wallStart = System.nanoTime();
        for (int i = 0; i < MEASURED_TICKS; i++) {
            long start = System.nanoTime();
            simulation.step(Scenario.RAPID_SWAP);
            nanos[i] = System.nanoTime() - start;
            if (i % 100 == 0) {
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000L;
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "BENCH-OUTAGE | 200 players, rapid swap, database down for the whole run "
                        + "(%d ms wall time) | avg %.1f us/tick | max %.1f us/tick | score changes submitted %d "
                        + "| pending in memory %d | merged %d | dropped %d | connection attempts failed %d "
                        + "| rows written %d |%n",
                wallMillis, Arrays.stream(nanos).sum() / (double) MEASURED_TICKS / 1000.0,
                sorted[MEASURED_TICKS - 1] / 1000.0, simulation.changes, writer.pendingCount(),
                metrics.writesMerged.sum(), metrics.writesDropped.sum(), metrics.databaseFailures.sum(),
                metrics.rowsWritten.sum());
        long start = System.nanoTime();
        int lost = writer.shutdown(2000L);
        System.out.printf(Locale.ROOT, "BENCH-OUTAGE | shutdown with the database still down returned in %d ms, "
                + "%d update(s) left unwritten%n", (System.nanoTime() - start) / 1_000_000L, lost);
    }
}
