package com.jupiter.shortlink.id;

import static com.jupiter.shortlink.id.IdGenerationException.Reason.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Never targets a development DB. Requires explicit reset permission and a dedicated test catalog.
 */
@Timeout(20)
class JdbcSegmentStoreIntegrationTest {
    private DataSource source;
    private JdbcOptions options;

    @BeforeEach
    void prepareDedicatedDatabase() throws Exception {
        String url = System.getenv("SHORTLINK_ID_TEST_JDBC_URL");
        String catalog = System.getenv("SHORTLINK_ID_TEST_CATALOG");
        assertTrue(url != null && catalog != null, "Dedicated MySQL test environment not supplied");
        assertEquals(
                "true",
                System.getenv("SHORTLINK_ID_TEST_ALLOW_RESET"),
                "Explicit test-table reset permission required");
        assertTrue(
                catalog.startsWith("shortlink_id_test"),
                "Only shortlink_id_test* catalogs may be reset");
        source =
                new DriverSource(
                        url,
                        System.getenv("SHORTLINK_ID_TEST_USER"),
                        System.getenv("SHORTLINK_ID_TEST_PASSWORD"));
        options = new JdbcOptions(catalog, Duration.ofSeconds(1), Duration.ofSeconds(3), 1);
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            assertEquals(catalog, connection.getCatalog());
            statement.execute("DROP TABLE IF EXISTS t_id_alloc");
            statement.execute(
                    "CREATE TABLE t_id_alloc (biz_tag VARCHAR(64) CHARACTER SET ascii COLLATE"
                        + " ascii_bin NOT NULL PRIMARY KEY, max_id BIGINT NOT NULL, step INT NOT"
                        + " NULL, update_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3))"
                        + " ENGINE=InnoDB");
            statement.execute(
                    "INSERT INTO t_id_alloc(biz_tag,max_id,step) VALUES"
                            + " ('shortlink_global',1,100000)");
        }
    }

    @Test
    void concurrentIndependentStoresReserveNonOverlappingIntervals() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<IdRange>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int size = 17 + i;
                JdbcSegmentStore store = new JdbcSegmentStore(source, options);
                pending.add(executor.submit(() -> store.reserve(size)));
            }
            List<IdRange> ranges = new ArrayList<>();
            for (Future<IdRange> future : pending) ranges.add(future.get(10, TimeUnit.SECONDS));
            ranges.sort(Comparator.comparingLong(IdRange::startInclusive));
            assertEquals(1, ranges.get(0).startInclusive());
            for (int i = 1; i < ranges.size(); i++)
                assertEquals(ranges.get(i - 1).endExclusive(), ranges.get(i).startInclusive());
            assertEquals(1 + ranges.stream().mapToLong(IdRange::size).sum(), highWater());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsActualTailAndExhaustionDoesNotMoveHighWater() throws Exception {
        execute("UPDATE t_id_alloc SET max_id = " + (IdRange.ID_LIMIT - 3));
        JdbcSegmentStore store = new JdbcSegmentStore(source, options);
        assertEquals(new IdRange(IdRange.ID_LIMIT - 3, IdRange.ID_LIMIT), store.reserve(100_000));
        assertEquals(
                SPACE_EXHAUSTED,
                assertThrows(IdGenerationException.class, () -> store.reserve(1)).reason());
        assertEquals(IdRange.ID_LIMIT, highWater());
    }

    @Test
    void commitFailureBeforeServerCommitRollsBackAndNeverReturnsCandidate() throws Exception {
        JdbcSegmentStore failing = new JdbcSegmentStore(commitFailure(false), options);
        assertEquals(
                COMMIT_UNKNOWN,
                assertThrows(IdGenerationException.class, () -> failing.reserve(10)).reason());
        assertEquals(1, highWater());
        assertEquals(new IdRange(1, 11), new JdbcSegmentStore(source, options).reserve(10));
    }

    @Test
    void lostCommitAckBurnsCommittedRangeAndNextAllocationStartsAboveIt() throws Exception {
        JdbcSegmentStore failing = new JdbcSegmentStore(commitFailure(true), options);
        assertEquals(
                COMMIT_UNKNOWN,
                assertThrows(IdGenerationException.class, () -> failing.reserve(10)).reason());
        assertEquals(11, highWater());
        assertEquals(new IdRange(11, 21), new JdbcSegmentStore(source, options).reserve(10));
    }

    @Test
    void businessRollbackDoesNotUndoIndependentAllocation() throws Exception {
        try (Connection outer = source.getConnection();
                Statement statement = outer.createStatement()) {
            statement.execute(
                    "CREATE TEMPORARY TABLE id_generator_outer_tx_probe (id BIGINT) ENGINE=InnoDB");
            outer.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO id_generator_outer_tx_probe VALUES (1)");
            assertEquals(new IdRange(1, 11), new JdbcSegmentStore(source, options).reserve(10));
            outer.rollback();
            try (ResultSet result =
                    statement.executeQuery("SELECT COUNT(*) FROM id_generator_outer_tx_probe")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
        assertEquals(11, highWater());
    }

    @Test
    void rowLockWaitIsBoundedAndDoesNotAdvanceOnFailure() throws Exception {
        try (Connection owner = source.getConnection();
                Statement lock = owner.createStatement()) {
            owner.setAutoCommit(false);
            try (ResultSet result =
                    lock.executeQuery(
                            "SELECT max_id FROM t_id_alloc WHERE biz_tag='shortlink_global' FOR"
                                    + " UPDATE")) {
                assertTrue(result.next());
                long before = System.nanoTime();
                IdGenerationException failed =
                        assertThrows(
                                IdGenerationException.class,
                                () -> new JdbcSegmentStore(source, options).reserve(10));
                assertEquals(DATABASE, failed.reason());
                assertTrue(System.nanoTime() - before < Duration.ofSeconds(5).toNanos());
            }
            owner.rollback();
        }
        assertEquals(1, highWater());
    }

    @Test
    void invalidCatalogNamespaceAndEngineAreRejectedWithoutRepair() throws Exception {
        JdbcOptions wrong =
                new JdbcOptions("wrong_catalog", Duration.ofSeconds(1), Duration.ofSeconds(3), 1);
        assertEquals(
                CONFIGURATION,
                assertThrows(
                                IdGenerationException.class,
                                () -> new JdbcSegmentStore(source, wrong).reserve(10))
                        .reason());
        execute("DELETE FROM t_id_alloc");
        execute("INSERT INTO t_id_alloc(biz_tag,max_id,step) VALUES ('another_tenant',1,100)");
        assertEquals(
                CONFIGURATION,
                assertThrows(
                                IdGenerationException.class,
                                () -> new JdbcSegmentStore(source, options).reserve(10))
                        .reason());
        execute("ALTER TABLE t_id_alloc ENGINE=MyISAM");
        assertEquals(
                CONFIGURATION,
                assertThrows(
                                IdGenerationException.class,
                                () -> new JdbcSegmentStore(source, options).reserve(10))
                        .reason());
    }

    @Test
    void generatorWithMysqlSupportsMixedRangeAndSingleAcrossRestart() {
        long last;
        try (SegmentIdGenerator generator =
                new SegmentIdGenerator(
                        new JdbcSegmentStore(source, options),
                        SegmentIdGeneratorTest.options(30))) {
            assertEquals(50, generator.reserveRanges(50).stream().mapToLong(IdRange::size).sum());
            last = generator.nextId();
            assertEquals(51, last);
        }
        try (SegmentIdGenerator restarted =
                new SegmentIdGenerator(
                        new JdbcSegmentStore(source, options),
                        SegmentIdGeneratorTest.options(30))) {
            assertTrue(restarted.nextId() > last);
        }
    }

    private DataSource commitFailure(boolean commitOnServer) {
        AtomicBoolean inject = new AtomicBoolean(true);
        return new DelegatingSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = source.getConnection();
                return (Connection)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Connection.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("commit")
                                            && inject.getAndSet(false)) {
                                        if (commitOnServer) connection.commit();
                                        throw new SQLException(
                                                "injected commit acknowledgment loss", "08006");
                                    }
                                    try {
                                        return method.invoke(connection, args);
                                    } catch (InvocationTargetException failed) {
                                        throw failed.getCause();
                                    }
                                });
            }
        };
    }

    private long highWater() throws Exception {
        try (Connection connection = source.getConnection();
                Statement query = connection.createStatement();
                ResultSet result =
                        query.executeQuery(
                                "SELECT max_id FROM t_id_alloc WHERE biz_tag='shortlink_global'")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record DriverSource(String url, String user, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private static class DelegatingSource implements DataSource {
        private final DataSource target;

        DelegatingSource(DataSource target) {
            this.target = target;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return target.getConnection();
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return target.getConnection(user, password);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return target.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            target.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            target.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return target.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return target.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return target.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return target.isWrapperFor(iface);
        }
    }
}
