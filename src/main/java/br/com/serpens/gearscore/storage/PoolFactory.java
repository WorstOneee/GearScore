package br.com.serpens.gearscore.storage;

import br.com.serpens.gearscore.config.DatabaseSettings;
import br.com.serpens.gearscore.engine.Metrics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.logging.Logger;

/**
 * Builds the HikariCP pool. The pool is created without connecting, so the plugin starts
 * even when the database is down and connects once it comes back.
 */
public final class PoolFactory {

    private PoolFactory() {
    }

    public static Database create(DatabaseSettings settings, Logger logger, Metrics metrics) {
        HikariConfig config = new HikariConfig();
        // MariaDB Connector/J is the only driver, and it is shaded into the plugin JAR.
        String url = "jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database();
        if (!settings.parameters().isBlank()) {
            url += "?" + settings.parameters();
        }
        config.setJdbcUrl(url);
        config.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setPoolName("GearScore");
        config.setMaximumPoolSize(settings.maxConnections());
        config.setMinimumIdle(Math.min(settings.minIdle(), settings.maxConnections()));
        config.setConnectionTimeout(settings.connectionTimeoutMillis());
        config.setValidationTimeout(Math.min(3000L, settings.connectionTimeoutMillis()));
        config.setMaxLifetime(settings.maxLifetimeMillis());
        config.setInitializationFailTimeout(-1L);

        HikariDataSource dataSource = new HikariDataSource(config);
        return new Database(dataSource, dataSource, settings.tablePrefix(),
                settings.retryInitialMillis(), settings.retryMaxMillis(), logger, metrics);
    }
}
