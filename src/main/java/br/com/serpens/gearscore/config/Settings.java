package br.com.serpens.gearscore.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
import java.util.Set;

/**
 * Operational settings from config.yml (everything that is not a scoring rule).
 *
 * @param official whether this instance may calculate and persist the official score
 */
public record Settings(String serverName, boolean official, int debounceTicks, int maxPerTick,
                       Set<String> ignoredWorlds, int pageSize, int maxEntries, long topCacheMillis,
                       long positionRefreshMillis, String fallback, long remoteIntervalMillis,
                       long remoteRecheckMillis, int remoteBatchSize, int remoteMaxBatches) {

    public static Settings from(ConfigurationSection root) {
        String name = root.getString("server.name", "server").trim();
        if (name.isEmpty() || name.length() > 32) {
            throw new IllegalArgumentException("config.yml: server.name must have 1 to 32 characters");
        }
        boolean official = false;
        for (String source : root.getStringList("server.official-sources")) {
            if (source.trim().equalsIgnoreCase(name)) {
                official = true;
                break;
            }
        }
        return new Settings(
                name,
                official,
                clamp(root.getInt("evaluation.debounce-ticks", 10), 1, 200),
                clamp(root.getInt("evaluation.max-per-tick", 20), 1, 1000),
                Set.copyOf(new HashSet<>(root.getStringList("evaluation.ignored-worlds"))),
                clamp(root.getInt("ranking.page-size", 10), 1, 50),
                clamp(root.getInt("ranking.max-entries", 100), 1, 1000),
                Math.max(1L, root.getLong("ranking.cache-seconds", 30L)) * 1000L,
                Math.max(5L, root.getLong("ranking.position-refresh-seconds",
                        root.getLong("ranking.position-cache-seconds", 60L))) * 1000L,
                root.getString("placeholders.fallback", "-"),
                Math.max(5L, root.getLong("query-refresh.interval-seconds", 30L)) * 1000L,
                Math.max(1L, root.getLong("query-refresh.join-recheck-seconds", 5L)) * 1000L,
                clamp(root.getInt("query-refresh.batch-size", 100), 1, 500),
                clamp(root.getInt("query-refresh.max-batches-per-cycle", 3), 1, 20));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
