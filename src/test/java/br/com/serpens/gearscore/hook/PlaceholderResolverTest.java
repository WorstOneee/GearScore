package br.com.serpens.gearscore.hook;

import br.com.serpens.gearscore.model.CachedScore;
import br.com.serpens.gearscore.model.PlayerRecord;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the sidebar (TAB through TAB-Bridge and PlaceholderAPI) gets back.
 */
class PlaceholderResolverTest {

    /** Counts every access; the resolver may only read these maps. */
    private static final class Memory implements PlaceholderResolver.Source {
        final Map<UUID, CachedScore> scores = new HashMap<>();
        final Map<UUID, Integer> positions = new HashMap<>();
        int reads;

        @Override
        public CachedScore cached(UUID id) {
            reads++;
            return scores.get(id);
        }

        @Override
        public String tierPlain(String tierId) {
            return "Elite";
        }

        @Override
        public String tierColored(String tierId) {
            return "§dElite";
        }

        @Override
        public int position(UUID id) {
            return positions.getOrDefault(id, -1);
        }

        @Override
        public String fallback() {
            return "—";
        }
    }

    private final Memory memory = new Memory();
    private final PlaceholderResolver resolver = new PlaceholderResolver(memory);
    private final UUID player = UUID.randomUUID();

    private void score(int total) {
        memory.scores.put(player, new CachedScore(
                new PlayerRecord(player, "zWorstOne", total, total - 300, 300, "elite", "newera", 2, 1L), null));
    }

    @Test
    void scoreAndTierAreSeparatePlaceholders() {
        score(1125);
        assertEquals("1125", resolver.resolve(player, "score"));
        assertEquals("1125", resolver.resolve(player, "total"));
        assertEquals("Elite", resolver.resolve(player, "tier"));
        assertEquals("§dElite", resolver.resolve(player, "tier_colored"));
        assertEquals("elite", resolver.resolve(player, "tier_id"));
        assertEquals("825", resolver.resolve(player, "armor"));
        assertEquals("300", resolver.resolve(player, "weapon"));
        assertEquals("1125", resolver.resolve(player, "SCORE"));
    }

    @Test
    void missingDataShowsTheFallbackNeverAnErrorOrTheRawName() {
        for (String name : List.of("score", "total", "tier", "tier_colored", "tier_id", "armor", "weapon",
                "position")) {
            assertEquals("—", resolver.resolve(player, name), name);
            assertEquals("—", resolver.resolve(null, name), name);
        }
        score(1125);
        assertEquals("—", resolver.resolve(player, "position"), "no position in memory yet");
        memory.positions.put(player, 7);
        assertEquals("7", resolver.resolve(player, "position"));
    }

    @Test
    void namesThatAreNotOursAreLeftToOtherExpansions() {
        score(1125);
        assertNull(resolver.resolve(player, "something_else"));
        assertEquals(0, memory.reads);
    }

    @Test
    void theValueFollowsTheCacheWithoutAnyOtherStep() {
        score(1125);
        assertEquals("1125", resolver.resolve(player, "score"));
        // A new evaluation lands in the cache; the next read simply returns it.
        score(2480);
        assertEquals("2480", resolver.resolve(player, "score"));
        memory.scores.remove(player);
        assertEquals("—", resolver.resolve(player, "score"));
    }

    @Test
    void oneMemoryReadPerRequestHoweverOftenItIsAsked() {
        score(1125);
        for (int i = 0; i < 100_000; i++) {
            resolver.resolve(player, "score");
        }
        assertEquals(100_000, memory.reads);
    }
}
