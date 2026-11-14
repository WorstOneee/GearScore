package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.engine.Metrics;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * In-memory H2 database in MySQL compatibility mode, with a switch that simulates an outage.
 * This stands in for MariaDB in automated tests; it is not a MariaDB server.
 */
public final class TestDatabase implements DataSource {

    private final JdbcDataSource delegate = new JdbcDataSource();
    private final AtomicInteger connections = new AtomicInteger();
    public volatile boolean down;

    public TestDatabase() {
        this(UUID.randomUUID().toString());
    }

    public TestDatabase(String name) {
        delegate.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    public Database open(Metrics metrics, long initialDelayMillis, long maxDelayMillis) {
        Logger logger = Logger.getLogger("GearScoreTest");
        logger.setUseParentHandlers(false);
        return new Database(this, null, "gearscore_", initialDelayMillis, maxDelayMillis, logger, metrics);
    }

    public int connectionsOpened() {
        return connections.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (down) {
            throw new SQLException("simulated outage");
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
