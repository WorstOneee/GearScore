package br.com.serpens.gearscore.engine;

import br.com.serpens.gearscore.TestRules;
import br.com.serpens.gearscore.model.PlayerRecord;
import br.com.serpens.gearscore.score.GearSnapshot;
import br.com.serpens.gearscore.score.ItemSnapshot;
import br.com.serpens.gearscore.score.RuleSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.serpens.gearscore.TestRules.armorSet;
import static br.com.serpens.gearscore.TestRules.gear;
import static br.com.serpens.gearscore.TestRules.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoreEngineTest {

    private static final int DEBOUNCE = 10;

    private final RuleSet rules = TestRules.defaults();
    private final Map<UUID, GearSnapshot> inventories = new HashMap<>();
    private final List<PlayerRecord> written = new ArrayList<>();
    private final Metrics metrics = new Metrics();
    private final UUID player = UUID.randomUUID();
    private ScoreEngine engine;
    private long tick;
    private long clock = 1_000L;

    @BeforeEach
    void setUp() {
        engine = new ScoreEngine(rules, "newera", inventories::get, written::add, metrics, () -> clock);
        engine.track(player, "Zwo");
    }

    private void advance(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick++;
            engine.tick(tick, 20);
        }
    }

    private GearSnapshot netherite(String sword) {
        return gear(armorSet(rules, "netherite", "protection", 4), item(rules, sword, 0, "sharpness", 5));
    }

    @Test
    void repeatedEventsBecomeOneEvaluationAfterTheDebounce() {
        inventories.put(player, netherite("netherite_sword"));
        for (int i = 0; i < 25; i++) {
            engine.request(player, tick, DEBOUNCE);
        }
        advance(DEBOUNCE - 1);
        assertNull(engine.cached(player), "must wait for the debounce");
        advance(1);
        assertNotNull(engine.cached(player));
        assertEquals(1, metrics.evaluations.sum());
        assertEquals(1, metrics.requests.sum());
        assertEquals(24, metrics.coalesced.sum());
        assertEquals(1, written.size());
        assertFalse(engine.hasPending());
    }

    @Test
    void unchangedEquipmentIsNotRecalculatedNorWritten() {
        inventories.put(player, netherite("netherite_sword"));
        engine.request(player, tick, DEBOUNCE);
        advance(DEBOUNCE);
        // Same gear captured again (e.g. only an item amount or durability changed).
        inventories.put(player, netherite("netherite_sword"));
        engine.request(player, tick, DEBOUNCE);
        advance(DEBOUNCE);
        assertEquals(2, metrics.captures.sum());
        assertEquals(1, metrics.unchanged.sum());
        assertEquals(1, metrics.evaluations.sum());
        assertEquals(1, written.size());
    }

    @Test
    void scoreFollowsSwapBreakAndRemoval() {
        inventories.put(player, netherite("netherite_sword"));
        engine.evaluateNow(player);
        int full = engine.cached(player).record().score();

        // weapon swapped for a weaker one
        inventories.put(player, netherite("iron_sword"));
        engine.evaluateNow(player);
        int weaker = engine.cached(player).record().score();
        assertEquals(full - (80 - 45), weaker);

        // chestplate broke
        ItemSnapshot[] armor = armorSet(rules, "netherite", "protection", 4);
        armor[1] = null;
        inventories.put(player, gear(armor, item(rules, "iron_sword", 0, "sharpness", 5)));
        engine.evaluateNow(player);
        int broken = engine.cached(player).record().score();
        assertEquals(weaker - (90 + 150), broken);

        // everything removed
        inventories.put(player, gear(null));
        engine.evaluateNow(player);
        assertEquals(0, engine.cached(player).record().score());
        assertEquals(4, written.size());
        assertEquals(0, written.get(3).score());
    }

    @Test
    void currentScoreIsNotAHistoricalBest() {
        inventories.put(player, netherite("netherite_sword"));
        engine.evaluateNow(player);
        inventories.put(player, gear(armorSet(rules, "leather")));
        engine.evaluateNow(player);
        assertEquals(60, written.get(written.size() - 1).score());
    }

    @Test
    void evaluationsAreSpreadByThePerTickLimit() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            players.add(id);
            engine.track(id, "P" + i);
            inventories.put(id, netherite("netherite_sword"));
            engine.request(id, tick, DEBOUNCE);
        }
        tick += DEBOUNCE;
        assertEquals(20, engine.tick(tick, 20));
        assertEquals(80, engine.pendingEvaluations());
        for (int i = 0; i < 4; i++) {
            tick++;
            assertEquals(20, engine.tick(tick, 20));
        }
        assertFalse(engine.hasPending());
        assertEquals(100, metrics.evaluations.sum());
    }

    @Test
    void reloadReevaluatesOnlinePlayersGradually() {
        for (int i = 0; i < 50; i++) {
            UUID id = UUID.randomUUID();
            engine.track(id, "P" + i);
            inventories.put(id, netherite("netherite_sword"));
            engine.evaluateNow(id);
        }
        long before = metrics.evaluations.sum();
        RuleSet doubled = TestRules.with(config -> config.set("enchantments.category-multipliers.combat", 2.0));
        engine.setRules(doubled);
        engine.requestAll(tick, DEBOUNCE);
        tick += DEBOUNCE;
        // Nothing is recalculated at once: at most the per-tick limit.
        assertEquals(20, engine.tick(tick, 20));
        assertEquals(before + 20, metrics.evaluations.sum());
        advance(5);
        assertEquals(before + 50, metrics.evaluations.sum());
    }

    @Test
    void sameResultAsTheDatabaseIsNotWrittenAgain() {
        inventories.put(player, netherite("netherite_sword"));
        engine.evaluateNow(player);
        PlayerRecord stored = written.get(0);

        // The player reconnects with the same gear: the row already holds this result.
        engine.forget(player);
        engine.track(player, "Zwo");
        engine.seedPersisted(player, stored);
        assertSame(stored, engine.cached(player).record());
        clock = 99_000L;
        engine.evaluateNow(player);
        assertEquals(1, written.size());
        assertEquals(stored.updatedAt(), engine.cached(player).record().updatedAt());
        assertNotNull(engine.cached(player).detail());
    }

    @Test
    void resultFromAnotherRulesVersionOrServerIsRewritten() {
        inventories.put(player, netherite("netherite_sword"));
        engine.evaluateNow(player);
        PlayerRecord stored = written.get(0);
        engine.forget(player);
        engine.track(player, "Zwo");
        engine.seedPersisted(player, new PlayerRecord(player, "Zwo", stored.score(), stored.armorScore(),
                stored.weaponScore(), stored.tier(), "newera", stored.rulesVersion() - 1, 5L));
        engine.evaluateNow(player);
        assertEquals(2, written.size());
    }

    @Test
    void skippedCaptureKeepsTheLastScore() {
        inventories.put(player, netherite("netherite_sword"));
        engine.evaluateNow(player);
        int score = engine.cached(player).record().score();
        // Player in an ignored world: the source declines to capture.
        inventories.remove(player);
        engine.evaluateNow(player);
        assertEquals(score, engine.cached(player).record().score());
        assertEquals(1, written.size());
    }

    @Test
    void forgetClearsEverythingForThePlayer() {
        inventories.put(player, netherite("netherite_sword"));
        engine.request(player, tick, DEBOUNCE);
        engine.forget(player);
        advance(DEBOUNCE + 1);
        assertNull(engine.cached(player));
        assertEquals(0, engine.trackedCount());
        assertEquals(0, metrics.evaluations.sum());
        assertTrue(written.isEmpty());
    }

    @Test
    void unknownEnchantmentsAreReportedForDiagnostics() {
        inventories.put(player, gear(null, item(rules, "diamond_sword", 0, "customplugin:vampirism", 2)));
        engine.evaluateNow(player);
        assertTrue(metrics.unknownEnchantments().contains("sword -> customplugin:vampirism"));
    }
}
