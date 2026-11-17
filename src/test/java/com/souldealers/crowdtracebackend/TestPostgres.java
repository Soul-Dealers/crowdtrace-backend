package com.souldealers.crowdtracebackend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real PostgreSQL 16 for tests, without Docker: one embedded server per test JVM, and a fresh empty database for
 * every {@link #newDatabase()} call, so each test class stays as isolated as it was with its own container.
 * The getters match Testcontainers' names so tests read the same.
 */
public final class TestPostgres {
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static EmbeddedPostgres server;

    private TestPostgres() {
    }

    public static synchronized Database newDatabase() {
        String name = "test_" + NEXT.incrementAndGet();
        try (Connection c = server().getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        return new Database(server().getJdbcUrl("postgres", name));
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                // Up to 32 cached Spring contexts each hold a connection pool on this one server.
                server = EmbeddedPostgres.builder().setServerConfig("max_connections", "500").start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // The JVM is exiting; the data directory is temporary.
                }
            }));
        }
        return server;
    }

    public record Database(String jdbcUrl) {
        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public String getUsername() {
            return "postgres";
        }

        public String getPassword() {
            return "";
        }

        public String getDriverClassName() {
            return "org.postgresql.Driver";
        }
    }
}
