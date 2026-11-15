package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import org.h2.jdbcx.JdbcDataSource;
import org.mariadb.jdbc.MariaDbDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Test database with a switch that simulates an outage.
 *
 * By default it is an in-memory H2 database in MySQL compatibility mode, which stands in for
 * MariaDB but is not one. When the system property {@code gearscore.test.jdbc} holds a
 * MariaDB JDBC URL (with credentials), the same tests run against that real server instead;
 * every instance uses its own table prefix, so nothing else in the schema is touched.
 */
public final class TestDatabase implements DataSource {

    public static final String JDBC_PROPERTY = "gearscore.test.jdbc";

    private final DataSource delegate;
    private final String prefix;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger attempts = new AtomicInteger();
    public volatile boolean down;
    /** While down, how long a connection attempt hangs before failing. */
    public volatile long outageDelayMillis;
    /** While up, how long a connection takes to be handed out (a slow database). */
    public volatile long connectDelayMillis;

    public TestDatabase() {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.prefix = "t" + unique + "_";
        String url = System.getProperty(JDBC_PROPERTY);
        try {
            if (url == null || url.isBlank()) {
                JdbcDataSource h2 = new JdbcDataSource();
                h2.setURL("jdbc:h2:mem:" + unique + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
                this.delegate = h2;
            } else {
                this.delegate = new MariaDbDataSource(url);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean realMariaDb() {
        String url = System.getProperty(JDBC_PROPERTY);
        return url != null && !url.isBlank();
    }

    public String prefix() {
        return prefix;
    }

    public Database open(Metrics metrics, long initialDelayMillis, long maxDelayMillis) {
        Logger logger = Logger.getLogger("GearScoreTest");
        logger.setUseParentHandlers(false);
        return new Database(this, null, prefix, initialDelayMillis, maxDelayMillis, logger, metrics);
    }

    /** Connections handed out, i.e. attempts that actually reached the database. */
    public int connectionsOpened() {
        return connections.get();
    }

    /** Every call for a connection, including the ones refused by the simulated outage. */
    public int attempts() {
        return attempts.get();
    }

    /** Removes the tables of this instance; only needed on a real server. */
    public void drop() {
        if (!realMariaDb()) {
            return;
        }
        try (Connection connection = delegate.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + prefix + "players");
            statement.execute("DROP TABLE IF EXISTS " + prefix + "schema_version");
        } catch (SQLException ignored) {
            // best effort clean-up
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        attempts.incrementAndGet();
        if (down) {
            long delay = outageDelayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            throw new SQLException("simulated outage");
        }
        long slow = connectDelayMillis;
        if (slow > 0) {
            try {
                Thread.sleep(slow);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        connections.incrementAndGet();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
