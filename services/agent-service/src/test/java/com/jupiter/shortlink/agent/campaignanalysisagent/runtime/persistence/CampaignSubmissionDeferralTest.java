package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignSubmissionDeferralTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final SubmissionBackoff BACKOFF = new SubmissionBackoff(1000, 4000);
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> true;

    @Test
    void repeatedRejectionSurvivesReopenAndUsesBoundedBackoffWithoutReleasingAnActiveCallback() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        RunToken original = run(runs, "backoff");
        ChildSpec spec = child(runs, original, "collect", "source", ChildMode.ASYNC);
        long started = fixture.clock().millis();
        DispatchPermit first = runs.beginDispatch(original, spec.childId());
        SubmissionDeferral rejected;
        try {
            runs.deferUnadmitted(first, CapacityKind.ACTIVE_EXECUTION);
            rejected = runs.submissionDeferral(original, spec.childId()).orElseThrow();
            ChildRecord saved = runs.child(original, spec.childId()).orElseThrow();
            assertEquals(ChildState.PREPARED, saved.state());
            assertEquals(UnresolvedReason.QUERY_CAPACITY_EXHAUSTED, saved.reason());
            assertTrue(saved.callbackActive());
            assertNull(saved.jobId());
            assertEquals(1, rejected.rejectedAttempts());
            assertEquals(started + 1000, rejected.retryNotBeforeMillis());
            assertEquals(first.attemptId(), rejected.lastAttemptId());
            assertEquals(first.attemptVersion(), rejected.lastAttemptVersion());
            runs.deferUnadmitted(first, CapacityKind.ACTIVE_EXECUTION);
            assertEquals(rejected, runs.submissionDeferral(original, spec.childId()).orElseThrow());
            assertEquals(saved, runs.child(original, spec.childId()).orElseThrow());
            assertThrows(IllegalStateException.class, () -> runs.deferUnadmitted(first, CapacityKind.RESULT_STORAGE));
            assertFalse(runs.mayDispatch(first));
            assertThrows(IllegalStateException.class, () -> runs.advance(original));
            assertThrows(IllegalStateException.class, () -> runs.beginDispatch(original, spec.childId()));
        } finally {
            runs.callbackExited(first);
        }
        CampaignRunStore reopened = fixture.runs();
        assertEquals(rejected, reopened.submissionDeferral(original, spec.childId()).orElseThrow());
        RunToken current = reopened.advance(original);
        fixture.clock().set(rejected.retryNotBeforeMillis() - 1);
        assertFalse(reopened.submissionDue(current, spec.childId()));
        assertThrows(IllegalStateException.class, () -> reopened.beginDispatch(current, spec.childId()));
        assertEquals(rejected, reopened.submissionDeferral(current, spec.childId()).orElseThrow());

        long[] delays = {2000, 4000, 4000};
        CapacityKind[] kinds = {CapacityKind.RESULT_STORAGE, CapacityKind.RECOVERY_IDENTITY, CapacityKind.ACTIVE_EXECUTION};
        for (int index = 0; index < delays.length; index++) {
            SubmissionDeferral previous = reopened.submissionDeferral(current, spec.childId()).orElseThrow();
            fixture.clock().set(previous.retryNotBeforeMillis());
            assertTrue(reopened.submissionDue(current, spec.childId()));
            DispatchPermit attempt = reopened.beginDispatch(current, spec.childId());
            try {
                assertTrue(attempt.attemptVersion() > previous.lastAttemptVersion());
                assertEquals(DispatchPurpose.FRESH, attempt.purpose());
                reopened.deferUnadmitted(attempt, kinds[index]);
                SubmissionDeferral next = fixture.runs().submissionDeferral(current, spec.childId()).orElseThrow();
                assertEquals(index + 2, next.rejectedAttempts());
                assertEquals(kinds[index], next.kind());
                assertEquals(fixture.clock().millis() + delays[index], next.retryNotBeforeMillis());
                assertEquals(spec, reopened.child(current, spec.childId()).orElseThrow().spec());
            } finally {
                reopened.callbackExited(attempt);
            }
        }
    }

    @Test
    void unknownResultsNonFreshPurposesCancellationAndStaleAttemptsCannotBecomeNewSubmissions() {
        for (ChildMode mode : ChildMode.values()) {
            Fixture fixture = fixture();
            CampaignRunStore runs = fixture.runs();
            RunToken run = run(runs, "unknown-" + mode);
            ChildSpec spec = child(runs, run, "collect", "source", mode);
            DispatchPermit attempt = runs.beginDispatch(run, spec.childId());
            try {
                runs.markUnresolved(attempt);
                ChildRecord unknown = runs.child(run, spec.childId()).orElseThrow();
                assertThrows(IllegalStateException.class, () -> runs.deferUnadmitted(attempt, CapacityKind.RESULT_STORAGE));
                assertEquals(unknown, runs.child(run, spec.childId()).orElseThrow());
                assertTrue(runs.submissionDeferral(run, spec.childId()).isEmpty());
            } finally { runs.callbackExited(attempt); }
            assertThrows(IllegalStateException.class, () -> runs.beginDispatch(run, spec.childId()));
        }

        Fixture nonFresh = fixture();
        CampaignRunStore runs = nonFresh.runs();
        RunToken run = run(runs, "purposes");
        ChildSpec spec = child(runs, run, "collect", "source", ChildMode.ASYNC);
        DispatchPermit submit = runs.beginDispatch(run, spec.childId());
        try { runs.recordWaiting(submit, "original-job"); }
        finally { runs.callbackExited(submit); }
        DispatchPermit reconciliation = runs.beginReconciliation(run, spec.childId());
        try {
            assertThrows(IllegalStateException.class, () -> runs.deferUnadmitted(reconciliation, CapacityKind.ACTIVE_EXECUTION));
            assertEquals("original-job", runs.child(run, spec.childId()).orElseThrow().jobId());
            runs.publishReady(reconciliation, artifact("ready-source"));
        } finally { runs.callbackExited(reconciliation); }
        DispatchPermit release = runs.beginRelease(run, spec.childId());
        try {
            ChildRecord ready = runs.child(run, spec.childId()).orElseThrow();
            assertThrows(IllegalStateException.class, () -> runs.deferUnadmitted(release, CapacityKind.RESULT_STORAGE));
            assertEquals(ready, runs.child(run, spec.childId()).orElseThrow());
            assertTrue(runs.submissionDeferral(run, spec.childId()).isEmpty());
        } finally { runs.callbackExited(release); }

        Fixture cancelled = fixture();
        CampaignRunStore cancelledRuns = cancelled.runs();
        RunToken cancelledRun = run(cancelledRuns, "cancelled");
        child(cancelledRuns, cancelledRun, "collect", "source", ChildMode.ASYNC);
        DispatchPermit cancelledAttempt = cancelledRuns.beginDispatch(cancelledRun, "source");
        try {
            cancelledRuns.cancel(cancelledRun);
            assertThrows(IllegalStateException.class, () -> cancelledRuns.deferUnadmitted(cancelledAttempt, CapacityKind.RECOVERY_IDENTITY));
            RunToken latest = cancelledRuns.loadRun(OWNER, cancelledRun.definition().runId()).orElseThrow().token();
            assertNotEquals(ChildState.PREPARED, cancelledRuns.child(latest, "source").orElseThrow().state());
        } finally { cancelledRuns.callbackExited(cancelledAttempt); }

        Fixture stale = fixture();
        CampaignRunStore staleRuns = stale.runs();
        RunToken staleRun = run(staleRuns, "stale");
        child(staleRuns, staleRun, "collect", "source", ChildMode.ASYNC);
        DispatchPermit old = staleRuns.beginDispatch(staleRun, "source");
        try { staleRuns.deferUnadmitted(old, CapacityKind.ACTIVE_EXECUTION); }
        finally { staleRuns.callbackExited(old); }
        stale.clock().set(staleRuns.submissionDeferral(staleRun, "source").orElseThrow().retryNotBeforeMillis());
        DispatchPermit newer = staleRuns.beginDispatch(staleRun, "source");
        try {
            ChildRecord active = staleRuns.child(staleRun, "source").orElseThrow();
            assertThrows(IllegalStateException.class, () -> staleRuns.deferUnadmitted(old, CapacityKind.ACTIVE_EXECUTION));
            assertEquals(active, staleRuns.child(staleRun, "source").orElseThrow());
            assertTrue(staleRuns.mayDispatch(newer));
            staleRuns.deferUnadmitted(newer, CapacityKind.ACTIVE_EXECUTION);
            assertEquals(2, staleRuns.submissionDeferral(staleRun, "source").orElseThrow().rejectedAttempts());
        } finally { staleRuns.callbackExited(newer); }
    }

    @Test
    void dueCapacityProofResumesAnInterruptedMixedStepOnlyAfterItsWaitingChildIsReady() {
        Fixture fixture = fixture();
        CampaignRunStore runs = fixture.runs();
        CampaignStepStore steps = fixture.steps();
        RunToken run = run(runs, "mixed");
        steps.initialize(run, List.of(new StepSpec("done", "{}", List.of(), Set.of("summary"), Set.of("summary")),
                simpleStep("mixed", "done"), simpleStep("no-proof"), simpleStep("unknown")));
        child(runs, run, "done", "done-source", ChildMode.SYNC);
        StepPermit done = steps.beginStep(run, "done");
        try {
            publish(runs, run, "done-source", "done-artifact");
            steps.settle(done, StepStatus.SUCCEEDED, Map.of("summary", "done-artifact"), null, ALLOW);
        } finally { steps.callbackExited(done); }
        StepRecord doneBefore = steps.step(run, "done").orElseThrow();
        Artifact doneArtifact = runs.readArtifact(OWNER, "done-artifact", ALLOW);

        child(runs, run, "mixed", "ready-source", ChildMode.SYNC);
        child(runs, run, "mixed", "capacity-source", ChildMode.ASYNC);
        child(runs, run, "mixed", "waiting-source", ChildMode.ASYNC);
        ChildSpec ordinary = child(runs, run, "mixed", "not-yet-started", ChildMode.ASYNC);
        StepPermit mixed = steps.beginStep(run, "mixed");
        try {
            publish(runs, run, "ready-source", "ready-artifact");
            DispatchPermit capacity = runs.beginDispatch(run, "capacity-source");
            try { runs.deferUnadmitted(capacity, CapacityKind.RESULT_STORAGE); }
            finally { runs.callbackExited(capacity); }
            DispatchPermit waiting = runs.beginDispatch(run, "waiting-source");
            try { runs.recordWaiting(waiting, "existing-job"); }
            finally { runs.callbackExited(waiting); }
            // Real callback exit without settle simulates the interrupted parent publication.
        } finally { steps.callbackExited(mixed); }
        StepRecord interrupted = steps.step(run, "mixed").orElseThrow();
        assertEquals(StepStatus.BLOCKED, interrupted.status());
        assertEquals("STEP_RESULT_UNKNOWN", interrupted.reason());
        assertEquals(interrupted, steps.refreshCapacityDeferred(run, "mixed"));
        fixture.clock().set(runs.submissionDeferral(run, "capacity-source").orElseThrow().retryNotBeforeMillis());
        assertEquals(interrupted, steps.refreshCapacityDeferred(run, "mixed"), "A real WAITING child still prevents resumption");
        DispatchPermit finished = runs.beginReconciliation(run, "waiting-source");
        try { runs.publishReady(finished, artifact("waiting-artifact")); }
        finally { runs.callbackExited(finished); }
        assertEquals(StepStatus.BLOCKED, steps.refreshWaiting(run, "mixed").status());
        StepRecord resumed = fixture.steps().refreshCapacityDeferred(run, "mixed");
        assertEquals(StepStatus.READY, resumed.status());
        assertTrue(resumed.outputs().isEmpty());
        assertEquals(ordinary, runs.child(run, "not-yet-started").orElseThrow().spec());
        assertEquals(ChildState.PREPARED, runs.child(run, "not-yet-started").orElseThrow().state());
        assertEquals("existing-job", runs.child(run, "waiting-source").orElseThrow().jobId());
        assertEquals("ready-artifact", runs.child(run, "ready-source").orElseThrow().artifactId());
        assertEquals(doneBefore, steps.step(run, "done").orElseThrow());
        assertEquals(doneArtifact, runs.readArtifact(OWNER, "done-artifact", ALLOW));

        child(runs, run, "no-proof", "ordinary-only", ChildMode.ASYNC);
        StepPermit noProof = steps.beginStep(run, "no-proof");
        steps.callbackExited(noProof);
        assertEquals(StepStatus.BLOCKED, steps.refreshCapacityDeferred(run, "no-proof").status());
        child(runs, run, "unknown", "unknown-source", ChildMode.ASYNC);
        StepPermit unknown = steps.beginStep(run, "unknown");
        try {
            DispatchPermit request = runs.beginDispatch(run, "unknown-source");
            try { runs.markUnresolved(request); }
            finally { runs.callbackExited(request); }
        } finally { steps.callbackExited(unknown); }
        assertEquals(StepStatus.BLOCKED, steps.refreshCapacityDeferred(run, "unknown").status());
    }

    private static RunToken run(CampaignRunStore runs, String id) {
        return runs.createRun(new RunDefinition(OWNER, "session-1", "run-" + id, "plan-1", 1, "{}"));
    }
    private static ChildSpec child(CampaignRunStore runs, RunToken run, String step, String id, ChildMode mode) {
        String action = "action-" + id;
        runs.prepareAction(run, new ActionSpec(action, step, "TOOL", "statistics", "1", "{}"));
        ChildSpec spec = new ChildSpec(id, action, mode, "request-" + id,
                new WireRequest(mode == ChildMode.ASYNC ? "POST" : "GET", "/statistics/jobs",
                        "{\"requestId\":\"request-" + id + "\",\"scopeRef\":\"frozen-original\"}"));
        runs.prepareChild(run, spec);
        return spec;
    }
    private static StepSpec simpleStep(String id, String... dependencies) {
        return new StepSpec(id, "{}", List.of(dependencies), Set.of(), Set.of());
    }
    private static ArtifactDraft artifact(String id) {
        return new ArtifactDraft(id, "TestEvidence", "evidence/v1", "scope-frozen", "periods-frozen", "{}", "{}",
                NOW.plusSeconds(86400), "{\"pv\":17}");
    }
    private static void publish(CampaignRunStore runs, RunToken run, String child, String artifact) {
        DispatchPermit permit = runs.beginDispatch(run, child);
        try { runs.publishReady(permit, artifact(artifact)); }
        finally { runs.callbackExited(permit); }
    }
    private static Fixture fixture() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:submission_deferral_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql")).execute(source);
        return new Fixture(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)), new MutableClock());
    }
    private record Fixture(JdbcTemplate jdbc, TransactionTemplate tx, MutableClock clock) {
        CampaignRunStore runs() { return new JdbcCampaignRunStore(jdbc, tx, clock, Limits.defaults(), BACKOFF); }
        CampaignStepStore steps() { return new JdbcCampaignStepStore(jdbc, tx, clock); }
    }
    private static final class MutableClock extends Clock {
        private final AtomicLong time;
        private final ZoneId zone;
        MutableClock() { this(new AtomicLong(NOW.toEpochMilli()), ZoneOffset.UTC); }
        private MutableClock(AtomicLong time, ZoneId zone) { this.time = time; this.zone = zone; }
        void set(long millis) { time.set(millis); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(time, zone); }
        @Override public long millis() { return time.get(); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    }
}
