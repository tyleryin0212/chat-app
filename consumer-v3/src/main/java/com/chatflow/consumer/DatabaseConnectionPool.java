package com.chatflow.consumer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP connection pool for PostgreSQL.
 * Maintains a fixed set of pre-established connections for batch writers to borrow.
 */
public class DatabaseConnectionPool {

    private final HikariDataSource dataSource;

    public DatabaseConnectionPool(String host, String dbName, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":5432/" + dbName);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize / 2);
        config.setConnectionTimeout(30_000);   // 30s to get a connection from pool
        config.setIdleTimeout(600_000);        // close idle connections after 10 min
        config.setMaxLifetime(1_800_000);      // recycle connections every 30 min
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "25");

        this.dataSource = new HikariDataSource(config);
        System.out.println("Database connection pool initialized: " + poolSize + " connections to " + host);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        dataSource.close();
        System.out.println("Database connection pool closed");
    }
}