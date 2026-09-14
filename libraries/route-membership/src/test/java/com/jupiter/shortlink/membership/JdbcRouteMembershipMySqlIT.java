package com.jupiter.shortlink.membership;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jupiter.shortlink.membership.MembershipFixture.address;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in against a disposable database on port 23306, never the running dev database. */
@EnabledIfEnvironmentVariable(named = "MEMBERSHIP_IT_JDBC_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcRouteMembershipMySqlIT {
    private static final String CATALOG = "shortlink_bloom15_it";
    private MembershipFixture f;
    private DriverManagerDataSource source;
    private String url;
    private String username;
    private String password;
    private String readerName;
    private String readerPassword;
    private Path migration;

    @BeforeAll void isolatedSchema() throws Exception {
        url = required("MEMBERSHIP_IT_JDBC_URL");
        username = required("MEMBERSHIP_IT_USER");
        password = required("MEMBERSHIP_IT_PASSWORD");
        assertEquals(CATALOG, required("MEMBERSHIP_IT_ALLOW_RESET"), "Explicit isolated catalog confirmation is required");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:23306/shortlink_bloom15_it(?:\\?.*)?"),
                "This integration suite only accepts the dedicated 23306 disposable database");
        source = new DriverManagerDataSource(url, username, password);
        try (Connection connection = source.getConnection()) {
            assertEquals(CATALOG, connection.getCatalog());
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        f = new MembershipFixture(source);
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("deploy/mysql/004-route-membership.sql")))
            cursor = cursor.getParent();
        assertNotNull(cursor, "Cannot locate the reviewed membership migration");
        migration = cursor.resolve("deploy/mysql/004-route-membership.sql");
        applyMigration();
        f.jdbc.execute("CREATE TABLE IF NOT EXISTS t_link_route (link_id BIGINT PRIMARY KEY,"
                + "domain_norm VARCHAR(253) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                + "short_uri VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "route_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',UNIQUE(domain_norm,short_uri)) ENGINE=InnoDB");
        f.jdbc.execute("CREATE TABLE IF NOT EXISTS test_outbox(revision BIGINT PRIMARY KEY) ENGINE=InnoDB");
        readerName = "bloom_read_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        readerPassword = UUID.randomUUID().toString();
        executePrivilegeSql("CREATE USER '" + readerName + "'@'%' IDENTIFIED BY '" + readerPassword + "'");
        executePrivilegeSql("GRANT SELECT ON " + CATALOG + ".t_route_membership TO '" + readerName + "'@'%'");
        executePrivilegeSql("GRANT SELECT ON " + CATALOG + ".t_route_membership_control TO '" + readerName + "'@'%'");
    }

    @BeforeEach void resetOnlyConfirmedIsolatedCatalog() { f.reset(); }

    @AfterAll void removeTemporaryReader() throws Exception {
        if (readerName != null) executePrivilegeSql("DROP USER IF EXISTS '" + readerName + "'@'%'");
    }

    @Test void selectOnlyRedirectAccountCanLockControlReadRegistryAndAcquireLease() {
        f.enforceEmpty();
        f.store.register(List.of(address("a")));
        DriverManagerDataSource limited = new DriverManagerDataSource(url, readerName, readerPassword);
        JdbcTemplate readerJdbc = new JdbcTemplate(limited);
        JdbcRouteMembershipStore reader = new JdbcRouteMembershipStore(readerJdbc,
                new DataSourceTransactionManager(limited), f.clock::get);
        ControlSnapshot cut = reader.readControl();
        assertTrue(reader.readPage(cut, 0, 500).complete());
        assertEquals(address("a"), reader.readPage(cut, 0, 500).entries().get(0).address());
        assertTrue(reader.acquireLease(cut.cut()).orElseThrow().isValid());
        assertThrows(DataAccessException.class, () -> readerJdbc.update(
                "UPDATE t_route_membership_control SET mode='OFF' WHERE namespace='routes'"));
    }

    @Test void concurrentReadOnlyLeaseGrantsShareTheControlLockWhileRegistrationWaits() throws Exception {
        f.enforceEmpty();
        AppliedCut initial = f.store.readControl().cut();
        CountDownLatch sharedLockHeld = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        DriverManagerDataSource reader = new DriverManagerDataSource(url, readerName, readerPassword);
        AtomicBoolean interceptOnce = new AtomicBoolean();
        DelegatingDataSource intercepted = new DelegatingDataSource(reader) {
            @Override public Connection getConnection() throws SQLException {
                return holdFirstSharedLock(super.getConnection(), sharedLockHeld, releaseRead, interceptOnce);
            }
        };
        JdbcRouteMembershipStore first = new JdbcRouteMembershipStore(new JdbcTemplate(intercepted),
                new DataSourceTransactionManager(intercepted), f.clock::get);
        JdbcRouteMembershipStore second = new JdbcRouteMembershipStore(new JdbcTemplate(reader),
                new DataSourceTransactionManager(reader), f.clock::get);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CompletableFuture<Boolean> heldLease = CompletableFuture.supplyAsync(() -> first.acquireLease(initial).isPresent(), pool);
            assertTrue(sharedLockHeld.await(2, TimeUnit.SECONDS), "The API did not acquire the expected shared lock");
            CompletableFuture<Boolean> parallelLease = CompletableFuture.supplyAsync(() -> second.acquireLease(initial).isPresent(), pool);
            assertTrue(parallelLease.get(1, TimeUnit.SECONDS), "A read-only lease should coexist with another reader");
            CompletableFuture<RegistrationPermit> writing = CompletableFuture.supplyAsync(() ->
                    f.store.register(List.of(address("waitsForSharedReaders"))), pool);
            assertThrows(TimeoutException.class, () -> writing.get(100, TimeUnit.MILLISECONDS));
            releaseRead.countDown();
            assertTrue(heldLease.get(3, TimeUnit.SECONDS));
            RegistrationPermit registered = writing.get(3, TimeUnit.SECONDS);
            assertEquals(1, registered.cut().memberCount());
            assertTrue(second.acquireLease(initial).isEmpty());
        } finally { releaseRead.countDown(); pool.shutdownNow(); }
    }

    @Test void registrationAndLeaseGrantSerializeOnTheSameMysqlControlRow() throws Exception {
        f.enforceEmpty();
        AppliedCut oldCut = f.store.readControl().cut();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<RegistrationPermit> register = CompletableFuture.supplyAsync(() -> f.store.register(
                    List.of(address("publishedLater")), notice -> { locked.countDown(); await(release); }), pool);
            assertTrue(locked.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> lease = CompletableFuture.supplyAsync(() -> f.store.acquireLease(oldCut).isPresent(), pool);
            assertThrows(TimeoutException.class, () -> lease.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            register.get(3, TimeUnit.SECONDS);
            assertFalse(lease.get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void publicationFenceRemainsLockedUntilMysqlBusinessCommit() throws Exception {
        RegistrationPermit permit = f.store.register(List.of(address("route")));
        f.advanceGrace();
        CountDownLatch verified = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> publish = CompletableFuture.runAsync(() -> f.business.executeWithoutResult(status -> {
                f.store.verifyForPublication(permit, List.of(address("route")));
                verified.countDown(); await(release);
                f.jdbc.update("INSERT INTO t_link_route(link_id,domain_norm,short_uri) VALUES (1,'s.example','route')");
            }), pool);
            assertTrue(verified.await(2, TimeUnit.SECONDS));
            CompletableFuture<DrainPermit> drain = CompletableFuture.supplyAsync(f.store::beginDrain, pool);
            assertThrows(TimeoutException.class, () -> drain.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            publish.get(3, TimeUnit.SECONDS);
            drain.get(3, TimeUnit.SECONDS);
            assertEquals(Mode.DRAINING, f.store.readControl().mode());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM t_link_route", Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void migrationRerunPreservesGenerationControlAndRegisteredAddresses() throws Exception {
        f.enforceEmpty();
        f.store.register(List.of(address("persisted")));
        ControlSnapshot before = f.store.readControl();
        applyMigration();
        applyMigration();
        assertEquals(before, f.store.readControl());
        assertEquals(address("persisted"), f.store.readPage(before, 0, 100).entries().get(0).address());
    }

    @Test void mysqlAddressCollationPreservesShortCodeCase() {
        f.store.register(List.of(address("AbCd"), address("abcd")));
        assertEquals(2, f.store.readControl().memberCount());
        assertEquals(2, f.store.readPage(f.store.readControl(), 0, 10).entries().size());
    }

    @Test void observerFailureRollsBackRegistrationAndItsOutboxInMysql() {
        assertThrows(IllegalStateException.class, () -> f.store.register(List.of(address("aborted")), notice -> {
            f.jdbc.update("INSERT INTO test_outbox VALUES (?)", notice.revision());
            throw new IllegalStateException("injected failure");
        }));
        assertEquals(0, f.store.readControl().memberCount());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM test_outbox", Integer.class));
        f.store.register(List.of(address("retry")));
        assertEquals(1, f.store.readPage(f.store.readControl(), 0, 100).entries().get(0).ordinal());
    }

    @Test void actualAdminStatusConnectsWithoutUnsupportedDatasourceApiAndConfirmsCatalog() throws Exception {
        Map<String, String> environment = Map.of("MEMBERSHIP_DB_URL", url,
                "MEMBERSHIP_DB_USER", username, "MEMBERSHIP_DB_PASSWORD", password);
        MembershipAdminMain.run(new String[]{"status", "--confirm-catalog", CATALOG}, environment);
        assertThrows(IllegalArgumentException.class, () -> MembershipAdminMain.run(
                new String[]{"status", "--confirm-catalog", "wrong_catalog"}, environment));
        assertEquals(Mode.OFF, f.store.readControl().mode());
    }

    private void applyMigration() throws Exception {
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
        }
    }

    private void executePrivilegeSql(String sql) throws Exception {
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            // A driver error can echo CREATE USER credentials. Keep disposable credentials out of logs.
            throw new IllegalStateException("Cannot manage the isolated test reader account");
        }
    }

    private static Connection holdFirstSharedLock(Connection delegate, CountDownLatch locked,
                                                   CountDownLatch release, AtomicBoolean intercepted) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class},
                (proxy, method, arguments) -> {
                    Object result;
                    try { result = method.invoke(delegate, arguments); }
                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    if (method.getName().equals("prepareStatement") && arguments != null
                            && arguments[0] instanceof String sql && sql.endsWith(" FOR SHARE")
                            && sql.contains("t_route_membership_control")) {
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArguments) -> {
                                    Object statementResult;
                                    try { statementResult = statementMethod.invoke(statement, statementArguments); }
                                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                                    if (statementMethod.getName().equals("executeQuery") && intercepted.compareAndSet(false, true)) {
                                        locked.countDown(); await(release);
                                    }
                                    return statementResult;
                                });
                    }
                    return result;
                });
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing integration setting " + name);
        return value;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }
}
