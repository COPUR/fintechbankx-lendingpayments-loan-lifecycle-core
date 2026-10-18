package com.bank.loan.infrastructure.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cluster-wide mutual exclusion through a session-level PostgreSQL advisory
 * lock, held on one pooled connection while the work runs. Unlike the outbox
 * relay's transaction-scoped lock, no transaction stays open while the work
 * waits on another service; the lock still ends with the session if the pod
 * dies.
 */
public class PostgresAdvisoryLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLock.class);

    private final JdbcTemplate jdbc;
    private final long key;

    public PostgresAdvisoryLock(JdbcTemplate jdbc, long key) {
        this.jdbc = jdbc;
        this.key = key;
    }

    /** Runs {@code work} if no other session holds the lock; empty if one does. */
    public <T> Optional<T> runExclusively(Supplier<T> work) {
        return jdbc.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!call(connection, "select pg_try_advisory_lock(?)")) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(work.get());
            } finally {
                if (!call(connection, "select pg_advisory_unlock(?)")) {
                    log.warn("Advisory lock {} was not held at unlock", key);
                }
            }
        });
    }

    private boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
