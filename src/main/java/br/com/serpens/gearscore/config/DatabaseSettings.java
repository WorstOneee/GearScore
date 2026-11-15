package br.com.serpens.gearscore.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Connection and persistence settings read from database.yml.
 */
public record DatabaseSettings(boolean enabled, String host, int port, String database,
                               String username, String password, String parameters, String tablePrefix,
                               int maxConnections, int minIdle, long connectionTimeoutMillis,
                               long maxLifetimeMillis, int readerThreads, int maxQueuedReads,
                               long flushIntervalMillis, int batchSize, int maxPendingWrites,
                               long retryInitialMillis, long retryMaxMillis,
                               long offlineCacheMillis, int offlineCacheSize, long shutdownTimeoutMillis) {

    public static DatabaseSettings from(ConfigurationSection root) {
        String prefix = root.getString("table-prefix", "gearscore_");
        if (!prefix.matches("[A-Za-z0-9_]{0,32}")) {
            throw new IllegalArgumentException("database.yml: table-prefix may only contain letters, digits and _");
        }
        return new DatabaseSettings(
                root.getBoolean("enabled", true),
                root.getString("host", "127.0.0.1"),
                root.getInt("port", 3306),
                root.getString("database", "serpens"),
                root.getString("username", "root"),
                root.getString("password", ""),
                root.getString("parameters", ""),
                prefix,
                clamp(root.getInt("pool.max-connections", 3), 1, 20),
                clamp(root.getInt("pool.min-idle", 1), 0, 20),
                Math.max(1000L, root.getLong("pool.connection-timeout-ms", 5000L)),
                Math.max(30_000L, root.getLong("pool.max-lifetime-ms", 1_800_000L)),
                clamp(root.getInt("reads.threads", 1), 1, 8),
                clamp(root.getInt("reads.max-queued", 64), 1, 1024),
                Math.max(250L, root.getLong("writes.flush-interval-ms", 3000L)),
                clamp(root.getInt("writes.batch-size", 100), 1, 1000),
                clamp(root.getInt("writes.max-pending", 5000), 100, 100_000),
                Math.max(250L, root.getLong("retry.initial-delay-ms", 2000L)),
                Math.max(1000L, root.getLong("retry.max-delay-ms", 60_000L)),
                Math.max(0L, root.getLong("reads.offline-cache-seconds", 60L)) * 1000L,
                clamp(root.getInt("reads.offline-cache-size", 500), 1, 10_000),
                Math.max(0L, root.getLong("shutdown-timeout-seconds", 5L)) * 1000L);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
