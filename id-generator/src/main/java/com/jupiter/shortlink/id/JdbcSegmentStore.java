/* Controlled adaptation of Leaf IDAllocDaoImpl's UPDATE/SELECT/commit protocol; Apache-2.0, see UPSTREAM.md. */
package com.jupiter.shortlink.id;

import static com.jupiter.shortlink.id.IdGenerationException.Reason.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * MySQL/InnoDB only. Uses its own connection; never a Spring transaction-bound connection.
 * DataSource ownership remains with the caller. Supply a dedicated bounded pool with
 * autoCommit=true, bounded acquisition/connect time and a driver that honors query/network
 * timeouts.
 */
public final class JdbcSegmentStore implements SegmentStore {
    public static final String NAMESPACE = "shortlink_global";
    private static final String READ_LOCKED =
            "SELECT max_id, step FROM t_id_alloc WHERE biz_tag = ? FOR UPDATE";
    private static final String ADVANCE =
            "UPDATE t_id_alloc SET max_id = ?, update_time = CURRENT_TIMESTAMP(3) "
                    + "WHERE biz_tag = ? AND max_id = ? AND max_id >= 1 AND max_id < ? AND ? <= ?";
    private static final String READ_RESULT = "SELECT max_id FROM t_id_alloc WHERE biz_tag = ?";
    private final DataSource dataSource;
    private final JdbcOptions options;
    private final int queryTimeoutSeconds;
    private volatile boolean schemaVerified;

    public JdbcSegmentStore(DataSource dataSource) {
        this(dataSource, JdbcOptions.defaults());
    }

    public JdbcSegmentStore(DataSource dataSource, JdbcOptions options) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.options = Objects.requireNonNull(options);
        this.queryTimeoutSeconds =
                Math.toIntExact(Math.max(1, (options.statementTimeout().toMillis() + 999) / 1000));
    }

    @Override
    public IdRange reserve(int requestedSize) {
        if (requestedSize < 1 || requestedSize > 1_000_000) {
            throw new IllegalArgumentException("Requested segment size outside [1, 1000000]");
        }
        boolean commitAttempted = false;
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getAutoCommit()) {
                throw new IdGenerationException(
                        CONFIGURATION, "Allocator requires an independent auto-commit connection");
            }
            if (!options.expectedCatalog().equals(connection.getCatalog())) {
                throw new IdGenerationException(
                        CONFIGURATION, "Allocator connected to an unexpected database catalog");
            }
            connection.setNetworkTimeout(
                    Runnable::run, Math.toIntExact(options.networkTimeout().toMillis()));
            verifySchema(connection);
            try (Statement settings = connection.createStatement()) {
                settings.setQueryTimeout(queryTimeoutSeconds);
                settings.execute(
                        "SET SESSION innodb_lock_wait_timeout = " + options.lockWaitSeconds());
            }
            connection.setAutoCommit(false);
            try {
                long start;
                try (PreparedStatement select = prepare(connection, READ_LOCKED)) {
                    select.setString(1, NAMESPACE);
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next())
                            throw new IdGenerationException(
                                    CONFIGURATION,
                                    "Required shortlink_global allocation row missing");
                        start = result.getLong(1);
                        boolean nullMax = result.wasNull();
                        int configuredStep = result.getInt(2);
                        if (result.next()
                                || nullMax
                                || start < 1
                                || start > IdRange.ID_LIMIT
                                || configuredStep < 1) {
                            throw new IdGenerationException(
                                    CONFIGURATION, "Invalid global allocation row");
                        }
                    }
                }
                if (start == IdRange.ID_LIMIT)
                    throw new IdGenerationException(
                            SPACE_EXHAUSTED, "Global 52-bit space exhausted");
                long grantSize = Math.min((long) requestedSize, IdRange.ID_LIMIT - start);
                long end = Math.addExact(start, grantSize);
                try (PreparedStatement update = prepare(connection, ADVANCE)) {
                    update.setLong(1, end);
                    update.setString(2, NAMESPACE);
                    update.setLong(3, start);
                    update.setLong(4, IdRange.ID_LIMIT);
                    update.setLong(5, end);
                    update.setLong(6, IdRange.ID_LIMIT);
                    if (update.executeUpdate() != 1) {
                        throw new IdGenerationException(
                                DATABASE, "Allocation guard did not update exactly one row");
                    }
                }
                try (PreparedStatement select = prepare(connection, READ_RESULT)) {
                    select.setString(1, NAMESPACE);
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next() || result.getLong(1) != end || result.next()) {
                            throw new IdGenerationException(
                                    DATABASE, "Allocation UPDATE/SELECT result mismatch");
                        }
                    }
                }
                IdRange granted = new IdRange(start, end);
                commitAttempted = true;
                connection.commit();
                return granted;
            } catch (SQLException | RuntimeException failed) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailed) {
                    failed.addSuppressed(rollbackFailed);
                }
                throw failed;
            }
        } catch (SQLException failed) {
            throw new IdGenerationException(
                    commitAttempted ? COMMIT_UNKNOWN : DATABASE,
                    commitAttempted
                            ? "Commit outcome uncertain; candidate interval discarded"
                            : "Independent allocation transaction failed",
                    failed);
        }
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(queryTimeoutSeconds);
            return statement;
        } catch (SQLException failed) {
            statement.close();
            throw failed;
        }
    }

    private void verifySchema(Connection connection) throws SQLException {
        if (schemaVerified) return;
        try (PreparedStatement table =
                prepare(
                        connection,
                        "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND"
                                + " TABLE_NAME = 't_id_alloc'")) {
            table.setString(1, options.expectedCatalog());
            try (ResultSet result = table.executeQuery()) {
                if (!result.next()
                        || !"InnoDB".equalsIgnoreCase(result.getString(1))
                        || result.next()) {
                    throw new IdGenerationException(
                            CONFIGURATION, "t_id_alloc must be an existing InnoDB table");
                }
            }
        }
        try (PreparedStatement primary =
                prepare(
                        connection,
                        "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA ="
                            + " ? AND TABLE_NAME = 't_id_alloc' AND INDEX_NAME = 'PRIMARY' ORDER BY"
                            + " SEQ_IN_INDEX")) {
            primary.setString(1, options.expectedCatalog());
            try (ResultSet result = primary.executeQuery()) {
                if (!result.next()
                        || !"biz_tag".equalsIgnoreCase(result.getString(1))
                        || result.next()) {
                    throw new IdGenerationException(
                            CONFIGURATION, "t_id_alloc primary key must be exactly biz_tag");
                }
            }
        }
        schemaVerified = true;
    }
}
