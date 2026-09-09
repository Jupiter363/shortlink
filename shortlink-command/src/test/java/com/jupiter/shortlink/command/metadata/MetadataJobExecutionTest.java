package com.jupiter.shortlink.command.metadata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

/**
 * Real H2 transactions exercise rollback and the actual conditional UPDATE predicates. Only the
 * MySQL clock expression is replaced by a controlled database-time literal. Real MySQL clock and
 * ShardingSphere compatibility are covered by BatchMetadataIntegrationTest, not claimed here.
 */
@Timeout(10)
class MetadataJobExecutionTest {
    private DbClockJdbc jdbc;
    private CommitManager manager;
    private MetadataJobService jobs;
    private SimpleMeterRegistry registry;
    private GroupCommandService groups;
    private final AtomicLong databaseTime = new AtomicLong(1_000_000);

    @BeforeEach
    void fixture() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:metadata-execution-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new DbClockJdbc(source, databaseTime);
        jdbc.execute("CREATE TABLE t_account_identity(id BIGINT PRIMARY KEY,username VARCHAR(64))");
        jdbc.execute(
                "CREATE TABLE t_user(id BIGINT PRIMARY KEY,username VARCHAR(64),auth_version"
                    + " BIGINT,disabled INT,del_flag INT)");
        jdbc.execute(
                "CREATE TABLE t_group(tenant_id BIGINT,gid VARCHAR(64),PRIMARY"
                    + " KEY(tenant_id,gid))");
        jdbc.execute(
                "CREATE TABLE t_link_route(link_id BIGINT PRIMARY KEY,tenant_id BIGINT,current_gid"
                    + " VARCHAR(64),route_status VARCHAR(16),target_revision BIGINT,origin_url"
                    + " VARCHAR(2048),metadata_status VARCHAR(16))");
        jdbc.execute(
                "CREATE TABLE t_link(id BIGINT PRIMARY KEY,tenant_id BIGINT,gid"
                    + " VARCHAR(64),target_revision BIGINT,del_flag INT,metadata_status"
                    + " VARCHAR(16),title VARCHAR(512),favicon VARCHAR(2048),update_time"
                    + " TIMESTAMP)");
        jdbc.execute(
                "CREATE TABLE t_metadata_job(job_id VARCHAR(36) PRIMARY KEY,tenant_id"
                    + " BIGINT,link_id BIGINT,target_revision BIGINT,url_digest"
                    + " VARCHAR(64),origin_url VARCHAR(2048),state VARCHAR(16),attempts INT,fence"
                    + " BIGINT,lease_owner VARCHAR(64),lease_until BIGINT,next_attempt_at"
                    + " BIGINT,last_error VARCHAR(512),created_at BIGINT,updated_at BIGINT)");
        jdbc.update("INSERT INTO t_account_identity VALUES(1,'owner')");
        jdbc.update("INSERT INTO t_user VALUES(1,'owner',1,0,0)");
        jdbc.update("INSERT INTO t_group VALUES(1,'gA')");
        jdbc.update(
                "INSERT INTO t_link_route"
                    + " VALUES(1,1,'gA','ACTIVE',1,'https://example.org/','PENDING')");
        jdbc.update("INSERT INTO t_link VALUES(1,1,'gA',1,0,'PENDING',NULL,NULL,NULL)");
        jdbc.update(
                "INSERT INTO t_metadata_job"
                    + " VALUES('job',1,1,1,?,'https://example.org/','READY',0,0,NULL,0,0,NULL,0,0)",
                "d".repeat(64));
        manager = new CommitManager(source);
        registry = new SimpleMeterRegistry();
        var links = mock(LinkCommandService.class);
        when(links.digest(any())).thenReturn("d".repeat(64));
        groups = mock(GroupCommandService.class);
        doAnswer(
                        call -> {
                            String gid = call.getArgument(1);
                            jdbc.queryForMap(
                                    "SELECT gid FROM t_group WHERE tenant_id=? AND gid=? FOR"
                                        + " UPDATE",
                                    1,
                                    gid);
                            return null;
                        })
                .when(groups)
                .lockActive(any(), anyString());
        var json = new ObjectMapper();
        // Far ahead of the database on purpose: execution leases must never use this JVM clock.
        Clock jvmClock = Clock.fixed(Instant.ofEpochMilli(9_000_000_000L), ZoneOffset.UTC);
        jobs =
                new MetadataJobService(
                        jdbc,
                        manager,
                        json,
                        links,
                        groups,
                        new BusinessOutbox(jdbc, json, jvmClock),
                        jvmClock,
                        registry);
        jdbc.calls.clear();
    }

    private MetadataJobService.Lease claim() {
        return Objects.requireNonNull(jobs.claim("job", "worker"));
    }

    private void complete(MetadataJobService.Lease lease) {
        jobs.complete(lease, new SafeMetadataFetcher.Metadata("fresh title", null));
    }

    private String state(String table) {
        return jdbc.queryForObject(
                "SELECT "
                        + (table.equals("t_metadata_job") ? "state" : "metadata_status")
                        + " FROM "
                        + table,
                String.class);
    }

    private long timer(String operation, String outcome) {
        return registry.get("shortlink.metadata.execution.duration")
                .tags("operation", operation, "outcome", outcome)
                .timer()
                .count();
    }

    private void assertUnchanged() {
        assertEquals("PENDING", state("t_link"));
        assertEquals("PENDING", state("t_link_route"));
        assertEquals("RUNNING", state("t_metadata_job"));
    }

    @Test
    void allTwentyFiniteOperationOutcomeTimersAreRegisteredAtZero() {
        assertEquals(20, registry.getMeters().size());
        for (String operation : List.of("candidates", "claim", "apply", "retry"))
            for (String outcome : List.of("success", "empty", "obsolete", "stale", "failed"))
                assertEquals(0, timer(operation, outcome));
        assertTrue(registry.getMeters().stream().allMatch(m -> m.getId().getTags().size() == 2));
    }

    @Test
    void claimUsesTwoSqlCallsAndApplyUsesTenIncludingGroupWithOriginalLockOrder() {
        var lease = claim();
        assertEquals(2, jdbc.calls.size());
        assertTrue(jdbc.calls.get(0).contains("FOR UPDATE"));
        assertTrue(jdbc.calls.get(1).contains("AND next_attempt_at<="));
        assertEquals(1, timer("claim", "success"));
        jdbc.calls.clear();
        complete(lease);
        assertEquals(10, jdbc.calls.size());
        List<String> locks = jdbc.calls.stream().filter(sql -> sql.contains("FOR UPDATE")).toList();
        assertEquals(4, locks.size());
        assertTrue(locks.get(0).contains("FROM t_user "));
        assertTrue(locks.get(1).contains("FROM t_group "));
        assertTrue(locks.get(2).contains("FROM t_link_route "));
        assertTrue(locks.get(3).contains("FROM t_metadata_job "));
        assertEquals(
                1,
                jdbc.calls.stream()
                        .filter(sql -> sql.startsWith("SELECT CAST(UNIX_TIMESTAMP"))
                        .count());
        assertEquals("COMPLETED", state("t_metadata_job"));
        assertEquals("READY", state("t_link"));
        assertEquals(1, timer("apply", "success"));
        assertEquals(2, manager.commits);
        assertEquals(
                List.of(
                        TransactionDefinition.ISOLATION_READ_COMMITTED,
                        TransactionDefinition.ISOLATION_READ_COMMITTED),
                manager.isolations);
    }

    @Test
    void conditionalClaimUsesDatabaseClockForFutureLeaseFutureRetryAndTerminalState() {
        jdbc.update("UPDATE t_metadata_job SET lease_until=?", databaseTime.get() + 1);
        assertNull(jobs.claim("job", "worker"));
        jdbc.update(
                "UPDATE t_metadata_job SET lease_until=0,next_attempt_at=?",
                databaseTime.get() + 1);
        assertNull(jobs.claim("job", "worker"));
        jdbc.update("UPDATE t_metadata_job SET next_attempt_at=0,state='COMPLETED'");
        assertNull(jobs.claim("job", "worker"));
        assertEquals(3, timer("claim", "empty"));
        assertEquals(0, timer("claim", "failed"));
        assertEquals(0, jdbc.queryForObject("SELECT fence FROM t_metadata_job", Long.class));
    }

    @Test
    void claimClockIsTakenByTheUpdateAfterTheRowLockHasBeenAcquired() {
        jdbc.update("UPDATE t_metadata_job SET lease_until=1005000");
        jdbc.afterClaimLock = () -> databaseTime.set(1_010_000);
        assertNotNull(claim());
        assertEquals(
                1_025_000L,
                jdbc.queryForObject("SELECT lease_until FROM t_metadata_job", Long.class));
    }

    @Test
    void oldFenceWrongOwnerAndNonRunningStateCannotWriteBack() {
        var lease = claim();
        jdbc.update("UPDATE t_metadata_job SET fence=fence+1");
        assertThrows(MetadataJobService.StaleLeaseException.class, () -> complete(lease));
        jdbc.update(
                "UPDATE t_metadata_job SET fence=?,lease_owner='another-worker'", lease.fence());
        assertThrows(MetadataJobService.StaleLeaseException.class, () -> complete(lease));
        jdbc.update("UPDATE t_metadata_job SET lease_owner=?,state='READY'", lease.owner());
        assertThrows(MetadataJobService.StaleLeaseException.class, () -> complete(lease));
        assertEquals("PENDING", state("t_link"));
        assertEquals("PENDING", state("t_link_route"));
        assertEquals(3, timer("apply", "stale"));
        assertEquals(0, timer("apply", "failed"));
    }

    @Test
    void expirationDuringResourceWritesRejectsTheFinalUpdateAndRollsBackBothTables() {
        var lease = claim();
        jdbc.beforeFinalUpdate = () -> databaseTime.addAndGet(15_001);
        assertThrows(MetadataJobService.StaleLeaseException.class, () -> complete(lease));
        assertUnchanged();
        assertEquals(1, timer("apply", "stale"));
        assertEquals(0, timer("apply", "success"));
    }

    @Test
    void terminalFetchFailureAlsoRollsBackWhenItsLeaseExpiresAtTheFinalWrite() {
        var lease = claim();
        jdbc.beforeFinalUpdate = () -> databaseTime.addAndGet(15_001);
        jobs.failed(lease, new IOException("METADATA_TARGET_DENIED"));
        assertUnchanged();
        assertEquals(1, timer("apply", "stale"));
        assertEquals(0, timer("apply", "success"));
    }

    @Test
    void changedRevisionIsCommittedAsObsoleteWithoutOverwritingCurrentMetadata() {
        var lease = claim();
        jdbc.update("UPDATE t_link_route SET target_revision=2");
        jdbc.update("UPDATE t_link SET target_revision=2");
        complete(lease);
        assertEquals("OBSOLETE", state("t_metadata_job"));
        assertEquals("PENDING", state("t_link"));
        assertEquals("PENDING", state("t_link_route"));
        assertEquals(1, timer("apply", "obsolete"));
        assertEquals(0, timer("apply", "success"));
    }

    @Test
    void retryUsesThreeSqlCallsAndComputesBackoffFromDatabaseTime() {
        var lease = claim();
        jdbc.calls.clear();
        jobs.failed(lease, new IOException("temporary transport failure"));
        assertEquals(3, jdbc.calls.size());
        assertEquals("READY", state("t_metadata_job"));
        assertEquals(
                databaseTime.get() + 2000,
                jdbc.queryForObject("SELECT next_attempt_at FROM t_metadata_job", Long.class));
        assertEquals(1, timer("retry", "success"));
    }

    @Test
    void retryLeaseExpirationAtItsFinalConditionalUpdateCannotReleaseTheOldLease() {
        var lease = claim();
        jdbc.beforeRetryUpdate = () -> databaseTime.addAndGet(15_001);
        jobs.failed(lease, new IOException("temporary transport failure"));
        assertEquals("RUNNING", state("t_metadata_job"));
        assertEquals(
                0, jdbc.queryForObject("SELECT next_attempt_at FROM t_metadata_job", Long.class));
        assertEquals(1, timer("retry", "stale"));
    }

    @Test
    void failedCommitRollsBackWritesAndNeverRecordsSuccessfulApply() {
        var lease = claim();
        manager.failBefore = true;
        assertThrows(TransactionSystemException.class, () -> complete(lease));
        assertUnchanged();
        assertEquals(1, timer("apply", "failed"));
        assertEquals(0, timer("apply", "success"));
    }

    @Test
    void unknownCommitIsARecordedFailureAndReplayCannotOverwriteTheCommittedResult() {
        var lease = claim();
        manager.failAfter = true;
        assertThrows(TransactionSystemException.class, () -> complete(lease));
        assertEquals("COMPLETED", state("t_metadata_job"));
        assertEquals("READY", state("t_link"));
        assertEquals(1, timer("apply", "failed"));
        assertEquals(0, timer("apply", "success"));
        assertThrows(MetadataJobService.StaleLeaseException.class, () -> complete(lease));
        assertEquals(1, timer("apply", "stale"));
    }

    @Test
    void workerCommitsRemainDurableWhenAnUnrelatedOuterTransactionRollsBack() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        outer -> {
                            complete(claim());
                            assertEquals(1, timer("claim", "success"));
                            assertEquals(1, timer("apply", "success"));
                            outer.setRollbackOnly();
                        });
        assertEquals("COMPLETED", state("t_metadata_job"));
        assertEquals("READY", state("t_link_route"));
        assertEquals(2, manager.commits);
    }

    @Test
    void candidateResultsDistinguishEmptyFromSuccessfulLookup() {
        assertEquals(List.of("job"), jobs.candidates(4));
        jdbc.update("UPDATE t_metadata_job SET state='COMPLETED'");
        assertTrue(jobs.candidates(4).isEmpty());
        assertEquals(1, timer("candidates", "success"));
        assertEquals(1, timer("candidates", "empty"));
    }

    private static final class CommitManager extends DataSourceTransactionManager {
        int commits;
        boolean failBefore, failAfter;
        final List<Integer> isolations = new ArrayList<>();

        CommitManager(DataSource source) {
            super(source);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            isolations.add(definition.getIsolationLevel());
            super.doBegin(transaction, definition);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failBefore) {
                failBefore = false;
                super.doRollback(status);
                throw new TransactionSystemException("Injected commit rejection");
            }
            super.doCommit(status);
            commits++;
            if (failAfter) {
                failAfter = false;
                throw new TransactionSystemException("Injected unknown commit acknowledgement");
            }
        }
    }

    private static final class DbClockJdbc extends JdbcTemplate {
        private static final String CLOCK_SQL =
                "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)";
        final AtomicLong time;
        final List<String> calls = new ArrayList<>();
        Runnable afterClaimLock, beforeFinalUpdate, beforeRetryUpdate;

        DbClockJdbc(DataSource source, AtomicLong time) {
            super(source);
            this.time = time;
        }

        String translated(String sql) {
            return sql.replace(CLOCK_SQL, Long.toString(time.get()));
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            calls.add(sql);
            Map<String, Object> result = super.queryForMap(translated(sql), args);
            if (sql.startsWith("SELECT * FROM t_metadata_job") && afterClaimLock != null) {
                Runnable callback = afterClaimLock;
                afterClaimLock = null;
                callback.run();
            }
            return result;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            calls.add(sql);
            return super.queryForList(translated(sql), args);
        }

        @Override
        public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
            calls.add(sql);
            return super.queryForList(translated(sql), type, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type, Object... args) {
            calls.add(sql);
            return super.queryForObject(translated(sql), type, args);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> type) {
            return queryForObject(sql, type, new Object[0]);
        }

        @Override
        public int update(String sql, Object... args) {
            calls.add(sql);
            if (sql.startsWith("UPDATE t_metadata_job SET state=?,lease_until=0")
                    && beforeFinalUpdate != null) {
                Runnable callback = beforeFinalUpdate;
                beforeFinalUpdate = null;
                callback.run();
            }
            if (sql.startsWith("UPDATE t_metadata_job SET state='READY'")
                    && beforeRetryUpdate != null) {
                Runnable callback = beforeRetryUpdate;
                beforeRetryUpdate = null;
                callback.run();
            }
            return super.update(translated(sql), args);
        }
    }
}
