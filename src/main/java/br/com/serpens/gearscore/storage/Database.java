package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Gate in front of the connection pool. Tracks failures, applies exponential backoff so a
 * dead database is not hammered, keeps the log quiet during an outage and runs the schema
 * migrations before the first real statement.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Thrown without touching the pool while the backoff window is open. */
    public static final class UnavailableException extends SQLException {
        public UnavailableException(long retryInMillis) {
            super("database unavailable, next attempt in " + retryInMillis + " ms");
        }
    }

    private static final long LOG_INTERVAL_MILLIS = 60_000L;

    private final DataSource dataSource;
    private final AutoCloseable closer;
    private final String prefix;
    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final Logger logger;
    private final Metrics metrics;

    private final Object lock = new Object();
    private volatile boolean schemaReady;
    private volatile long retryAt;
    private int failures;
    private long lastLogAt;
    private int suppressedLogs;

    public Database(DataSource dataSource, AutoCloseable closer, String prefix,
                    long initialDelayMillis, long maxDelayMillis, Logger logger, Metrics metrics) {
        this.dataSource = dataSource;
        this.closer = closer;
        this.prefix = prefix;
        this.initialDelayMillis = Math.max(1L, initialDelayMillis);
        this.maxDelayMillis = Math.max(this.initialDelayMillis, maxDelayMillis);
        this.logger = logger;
        this.metrics = metrics;
    }

    public String prefix() {
        return prefix;
    }

    /** False while waiting for the next retry after a failure. */
    public boolean isAvailable() {
        return System.currentTimeMillis() >= retryAt;
    }

    public long retryInMillis() {
        return Math.max(0L, retryAt - System.currentTimeMillis());
    }

    public int consecutiveFailures() {
        synchronized (lock) {
            return failures;
        }
    }

    public <T> T run(SqlWork<T> work) throws SQLException {
        if (!isAvailable()) {
            throw new UnavailableException(retryInMillis());
        }
        try (Connection connection = dataSource.getConnection()) {
            ensureSchema(connection);
            T result = work.run(connection);
            onSuccess();
            return result;
        } catch (SQLException | RuntimeException e) {
            onFailure(e);
            throw e;
        }
    }

    private void ensureSchema(Connection connection) throws SQLException {
        if (schemaReady) {
            return;
        }
        synchronized (lock) {
            if (schemaReady) {
                return;
            }
            int applied = new Migrator(prefix).migrate(connection);
            schemaReady = true;
            if (applied > 0) {
                logger.info("Applied " + applied + " database migration(s).");
            }
        }
    }

    private void onSuccess() {
        synchronized (lock) {
            if (failures == 0) {
                return;
            }
            logger.info("Database connection recovered after " + failures + " failed attempt(s).");
            failures = 0;
            suppressedLogs = 0;
            lastLogAt = 0L;
            retryAt = 0L;
        }
    }

    private void onFailure(Exception error) {
        metrics.databaseFailures.increment();
        synchronized (lock) {
            failures++;
            long delay = initialDelayMillis << Math.min(failures - 1, 20);
            if (delay <= 0 || delay > maxDelayMillis) {
                delay = maxDelayMillis;
            }
            long now = System.currentTimeMillis();
            retryAt = now + delay;
            if (now - lastLogAt >= LOG_INTERVAL_MILLIS) {
                String extra = suppressedLogs > 0 ? " (" + suppressedLogs + " similar message(s) suppressed)" : "";
                logger.log(Level.WARNING, "Database error, retrying in " + delay + " ms" + extra + ": "
                        + describe(error));
                lastLogAt = now;
                suppressedLogs = 0;
            } else {
                suppressedLogs++;
            }
        }
    }

    private static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        if (closer == null) {
            return;
        }
        try {
            closer.close();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error while closing the connection pool: " + describe(e));
        }
    }
}
