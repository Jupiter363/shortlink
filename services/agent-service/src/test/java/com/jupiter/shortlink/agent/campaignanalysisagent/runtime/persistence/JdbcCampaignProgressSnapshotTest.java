package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcCampaignProgressSnapshotTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void currentRevisionAndCancelledRunRemainReadableWithoutChangingAnyLedgerOrLoadingPayloads() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken original = createRun(runs, "run-current");
        StepSpec oldSpec = outputStep("old-summary");
        steps.initialize(original, List.of(oldSpec));
        String artifactId = publishArtifact(runs, original, oldSpec.stepId());
        finishWithOutput(steps, original, oldSpec.stepId(), artifactId);

        RunToken current = runs.revise(original, 2, "{\"revision\":2,\"scopeRef\":\"scope-frozen\"}");
        StepSpec summary = outputStep("current-summary");
        StepSpec following = new StepSpec("current-next", "{\"executorVersion\":\"next/1\"}",
                List.of(summary.stepId()), Set.of(), Set.of());
        steps.initialize(current, List.of(summary, following));
        finishWithOutput(steps, current, summary.stepId(), artifactId);
        List<StepRecord> expectedSteps = steps.steps(current);
        var before = fixture.databaseState();

        ProgressSnapshot snapshot = fixture.guardedSnapshot(OWNER, current.definition().runId());

        assertEquals(current, snapshot.run().token(), "Snapshot reads must not acquire or rotate the writer token");
        assertEquals(RunStatus.ACTIVE, snapshot.run().status());
        assertEquals(2, snapshot.run().definition().revision());
        assertEquals(expectedSteps, snapshot.steps());
        assertEquals(List.of("current-summary", "current-next"),
                snapshot.steps().stream().map(step -> step.spec().stepId()).toList());
        assertEquals(StepStatus.SUCCEEDED, snapshot.steps().get(0).status());
        assertEquals(Map.of("summary", artifactId), snapshot.steps().get(0).outputs());
        assertEquals(StepStatus.PENDING, snapshot.steps().get(1).status());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.steps().clear());
        assertEquals(before, fixture.databaseState(), "Every table and persisted version must remain unchanged");

        runs.cancel(current);
        RunRecord cancelledRun = runs.loadRun(OWNER, current.definition().runId()).orElseThrow();
        var cancelledBefore = fixture.databaseState();
        ProgressSnapshot cancelled = fixture.guardedSnapshot(OWNER, current.definition().runId());
        assertEquals(cancelledRun, cancelled.run());
        assertEquals(RunStatus.CANCELLED, cancelled.run().status());
        assertEquals(expectedSteps, cancelled.steps());
        assertEquals(cancelledBefore, fixture.databaseState());
        assertFalse(fixture.dataSource().auditedSql.isEmpty());
    }

    @Test
    void progressReadRejectsEachForeignIdentityAndReportsAnUnknownRun() {
        Fixture fixture = fixture();
        RunToken run = createRun(fixture.runs(), "run-authorized");
        fixture.steps().initialize(run, List.of(simpleStep("only-step")));
        var before = fixture.databaseState();
        for (Caller foreign : List.of(new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class,
                    () -> fixture.guardedSnapshot(foreign, run.definition().runId()));
        }
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> fixture.guardedSnapshot(OWNER, "run-missing"));
        assertEquals("RUN_NOT_FOUND", missing.getMessage());
        assertEquals(before, fixture.databaseState());
        assertEquals(run, fixture.guardedSnapshot(OWNER, run.definition().runId()).run().token());
    }

    @Test
    void revisionCommittedDuringALockedReadCannotMixNewRunWithOldSteps() throws Exception {
        Fixture fixture = fixture();
        RunToken original = createRun(fixture.runs(), "run-race");
        fixture.steps().initialize(original, List.of(simpleStep("old-step")));
        CountDownLatch revisionStaged = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch snapshotReadStarted = new CountDownLatch(1);
        fixture.dataSource().snapshotReadStarted = snapshotReadStarted;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<RunToken> writer = workers.submit(() -> fixture.transactions().execute(status -> {
                RunToken revised = fixture.runs().revise(original, 2, "{\"revision\":2}");
                fixture.steps().initialize(revised, List.of(simpleStep("new-step")));
                revisionStaged.countDown();
                awaitLatch(releaseWriter, "Writer was not released");
                return revised;
            }));
            assertTrue(revisionStaged.await(5, TimeUnit.SECONDS));
            Future<ReadOutcome> reader = workers.submit(() -> {
                try {
                    return new ReadOutcome(fixture.guardedSnapshot(OWNER, original.definition().runId()), null);
                } catch (IllegalStateException conflict) {
                    return new ReadOutcome(null, conflict);
                }
            });
            assertTrue(snapshotReadStarted.await(5, TimeUnit.SECONDS));
            releaseWriter.countDown();
            RunToken revised = writer.get(5, TimeUnit.SECONDS);
            ReadOutcome result = reader.get(5, TimeUnit.SECONDS);
            if (result.conflict() != null) {
                assertEquals("PROGRESS_SNAPSHOT_CHANGED", result.conflict().getMessage());
                assertNull(result.snapshot(), "A concurrent-change failure cannot expose a partial snapshot");
            } else {
                assertNotNull(result.snapshot());
                assertEquals(revised, result.snapshot().run().token());
                assertEquals(List.of("new-step"), result.snapshot().steps().stream()
                        .map(step -> step.spec().stepId()).toList());
            }
            var committed = fixture.databaseState();
            ProgressSnapshot stable = fixture.guardedSnapshot(OWNER, original.definition().runId());
            assertEquals(revised, stable.run().token());
            assertEquals(2, stable.run().definition().revision());
            assertEquals(List.of("new-step"), stable.steps().stream().map(step -> step.spec().stepId()).toList());
            assertEquals(committed, fixture.databaseState());
        } finally {
            releaseWriter.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static RunToken createRun(CampaignRunStore runs, String runId) {
        return runs.createRun(new RunDefinition(OWNER, "session-" + runId, runId, "plan-" + runId, 1,
                "{\"revision\":1,\"scopeRef\":\"scope-frozen\"}"));
    }

    private static StepSpec simpleStep(String stepId) {
        return new StepSpec(stepId, "{\"executorVersion\":\"stats/1\"}", List.of(), Set.of(), Set.of());
    }

    private static StepSpec outputStep(String stepId) {
        return new StepSpec(stepId, "{\"executorVersion\":\"summary/1\"}",
                List.of(), Set.of("summary"), Set.of("summary"));
    }

    private static String publishArtifact(CampaignRunStore runs, RunToken run, String stepId) {
        runs.prepareAction(run, new ActionSpec("action-source", stepId, "TOOL", "campaign_stats", "stats/1", "{}"));
        runs.prepareChild(run, new ChildSpec("child-source", "action-source", ChildMode.SYNC,
                "request-source", new WireRequest("POST", "/analytics/query", "{}")));
        DispatchPermit permit = runs.beginDispatch(run, "child-source");
        try {
            return runs.publishReady(permit, new ArtifactDraft("artifact-source", "CAMPAIGN_STATS", "stats/1",
                    "scope-frozen", "periods-frozen", "{\"complete\":true}", "{\"snapshotId\":\"snapshot-fixed\"}",
                    NOW.plusSeconds(3600), "{\"pv\":42}")).artifactId();
        } finally {
            runs.callbackExited(permit);
        }
    }

    private static void finishWithOutput(CampaignStepStore steps, RunToken run, String stepId, String artifactId) {
        StepPermit permit = steps.beginStep(run, stepId);
        try {
            steps.settle(permit, StepStatus.SUCCEEDED, Map.of("summary", artifactId), null, ALLOW);
        } finally {
            steps.callbackExited(permit);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(failureMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failureMessage, interrupted);
        }
    }

    private static Fixture fixture() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_snapshot_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        delegate.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"))
                .execute(delegate);
        ReadAuditDataSource guarded = new ReadAuditDataSource(delegate);
        return new Fixture(new JdbcTemplate(guarded),
                new TransactionTemplate(new DataSourceTransactionManager(guarded)), guarded);
    }

    private record ReadOutcome(ProgressSnapshot snapshot, IllegalStateException conflict) {}

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, ReadAuditDataSource dataSource) {
        private CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        private CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, transactions, CLOCK); }
        private ProgressSnapshot guardedSnapshot(Caller caller, String runId) {
            return dataSource.auditRead(() -> steps().snapshot(caller, runId));
        }

        private Map<String, List<Map<String, String>>> databaseState() {
            Map<String, List<Map<String, String>>> state = new LinkedHashMap<>();
            Map<String, String> orders = Map.of(
                    "campaign_run_ledger", "run_id,revision",
                    "campaign_action_ledger", "run_id,revision,action_id",
                    "campaign_child_ledger", "run_id,revision,child_id",
                    "campaign_step_ledger", "run_id,revision,ordinal_index",
                    "campaign_artifact", "artifact_id",
                    "campaign_artifact_payload", "artifact_id");
            orders.forEach((table, order) -> state.put(table, jdbc.query("SELECT * FROM " + table + " ORDER BY " + order,
                    (rs, row) -> {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                            values.put(rs.getMetaData().getColumnLabel(column), rs.getString(column));
                        }
                        return values;
                    })));
            return state;
        }
    }

    /** Audit only the snapshot thread, including plain Statements, while allowing its transaction locks. */
    private static final class ReadAuditDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final ThreadLocal<Boolean> auditing = ThreadLocal.withInitial(() -> false);
        private final List<String> auditedSql = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch snapshotReadStarted;

        private ReadAuditDataSource(DataSource delegate) { this.delegate = delegate; }

        private <T> T auditRead(Supplier<T> read) {
            auditing.set(true);
            try {
                return read.get();
            } finally {
                auditing.remove();
            }
        }

        @Override
        public Connection getConnection() throws SQLException { return guard(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return guard(delegate.getConnection(username, password));
        }

        private void auditSql(Object[] arguments) {
            if (!auditing.get() || arguments == null || arguments.length == 0
                    || !(arguments[0] instanceof String sql)) return;
            String normalized = sql.stripLeading().toLowerCase(Locale.ROOT);
            assertTrue(normalized.startsWith("select"), "Progress snapshot executed a non-SELECT statement: " + sql);
            assertFalse(normalized.contains("campaign_artifact_payload"), "Progress snapshot loaded an artifact payload");
            auditedSql.add(sql);
            CountDownLatch started = snapshotReadStarted;
            if (started != null && normalized.contains("campaign_run_ledger")) started.countDown();
        }

        private Connection guard(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) {
                            auditSql(args);
                        }
                        try {
                            Object result = method.invoke(connection, args);
                            return method.getName().equals("createStatement") ? guard((Statement) result) : result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }

        private Statement guard(Statement statement) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        auditSql(args);
                        try {
                            return method.invoke(statement, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
