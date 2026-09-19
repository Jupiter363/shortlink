package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

@Timeout(20)
class JdbcCampaignRunLedgerTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T08:00:00Z"), ZoneOffset.UTC);
    private static final String DEFINITION = """
            {"planId":"plan-1","frozenInputs":{"gid":"group-7","linkIds":["link-2","link-5"]},
             "pins":{"executor":"compare/2","policy":"read-only/1"}}
            """;
    private static final ActionSpec ACTION = new ActionSpec("action-1", "step-compare", "TOOL",
            "get_campaign_comparison", "compare/2", "{\"scopeRef\":\"scope-7\",\"metric\":\"pv\"}");

    @Test
    void reopeningStoreRetainsFrozenDefinitionAndExistingChildWithoutResubmission() {
        Fixture fixture = fixture();
        CampaignRunStore first = fixture.store();
        RunToken run = prepareRun(first);
        ChildSpec spec = child("child-async", ChildMode.ASYNC);
        first.prepareChild(run, spec);
        DispatchPermit permit = first.beginDispatch(run, spec.childId());
        first.recordWaiting(permit, "job-existing");
        first.callbackExited(permit);

        CampaignRunStore reopened = fixture.store();
        RunRecord loaded = reopened.loadRun(OWNER, run.definition().runId()).orElseThrow();
        assertEquals(run.definition(), loaded.definition());
        assertEquals(run.definition().definitionHash(), loaded.definition().definitionHash());
        assertEquals(RunStatus.ACTIVE, loaded.status());
        assertEquals(run, loaded.token(), "Reading persisted state must not acquire a new writer token");
        ChildRecord existing = reopened.child(loaded.token(), spec.childId()).orElseThrow();
        assertEquals(spec, existing.spec());
        assertEquals(spec.wire().hash(), existing.spec().wire().hash());
        assertEquals(ChildState.WAITING, existing.state());
        assertEquals("job-existing", existing.jobId());
        assertFalse(existing.callbackActive());
        assertEquals(List.of(existing), reopened.children(loaded.token()));
        assertEquals(existing, reopened.prepareChild(loaded.token(), spec));
        assertThrows(IllegalStateException.class, () -> reopened.beginDispatch(loaded.token(), spec.childId()));
        assertEquals(1, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger", Integer.class));
    }

    @Test
    void competingStoreInstancesHaveOneAdvanceWinnerAndFenceTheOldToken() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore first = fixture.store();
        CampaignRunStore second = fixture.store();
        RunToken original = prepareRun(first);
        first.prepareChild(original, child("child-cas", ChildMode.SYNC));
        CountDownLatch contendersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<AdvanceOutcome> left = workers.submit(() -> advanceAfter(first, original, contendersReady, start));
            Future<AdvanceOutcome> right = workers.submit(() -> advanceAfter(second, original, contendersReady, start));
            assertTrue(contendersReady.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AdvanceOutcome> outcomes = List.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(outcome -> outcome.token() != null).count());
            assertEquals(1L, outcomes.stream().filter(outcome -> outcome.failure() != null).count());
            RunToken winner = outcomes.stream().map(AdvanceOutcome::token).filter(token -> token != null)
                    .findFirst().orElseThrow();
            assertEquals(original.version() + 1, winner.version());
            assertNotEquals(original.advanceToken(), winner.advanceToken());
            assertEquals(winner, second.loadRun(OWNER, original.definition().runId()).orElseThrow().token());
            assertThrows(IllegalStateException.class, () -> first.beginDispatch(original, "child-cas"));
            assertThrows(IllegalStateException.class, () -> second.prepareAction(original, ACTION));
            DispatchPermit current = second.beginDispatch(winner, "child-cas");
            assertTrue(second.mayDispatch(current));
            second.callbackExited(current);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void frozenRunActionChildAndRequestIdentitiesRejectChangedBodies() {
        Fixture fixture = fixture();
        CampaignRunStore store = fixture.store();
        RunToken run = prepareRun(store);
        ChildSpec original = child("child-frozen", ChildMode.ASYNC);
        ChildRecord prepared = store.prepareChild(run, original);
        assertEquals(run, store.createRun(run.definition()));
        assertDoesNotThrow(() -> store.prepareAction(run, ACTION));
        assertEquals(prepared, store.prepareChild(run, original));

        RunDefinition definition = run.definition();
        assertThrows(IllegalStateException.class, () -> store.createRun(new RunDefinition(OWNER,
                definition.sessionId(), definition.runId(), definition.planId(), 1, "{\"gid\":\"changed\"}")));
        assertThrows(IllegalStateException.class, () -> store.prepareAction(run, new ActionSpec(ACTION.actionId(),
                ACTION.stepId(), ACTION.executorKind(), ACTION.executorName(), ACTION.executorVersion(),
                "{\"scopeRef\":\"different-scope\"}")));
        assertThrows(IllegalStateException.class, () -> store.prepareAction(run, new ActionSpec(ACTION.actionId(),
                ACTION.stepId(), ACTION.executorKind(), ACTION.executorName(), "compare/3", ACTION.definitionJson())));
        WireRequest changedWire = new WireRequest(original.wire().method(), original.wire().path(),
                "{\"gid\":\"different-group\"}");
        assertNotEquals(original.wire().hash(), changedWire.hash());
        assertThrows(IllegalStateException.class, () -> store.prepareChild(run, new ChildSpec(original.childId(),
                original.actionId(), original.mode(), original.requestId(), changedWire)));
        assertThrows(IllegalStateException.class, () -> store.prepareChild(run, new ChildSpec(original.childId(),
                original.actionId(), original.mode(), "changed-request-id", original.wire())));
        assertThrows(IllegalStateException.class, () -> store.prepareChild(run, new ChildSpec("other-child",
                original.actionId(), original.mode(), original.requestId(), changedWire)));
        assertThrows(IllegalArgumentException.class, () -> store.prepareChild(run, new ChildSpec("invalid-request-child",
                original.actionId(), original.mode(), "request.with-colon:value", original.wire())));

        CampaignRunStore reopened = fixture.store();
        assertEquals(definition, reopened.loadRun(OWNER, definition.runId()).orElseThrow().definition());
        assertEquals(List.of(prepared), reopened.children(run));
        assertEquals(ACTION.definitionHash(), fixture.jdbc().queryForObject(
                "SELECT definition_hash FROM campaign_action_ledger WHERE action_id=?", String.class, ACTION.actionId()));
        assertEquals(original.wire().hash(), fixture.jdbc().queryForObject(
                "SELECT wire_hash FROM campaign_child_ledger WHERE child_id=?", String.class, original.childId()));
        assertEquals(1, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_action_ledger", Integer.class));
    }

    @Test
    void unknownSyncAndAsyncResultsRemainUnresolvedAndNeverBecomeFreshDispatches() {
        Fixture fixture = fixture();
        CampaignRunStore store = fixture.store();
        RunToken run = prepareRun(store);
        for (ChildMode mode : ChildMode.values()) {
            ChildSpec spec = child("child-unknown-" + mode.name(), mode);
            store.prepareChild(run, spec);
            DispatchPermit attempt = store.beginDispatch(run, spec.childId());
            store.markUnresolved(attempt);
            ChildRecord unresolved = store.child(run, spec.childId()).orElseThrow();
            assertEquals(ChildState.UNRESOLVED, unresolved.state());
            assertEquals(mode == ChildMode.SYNC ? UnresolvedReason.READ_RESULT_UNKNOWN
                    : UnresolvedReason.SUBMISSION_UNRESOLVED, unresolved.reason());
            assertNull(unresolved.jobId());
            assertNull(unresolved.artifactId());
            assertTrue(unresolved.callbackActive());
            assertFalse(store.mayDispatch(attempt));
            store.callbackExited(attempt);
            assertThrows(IllegalStateException.class, () -> store.beginDispatch(run, spec.childId()));
            if (mode == ChildMode.SYNC) {
                assertThrows(IllegalStateException.class, () -> store.beginReconciliation(run, spec.childId()));
            }
        }
    }

    @Test
    void asyncUnknownCanOnlyReconcileTheFrozenRequestAndOldAttemptsCannotClearItsCallback() {
        Fixture fixture = fixture();
        CampaignRunStore store = fixture.store();
        RunToken run = prepareRun(store);
        ChildSpec spec = child("child-reconcile", ChildMode.ASYNC);
        store.prepareChild(run, spec);
        DispatchPermit fresh = store.beginDispatch(run, spec.childId());
        store.markUnresolved(fresh);
        store.callbackExited(fresh);
        DispatchPermit reconcile = store.beginReconciliation(run, spec.childId());
        assertEquals(DispatchPurpose.RECONCILE, reconcile.purpose());
        assertTrue(reconcile.attemptVersion() > fresh.attemptVersion());
        assertNotEquals(fresh.attemptId(), reconcile.attemptId());
        assertEquals(spec, store.child(run, spec.childId()).orElseThrow().spec());
        assertTrue(store.mayDispatch(reconcile));
        assertFalse(store.mayDispatch(fresh));
        assertThrows(IllegalStateException.class, () -> store.callbackExited(fresh));
        assertThrows(IllegalStateException.class, () -> store.recordLateJob(fresh, "job-from-old-attempt"));
        assertTrue(store.child(run, spec.childId()).orElseThrow().callbackActive());
        store.recordWaiting(reconcile, "job-recovered");
        store.callbackExited(reconcile);
        assertThrows(IllegalStateException.class, () -> store.beginDispatch(run, spec.childId()));

        DispatchPermit polling = store.beginReconciliation(run, spec.childId());
        assertEquals(DispatchPurpose.RECONCILE, polling.purpose());
        assertThrows(IllegalStateException.class, () -> store.recordWaiting(polling, "different-job"));
        store.markUnresolved(polling);
        store.callbackExited(polling);
        ChildRecord unknownResult = fixture.store().child(run, spec.childId()).orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unknownResult.state());
        assertEquals(UnresolvedReason.JOB_RESULT_UNKNOWN, unknownResult.reason());
        assertEquals("job-recovered", unknownResult.jobId());
        assertEquals(spec, unknownResult.spec());
        assertThrows(IllegalStateException.class, () -> store.beginDispatch(run, spec.childId()));
    }

    @Test
    void cancelledFutureCannotReleaseAnActuallyRunningCallbackOrPermitAnotherDispatch() throws Exception {
        Fixture fixture = fixture();
        CampaignRunStore store = fixture.store();
        RunToken run = prepareRun(store);
        store.prepareChild(run, child("child-running", ChildMode.ASYNC));
        store.prepareChild(run, child("child-next", ChildMode.SYNC));
        DispatchPermit running = store.beginDispatch(run, "child-running");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch actualExit = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = worker.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        store.callbackExited(running);
                    } finally {
                        actualExit.countDown();
                    }
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            assertTrue(future.isDone());
            assertTrue(future.isCancelled());
            assertEquals(1L, actualExit.getCount(), "Future cancellation is not callback-exit evidence");
            store.markUnresolved(running);
            assertTrue(store.child(run, "child-running").orElseThrow().callbackActive());
            assertThrows(IllegalStateException.class, () -> store.advance(run));
            assertThrows(IllegalStateException.class, () -> store.beginDispatch(run, "child-next"));
            assertThrows(IllegalStateException.class, () -> store.beginReconciliation(run, "child-running"));

            release.countDown();
            assertTrue(actualExit.await(5, TimeUnit.SECONDS));
            assertFalse(store.child(run, "child-running").orElseThrow().callbackActive());
            RunToken next = fixture.store().advance(run);
            DispatchPermit permitted = store.beginDispatch(next, "child-next");
            assertTrue(store.mayDispatch(permitted));
            store.callbackExited(permitted);
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelAndRevisionFenceOldCallbacksButRetainTheirLateJobWithoutReopeningTheRun() {
        for (boolean revise : List.of(false, true)) {
            Fixture fixture = fixture();
            CampaignRunStore store = fixture.store();
            RunToken original = prepareRun(store);
            store.prepareChild(original, child("child-old", ChildMode.ASYNC));
            DispatchPermit old = store.beginDispatch(original, "child-old");
            RunToken next = null;
            if (revise) {
                next = store.revise(original, 2, "{\"frozenInputs\":{\"gid\":\"group-next\"}}");
                store.prepareAction(next, ACTION);
                store.prepareChild(next, child("child-new-revision", ChildMode.SYNC));
                RunToken blocked = next;
                assertThrows(IllegalStateException.class, () -> store.advance(blocked));
                assertThrows(IllegalStateException.class, () -> store.beginDispatch(blocked, "child-new-revision"));
            } else {
                store.cancel(original);
            }

            assertFalse(store.mayDispatch(old));
            assertThrows(IllegalStateException.class, () -> store.recordWaiting(old, "job-late"));
            assertThrows(IllegalStateException.class, () -> store.beginReconciliation(original, "child-old"));
            store.recordLateJob(old, "job-late");
            assertThrows(IllegalStateException.class, () -> store.recordLateJob(old, "job-conflicting"));
            assertEquals("job-late", fixture.jdbc().queryForObject(
                    "SELECT job_id FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id='child-old'",
                    String.class, original.definition().runId()));
            assertEquals(ChildState.UNRESOLVED.name(), fixture.jdbc().queryForObject(
                    "SELECT child_state FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id='child-old'",
                    String.class, original.definition().runId()));
            assertEquals(revise ? RunStatus.SUPERSEDED.name() : RunStatus.CANCELLED.name(),
                    fixture.jdbc().queryForObject("SELECT run_status FROM campaign_run_ledger WHERE run_id=? AND revision=1",
                            String.class, original.definition().runId()));
            assertEquals(0, fixture.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM campaign_child_ledger WHERE artifact_id IS NOT NULL", Integer.class));
            assertFalse(store.mayDispatch(old), "Recording a remote fact cannot revive a fenced writer");
            store.callbackExited(old);
            assertFalse(fixture.jdbc().queryForObject(
                    "SELECT callback_active FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id='child-old'",
                    Boolean.class, original.definition().runId()));
            if (revise) {
                assertEquals(next, store.loadRun(OWNER, original.definition().runId()).orElseThrow().token());
                DispatchPermit current = store.beginDispatch(next, "child-new-revision");
                assertTrue(store.mayDispatch(current));
                store.callbackExited(current);
            } else {
                assertEquals(RunStatus.CANCELLED, store.loadRun(OWNER, original.definition().runId()).orElseThrow().status());
                assertThrows(IllegalStateException.class, () -> store.advance(original));
            }
        }
    }

    @Test
    void recoveringInterruptedDispatchRetainsActiveCallbackUntilItsExactAttemptExits() {
        Fixture fixture = fixture();
        CampaignRunStore originalStore = fixture.store();
        RunToken run = prepareRun(originalStore);
        ChildSpec spec = child("child-interrupted", ChildMode.ASYNC);
        originalStore.prepareChild(run, spec);
        DispatchPermit original = originalStore.beginDispatch(run, spec.childId());

        CampaignRunStore reopened = fixture.store();
        RunToken loaded = reopened.loadRun(OWNER, run.definition().runId()).orElseThrow().token();
        reopened.recoverInterrupted(loaded);
        reopened.recoverInterrupted(loaded);
        ChildRecord unresolved = reopened.child(loaded, spec.childId()).orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unresolved.state());
        assertEquals(UnresolvedReason.SUBMISSION_UNRESOLVED, unresolved.reason());
        assertTrue(unresolved.callbackActive());
        assertEquals(original.attemptId(), unresolved.attemptId());
        assertEquals(original.attemptVersion(), unresolved.attemptVersion());
        assertEquals(spec, unresolved.spec());
        assertFalse(reopened.mayDispatch(original));
        assertThrows(IllegalStateException.class, () -> reopened.advance(loaded));
        assertThrows(IllegalStateException.class, () -> reopened.beginReconciliation(loaded, spec.childId()));

        originalStore.callbackExited(original);
        RunToken acquired = reopened.advance(loaded);
        assertThrows(IllegalStateException.class, () -> reopened.beginDispatch(acquired, spec.childId()));
        DispatchPermit recovery = reopened.beginReconciliation(acquired, spec.childId());
        assertEquals(DispatchPurpose.RECONCILE, recovery.purpose());
        assertTrue(reopened.mayDispatch(recovery));
        reopened.callbackExited(recovery);
    }

    private static RunToken prepareRun(CampaignRunStore store) {
        RunToken run = store.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1, DEFINITION));
        store.prepareAction(run, ACTION);
        return run;
    }

    private static ChildSpec child(String childId, ChildMode mode) {
        return new ChildSpec(childId, ACTION.actionId(), mode, "request-" + childId,
                new WireRequest("POST", "/statistics/query", "{\"gid\":\"group-7\",\"metric\":\"pv\"}"));
    }

    private static AdvanceOutcome advanceAfter(CampaignRunStore store, RunToken token,
                                               CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Advance contenders did not start");
        try {
            return new AdvanceOutcome(store.advance(token), null);
        } catch (IllegalStateException conflict) {
            return new AdvanceOutcome(null, conflict);
        }
    }

    private static Fixture fixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_run_ledger_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"))
                .execute(dataSource);
        return new Fixture(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private record AdvanceOutcome(RunToken token, IllegalStateException failure) {}

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions) {
        private CampaignRunStore store() {
            return new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        }
    }
}
