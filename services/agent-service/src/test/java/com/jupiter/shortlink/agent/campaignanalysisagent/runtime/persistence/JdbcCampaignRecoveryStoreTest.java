package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.Observation;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.State;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(30)
class JdbcCampaignRecoveryStoreTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ProcessIdentity OLD = process(101);
    private static final ProcessIdentity LOCAL = process(202);
    private static final ProcessIdentity COMPETITOR = process(303);
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void missingOwnerLiveOrUnknownOwnerAndUnprovedLocalProcessAllBlockWithoutWrites() {
        Fixture missing = fixture();
        FakeLiveness missingProof = liveProcesses();
        RunToken unowned = createRun(missing.runs(), "run-unowned");
        ActiveAttempt unownedCallback = startAttempt(missing, unowned, ChildMode.SYNC);
        var unownedBefore = missing.databaseState();
        TakeoverResult ownerMissing = missing.recovery(LOCAL, missingProof).recover(unowned);
        assertBlocked(ownerMissing);
        assertEquals("CALLBACK_OWNER_MISSING", ownerMissing.reason());
        assertEquals(unownedBefore, missing.databaseState());
        assertTrue(missing.runs().child(unowned, unownedCallback.child().childId()).orElseThrow().callbackActive());

        for (State oldState : List.of(State.ALIVE, State.UNKNOWN)) {
            Fixture fixture = fixture();
            FakeLiveness proof = liveProcesses();
            RunToken run = claim(fixture, proof, OLD, "run-owner-" + oldState);
            startAttempt(fixture, run, ChildMode.ASYNC);
            proof.states.put(OLD, oldState);
            var before = fixture.databaseState();
            TakeoverResult blocked = fixture.recovery(LOCAL, proof).recover(run);
            assertBlocked(blocked);
            assertEquals(oldState == State.ALIVE ? "CALLBACK_OWNER_ALIVE" : "CALLBACK_OWNER_UNKNOWN", blocked.reason());
            assertEquals(before, fixture.databaseState());
        }
        for (State localState : List.of(State.DEAD, State.UNKNOWN)) {
            Fixture fixture = fixture();
            FakeLiveness proof = liveProcesses();
            RunToken run = claim(fixture, proof, OLD, "run-local-" + localState);
            startAttempt(fixture, run, ChildMode.ASYNC);
            proof.states.put(OLD, State.DEAD);
            proof.states.put(LOCAL, localState);
            var before = fixture.databaseState();
            TakeoverResult blocked = fixture.recovery(LOCAL, proof).recover(run);
            assertBlocked(blocked);
            assertEquals("LOCAL_PROCESS_NOT_ALIVE", blocked.reason());
            assertEquals(before, fixture.databaseState());
        }
    }

    @Test
    void concurrentTakeoversHaveOneWinnerAndAuditOrOwnerInsertFailureRollsBackTheWholeTakeover() throws Exception {
        Fixture concurrent = fixture();
        FakeLiveness proof = liveProcesses();
        RunToken expected = claim(concurrent, proof, OLD, "run-cas");
        startAttempt(concurrent, expected, ChildMode.SYNC);
        proof.states.put(OLD, State.DEAD);
        CountDownLatch contendersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<TakeoverResult> left = workers.submit(() -> recoverAfter(
                    concurrent.recovery(LOCAL, proof), expected, contendersReady, start));
            Future<TakeoverResult> right = workers.submit(() -> recoverAfter(
                    concurrent.recovery(COMPETITOR, proof), expected, contendersReady, start));
            assertTrue(contendersReady.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<TakeoverResult> results = List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(result -> result.outcome() == Outcome.ACQUIRED).count());
            assertEquals(1L, results.stream().filter(result -> result.outcome() == Outcome.BLOCKED).count());
            TakeoverResult winner = results.stream().filter(result -> result.outcome() == Outcome.ACQUIRED)
                    .findFirst().orElseThrow();
            assertEquals(2, winner.recoveredCallbacks());
            assertEquals(expected.version() + 1, winner.token().version());
            assertNotEquals(expected.advanceToken(), winner.token().advanceToken());
            assertEquals(winner.token(), concurrent.runs().loadRun(OWNER, "run-cas").orElseThrow().token());
            assertEquals(2, concurrent.count("campaign_callback_recovery"));
            assertEquals(2, concurrent.count("campaign_run_owner"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }

        for (String failingTable : List.of("campaign_callback_recovery", "campaign_run_owner")) {
            Fixture fixture = fixture();
            FakeLiveness faultProof = liveProcesses();
            RunToken run = claim(fixture, faultProof, OLD, "run-rollback");
            startAttempt(fixture, run, ChildMode.SYNC);
            faultProof.states.put(OLD, State.DEAD);
            CampaignRecoveryStore recovery = fixture.recovery(LOCAL, faultProof);
            var before = fixture.databaseState();
            fixture.dataSource().failInsertTable = failingTable;
            try {
                assertThrows(DataAccessException.class, () -> recovery.recover(run));
                assertEquals(1, fixture.dataSource().injectedFailures.get());
            } finally {
                fixture.dataSource().failInsertTable = null;
            }
            assertEquals(before, fixture.databaseState(), "Cleanup, audit, writer advance and ownership must all roll back");
            int observations = faultProof.observations.get();
            IllegalStateException nested = assertThrows(IllegalStateException.class,
                    () -> fixture.transactions().execute(status -> recovery.recover(run)));
            assertEquals("RECOVERY_REQUIRES_TOP_LEVEL", nested.getMessage());
            assertEquals(observations, faultProof.observations.get(), "An outer transaction must be rejected before probing");
            assertEquals(before, fixture.databaseState());
        }
    }

    @Test
    void provedDeadCallbacksAreClassifiedExactlyWhileReadyWaitingAndUnresolvedEvidenceIsPreserved() {
        for (Scenario scenario : Scenario.values()) {
            Fixture fixture = fixture();
            FakeLiveness proof = liveProcesses();
            RunToken run = claim(fixture, proof, OLD, "run-classification");
            CampaignRunStore runs = fixture.runs();
            CampaignStepStore steps = fixture.steps();
            steps.initialize(run, List.of(outputStep()));
            prepareChild(runs, run, scenario == Scenario.SYNC_DISPATCHING ? ChildMode.SYNC : ChildMode.ASYNC);
            StepPermit step = steps.beginStep(run, "work");
            DispatchPermit child = null;
            if (scenario != Scenario.PREPARED) {
                child = runs.beginDispatch(run, "source");
                if (scenario == Scenario.ASYNC_POLLING) {
                    runs.recordWaiting(child, "job-stable");
                    runs.callbackExited(child);
                    child = runs.beginReconciliation(run, "source");
                } else if (scenario == Scenario.READY) {
                    runs.publishReady(child, artifact());
                    steps.settle(step, StepStatus.SUCCEEDED, Map.of("data", "artifact-stable"), null, ALLOW);
                } else if (scenario == Scenario.WAITING) {
                    runs.recordWaiting(child, "job-stable");
                    steps.settle(step, StepStatus.WAITING, Map.of(), "awaiting-job", ALLOW);
                } else if (scenario == Scenario.UNRESOLVED) {
                    runs.markUnresolved(child);
                }
            }
            ChildRecord beforeChild = runs.child(run, "source").orElseThrow();
            StepRecord beforeStep = steps.step(run, "work").orElseThrow();
            Artifact beforeArtifact = scenario == Scenario.READY
                    ? runs.readArtifact(OWNER, "artifact-stable", ALLOW) : null;
            proof.states.put(OLD, State.DEAD);
            TakeoverResult recovered = fixture.recovery(LOCAL, proof).recover(run);
            assertEquals(Outcome.ACQUIRED, recovered.outcome());
            assertEquals(scenario == Scenario.PREPARED ? 1 : 2, recovered.recoveredCallbacks());
            ChildRecord afterChild = runs.child(recovered.token(), "source").orElseThrow();
            StepRecord afterStep = steps.step(recovered.token(), "work").orElseThrow();
            assertFalse(afterChild.callbackActive());
            assertFalse(afterStep.callbackActive());
            assertEquals(beforeChild.spec(), afterChild.spec());
            assertEquals(beforeChild.spec().wire().hash(), afterChild.spec().wire().hash());
            assertEquals(beforeChild.attemptId(), afterChild.attemptId());
            assertEquals(beforeChild.attemptVersion(), afterChild.attemptVersion());
            assertEquals(beforeChild.jobId(), afterChild.jobId());
            assertEquals(beforeChild.artifactId(), afterChild.artifactId());
            assertEquals(beforeStep.spec(), afterStep.spec());
            assertEquals(beforeStep.attemptId(), afterStep.attemptId());
            assertEquals(beforeStep.outputs(), afterStep.outputs());
            if (beforeChild.state() == ChildState.DISPATCHING) {
                assertEquals(ChildState.UNRESOLVED, afterChild.state());
                assertEquals(switch (scenario) {
                    case SYNC_DISPATCHING -> UnresolvedReason.READ_RESULT_UNKNOWN;
                    case ASYNC_POLLING -> UnresolvedReason.JOB_RESULT_UNKNOWN;
                    default -> UnresolvedReason.SUBMISSION_UNRESOLVED;
                }, afterChild.reason());
            } else {
                assertEquals(beforeChild.state(), afterChild.state());
                assertEquals(beforeChild.reason(), afterChild.reason());
            }
            if (beforeStep.status() == StepStatus.RUNNING) {
                assertEquals(StepStatus.BLOCKED, afterStep.status());
                assertEquals("STEP_RESULT_UNKNOWN", afterStep.reason());
            } else {
                assertEquals(beforeStep.status(), afterStep.status());
                assertEquals(beforeStep.reason(), afterStep.reason());
            }
            if (beforeArtifact != null) assertEquals(beforeArtifact, runs.readArtifact(OWNER, "artifact-stable", ALLOW));
            assertEquals(recovered.recoveredCallbacks(), fixture.count("campaign_callback_recovery"));
            assertEquals(recovered.recoveredCallbacks(), fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM campaign_callback_recovery WHERE owner_instance_id=? AND recovery_instance_id=? AND proof_code=?",
                    Integer.class, OLD.instanceId(), LOCAL.instanceId(), ProcessLiveness.PROCESS_EXITED));
            assertFalse(steps.mayExecute(step));
            if (child != null) assertFalse(runs.mayDispatch(child));
        }
    }

    @Test
    void everyOlderRevisionCallbackNeedsProofAndChangesDuringTheUnlockedProbeInvalidateTakeover() throws Exception {
        Fixture fixture = fixture();
        FakeLiveness proof = liveProcesses();
        RunToken original = claim(fixture, proof, OLD, "run-revisions");
        ActiveAttempt active = startAttempt(fixture, original, ChildMode.ASYNC);
        RunToken revisionTwo = fixture.runs().revise(original, 2, "{\"revision\":2}");
        CampaignRecoveryStore recovery = fixture.recovery(LOCAL, proof);
        var before = fixture.databaseState();
        TakeoverResult stale = recovery.recover(original);
        assertBlocked(stale);
        assertEquals("RUN_CHANGED", stale.reason());
        assertEquals(before, fixture.databaseState());
        proof.states.put(OLD, State.UNKNOWN);
        assertBlocked(recovery.recover(revisionTwo));
        assertEquals(before, fixture.databaseState(), "Callbacks in superseded revisions are part of the proof boundary");

        proof.states.put(OLD, State.DEAD);
        proof.heldIdentity = OLD;
        proof.probeEntered = new CountDownLatch(1);
        proof.releaseProbe = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<TakeoverResult> pending = worker.submit(() -> recovery.recover(revisionTwo));
            assertTrue(proof.probeEntered.await(5, TimeUnit.SECONDS));
            fixture.runs().callbackExited(active.child());
            var afterRealExit = fixture.databaseState();
            proof.releaseProbe.countDown();
            TakeoverResult changed = pending.get(5, TimeUnit.SECONDS);
            assertBlocked(changed);
            assertEquals("CALLBACK_SNAPSHOT_CHANGED", changed.reason());
            assertEquals(afterRealExit, fixture.databaseState(), "Stale proof cannot partly clear the remaining old callback");
        } finally {
            proof.releaseProbe.countDown();
            proof.heldIdentity = null;
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
        TakeoverResult current = recovery.recover(revisionTwo);
        assertEquals(Outcome.ACQUIRED, current.outcome());
        assertEquals(2, current.token().definition().revision());
        assertEquals(1, current.recoveredCallbacks());
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_step_ledger WHERE run_id=? AND callback_active=TRUE", Integer.class, "run-revisions"));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_child_ledger WHERE run_id=? AND callback_active=TRUE", Integer.class, "run-revisions"));
        assertEquals(1, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_callback_recovery WHERE revision=1", Integer.class));
    }

    @Test
    void cancelledCurrentRunCanCleanProvedDeadCallbacksButCannotReopenOrBypassCallerIdentity() {
        Fixture fixture = fixture();
        FakeLiveness proof = liveProcesses();
        RunToken claimed = claim(fixture, proof, OLD, "run-cancelled");
        ActiveAttempt active = startAttempt(fixture, claimed, ChildMode.SYNC);
        fixture.runs().cancel(claimed);
        RunRecord cancelled = fixture.runs().loadRun(OWNER, "run-cancelled").orElseThrow();
        RunToken expected = cancelled.token();
        CampaignRecoveryStore recovery = fixture.recovery(LOCAL, proof);
        proof.states.put(OLD, State.DEAD);
        var before = fixture.databaseState();
        for (Caller foreign : List.of(new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            RunDefinition definition = expected.definition();
            RunToken forged = new RunToken(new RunDefinition(foreign, definition.sessionId(), definition.runId(),
                    definition.planId(), definition.revision(), definition.definitionJson()), expected.version(), expected.advanceToken());
            assertThrows(SecurityException.class, () -> recovery.recover(forged));
            assertEquals(before, fixture.databaseState());
        }
        TakeoverResult oldExpected = recovery.recover(claimed);
        assertBlocked(oldExpected);
        assertEquals("RUN_CHANGED", oldExpected.reason());
        assertEquals(before, fixture.databaseState());
        TakeoverResult stopped = recovery.recover(expected);
        assertEquals(Outcome.STOPPED, stopped.outcome());
        assertNull(stopped.token());
        assertEquals(2, stopped.recoveredCallbacks());
        assertEquals(cancelled, fixture.runs().loadRun(OWNER, "run-cancelled").orElseThrow());
        assertEquals(1, fixture.count("campaign_run_owner"), "Stopping a run must not install a new writer owner");
        assertEquals(2, fixture.count("campaign_callback_recovery"));
        assertFalse(fixture.runs().mayDispatch(active.child()));
        assertFalse(fixture.steps().mayExecute(active.step()));
        assertThrows(IllegalStateException.class, () -> fixture.runs().publishReady(active.child(), artifact()));
        var stoppedState = fixture.databaseState();
        TakeoverResult again = recovery.recover(expected);
        assertEquals(Outcome.STOPPED, again.outcome());
        assertNull(again.token());
        assertEquals(0, again.recoveredCallbacks());
        assertEquals(stoppedState, fixture.databaseState());
    }

    private enum Scenario { PREPARED, SYNC_DISPATCHING, ASYNC_SUBMISSION, ASYNC_POLLING, READY, WAITING, UNRESOLVED }

    private static ProcessIdentity process(int pid) {
        String id = UUID.nameUUIDFromBytes(("process-" + pid).getBytes(StandardCharsets.UTF_8)).toString();
        return new ProcessIdentity(id, "trusted-test-host", pid, NOW.minusSeconds(3600).toEpochMilli() + pid);
    }

    private static FakeLiveness liveProcesses() {
        FakeLiveness proof = new FakeLiveness();
        proof.states.put(OLD, State.ALIVE);
        proof.states.put(LOCAL, State.ALIVE);
        proof.states.put(COMPETITOR, State.ALIVE);
        return proof;
    }

    private static RunToken createRun(CampaignRunStore runs, String runId) {
        return runs.createRun(new RunDefinition(OWNER, "session-" + runId, runId, "plan-" + runId, 1,
                "{\"revision\":1,\"scopeRef\":\"scope-frozen\"}"));
    }

    private static RunToken claim(Fixture fixture, FakeLiveness proof, ProcessIdentity owner, String runId) {
        TakeoverResult claimed = fixture.recovery(owner, proof).recover(createRun(fixture.runs(), runId));
        assertEquals(Outcome.ACQUIRED, claimed.outcome());
        assertEquals(0, claimed.recoveredCallbacks());
        assertNotNull(claimed.token());
        return claimed.token();
    }

    private static ActiveAttempt startAttempt(Fixture fixture, RunToken run, ChildMode mode) {
        fixture.steps().initialize(run, List.of(outputStep()));
        prepareChild(fixture.runs(), run, mode);
        StepPermit step = fixture.steps().beginStep(run, "work");
        DispatchPermit child = fixture.runs().beginDispatch(run, "source");
        return new ActiveAttempt(step, child);
    }

    private static StepSpec outputStep() {
        return new StepSpec("work", "{\"executorVersion\":\"stats/1\"}", List.of(), Set.of("data"), Set.of());
    }

    private static void prepareChild(CampaignRunStore runs, RunToken run, ChildMode mode) {
        runs.prepareAction(run, new ActionSpec("action-source", "work", "TOOL", "campaign_stats", "stats/1", "{}"));
        runs.prepareChild(run, new ChildSpec("source", "action-source", mode, "request-" + run.definition().runId(),
                new WireRequest("POST", "/analytics/query", "{\"scopeRef\":\"scope-frozen\"}")));
    }

    private static ArtifactDraft artifact() {
        return new ArtifactDraft("artifact-stable", "CAMPAIGN_STATS", "stats/1", "scope-frozen", "periods-frozen",
                "{\"complete\":true}", "{\"snapshotId\":\"snapshot-stable\"}", NOW.plusSeconds(3600), "{\"pv\":42}");
    }

    private static void assertBlocked(TakeoverResult result) {
        assertEquals(Outcome.BLOCKED, result.outcome());
        assertNull(result.token());
        assertEquals(0, result.recoveredCallbacks());
        assertNotNull(result.reason());
    }

    private static TakeoverResult recoverAfter(CampaignRecoveryStore recovery, RunToken expected,
                                               CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        awaitLatch(start, "Recovery contenders did not start");
        return recovery.recover(expected);
    }

    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static Fixture fixture() {
        DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_recovery_" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        delegate.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql"))
                .execute(delegate);
        FaultDataSource dataSource = new FaultDataSource(delegate);
        return new Fixture(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), dataSource);
    }

    private record ActiveAttempt(StepPermit step, DispatchPermit child) {}

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, FaultDataSource dataSource) {
        private CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
        private CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, transactions, CLOCK); }
        private CampaignRecoveryStore recovery(ProcessIdentity owner, ProcessLiveness proof) {
            return new JdbcCampaignRecoveryStore(jdbc, transactions, CLOCK, owner, proof);
        }
        private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

        private Map<String, List<Map<String, String>>> databaseState() {
            Map<String, List<Map<String, String>>> state = new LinkedHashMap<>();
            Map<String, String> orders = Map.of(
                    "campaign_run_ledger", "run_id,revision",
                    "campaign_action_ledger", "run_id,revision,action_id",
                    "campaign_child_ledger", "run_id,revision,child_id",
                    "campaign_step_ledger", "run_id,revision,ordinal_index",
                    "campaign_artifact", "artifact_id",
                    "campaign_artifact_payload", "artifact_id",
                    "campaign_run_owner", "run_id,revision,run_version",
                    "campaign_callback_recovery", "run_id,revision,callback_kind,callback_id,attempt_version");
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

    private static final class FakeLiveness implements ProcessLiveness {
        private final Map<ProcessIdentity, State> states = new ConcurrentHashMap<>();
        private final AtomicInteger observations = new AtomicInteger();
        private volatile ProcessIdentity heldIdentity;
        private volatile CountDownLatch probeEntered;
        private volatile CountDownLatch releaseProbe;

        @Override
        public Observation observe(ProcessIdentity identity) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Process observations must run outside ledger transactions and their locks");
            observations.incrementAndGet();
            if (identity.equals(heldIdentity)) {
                probeEntered.countDown();
                awaitLatch(releaseProbe, "Process proof was not released");
            }
            State state = states.getOrDefault(identity, State.UNKNOWN);
            return new Observation(state, switch (state) {
                case ALIVE -> ProcessLiveness.PROCESS_ALIVE;
                case DEAD -> ProcessLiveness.PROCESS_EXITED;
                case UNKNOWN -> ProcessLiveness.PROCESS_ACCESS_DENIED;
            });
        }
    }

    private static final class FaultDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger injectedFailures = new AtomicInteger();
        private volatile String failInsertTable;

        private FaultDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override
        public Connection getConnection() throws SQLException { return guard(delegate.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return guard(delegate.getConnection(username, password));
        }

        private Connection guard(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String table = failInsertTable;
                        if (table != null && method.getName().equals("prepareStatement") && args != null
                                && args.length > 0 && args[0] instanceof String sql
                                && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("insert into " + table)) {
                            injectedFailures.incrementAndGet();
                            throw new SQLException("Injected " + table + " insert failure", "HY000");
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
