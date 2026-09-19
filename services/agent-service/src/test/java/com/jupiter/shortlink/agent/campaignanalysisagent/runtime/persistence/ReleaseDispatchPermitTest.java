package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** The release-binding store supplies remote-release authorization; these tests cover only writer fencing. */
class ReleaseDispatchPermitTest {
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-a", "analyst", 7);
    private static final String CHILD = "child-result";
    private static final String STEP = "collect";
    private static final ArtifactAuthorizer ALLOW = (caller, metadata) -> true;

    @Test
    void releaseKeepsReadyEvidenceAndNamedOutputsReadableWhileItsActualCallbackBlocksNewWriters() {
        Fixture f = new Fixture();
        ChildRecord before = f.runs.child(f.run, CHILD).orElseThrow();
        Artifact evidence = f.runs.readArtifact(OWNER, f.draft.artifactId(), ALLOW);
        StepRecord output = f.steps.step(f.run, STEP).orElseThrow();
        assertThrows(IllegalStateException.class, () -> f.runs.beginDispatch(f.run, CHILD));
        assertThrows(IllegalStateException.class, () -> f.runs.beginReconciliation(f.run, CHILD));

        DispatchPermit release = f.runs.beginRelease(f.run, CHILD);
        try {
            assertEquals(DispatchPurpose.RELEASE, release.purpose());
            assertTrue(f.runs.mayDispatch(release));
            ChildRecord active = f.runs.child(f.run, CHILD).orElseThrow();
            assertEquals(ChildState.READY, active.state());
            assertEquals(before.spec(), active.spec());
            assertEquals(before.jobId(), active.jobId());
            assertEquals(before.artifactId(), active.artifactId());
            assertEquals(before.reason(), active.reason());
            assertEquals(before.attemptVersion() + 1, active.attemptVersion());
            assertNotEquals(before.attemptId(), active.attemptId());
            assertTrue(active.callbackActive());
            assertEquals(evidence, f.reopen().readArtifact(OWNER, f.draft.artifactId(), ALLOW));
            assertEquals(output, f.steps.step(f.run, STEP).orElseThrow());
            assertThrows(IllegalStateException.class, () -> f.reopen().advance(f.run));
            assertThrows(IllegalStateException.class, () -> f.runs.beginRelease(f.run, CHILD));
            f.runs.recoverInterrupted(f.run);
            assertTrue(f.runs.child(f.run, CHILD).orElseThrow().callbackActive(),
                    "Reopening or scanning interrupted queries is not proof that a release callback exited");
            assertEquals(ChildState.READY, f.runs.child(f.run, CHILD).orElseThrow().state());
        } finally {
            f.runs.callbackExited(release);
        }
        assertFalse(f.runs.mayDispatch(release));
        RunToken next = f.reopen().advance(f.run);
        assertEquals(before.spec(), f.runs.child(next, CHILD).orElseThrow().spec());
        assertEquals(ChildState.READY, f.runs.child(next, CHILD).orElseThrow().state());
        assertEquals(output, f.steps.step(next, STEP).orElseThrow());
        assertEquals(evidence, f.runs.readArtifact(OWNER, f.draft.artifactId(), ALLOW));
    }

    @Test
    void releaseCannotMutateResultsAndCancellationOrRevisionFencesOldAttemptsUntilTheirRealExit() {
        for (boolean revise : List.of(false, true)) {
            Fixture f = new Fixture();
            Artifact original = f.runs.readArtifact(OWNER, f.draft.artifactId(), ALLOW);
            DispatchPermit old = f.runs.beginRelease(f.run, CHILD);
            f.runs.callbackExited(old);
            DispatchPermit current = f.runs.beginRelease(f.run, CHILD);
            assertFalse(f.runs.mayDispatch(old));
            assertThrows(IllegalStateException.class, () -> f.runs.callbackExited(old));
            assertTrue(f.runs.mayDispatch(current), "An old callback cannot clear the new attempt");
            assertThrows(IllegalStateException.class, () -> f.runs.recordWaiting(current, "job-result"));
            assertThrows(IllegalStateException.class, () -> f.runs.recordLateJob(current, "job-result"));
            assertThrows(IllegalStateException.class, () -> f.runs.publishReady(current, f.draft));
            assertThrows(IllegalStateException.class, () -> f.runs.markUnresolved(current));
            assertEquals(ChildState.READY, f.runs.child(f.run, CHILD).orElseThrow().state());

            RunToken latest;
            if (revise) {
                latest = f.runs.revise(f.run, 2, "{\"plan\":\"revised\"}");
                assertThrows(IllegalStateException.class, () -> f.runs.advance(latest));
            } else {
                f.runs.cancel(f.run);
                latest = f.runs.loadRun(OWNER, f.run.definition().runId()).orElseThrow().token();
            }
            assertFalse(f.runs.mayDispatch(current));
            assertThrows(IllegalStateException.class, () -> f.runs.beginRelease(f.run, CHILD));
            assertThrows(IllegalStateException.class, () -> f.runs.recordLateJob(current, "job-result"));
            assertEquals(original, f.runs.readArtifact(OWNER, f.draft.artifactId(), ALLOW));
            assertEquals(Boolean.TRUE, f.jdbc.queryForObject(
                    "SELECT callback_active FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id=?",
                    Boolean.class, f.run.definition().runId(), CHILD));

            // Only the exact original attempt's real finally signal releases the callback marker.
            f.reopen().callbackExited(current);
            assertFalse(f.runs.mayDispatch(current));
            assertEquals("READY", f.jdbc.queryForObject(
                    "SELECT child_state FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id=?",
                    String.class, f.run.definition().runId(), CHILD));
            assertEquals(f.draft.artifactId(), f.jdbc.queryForObject(
                    "SELECT artifact_id FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id=?",
                    String.class, f.run.definition().runId(), CHILD));
            assertEquals(Boolean.FALSE, f.jdbc.queryForObject(
                    "SELECT callback_active FROM campaign_child_ledger WHERE run_id=? AND revision=1 AND child_id=?",
                    Boolean.class, f.run.definition().runId(), CHILD));
            if (revise) assertDoesNotThrow(() -> f.runs.advance(latest));
            else {
                assertEquals(RunStatus.CANCELLED, f.runs.loadRun(OWNER, f.run.definition().runId()).orElseThrow().status());
                assertThrows(IllegalStateException.class, () -> f.runs.advance(latest));
            }
            assertEquals(original, f.runs.readArtifact(OWNER, f.draft.artifactId(), ALLOW));
        }
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;
        private final CampaignRunStore runs;
        private final CampaignStepStore steps;
        private final RunToken run;
        private final ArtifactDraft draft = new ArtifactDraft("artifact-result", "StatisticsJobPages", "statistics-job-pages/v1",
                "scope-a", "period-a", "{\"completeness\":\"PARTIAL\"}", "{\"jobId\":\"job-result\"}",
                NOW.plusSeconds(3600), "{\"pv\":37,\"resultComplete\":true}");

        private Fixture() {
            DriverManagerDataSource source = new DriverManagerDataSource(
                    "jdbc:h2:mem:release_permit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            source.setDriverClassName("org.h2.Driver");
            new ResourceDatabasePopulator(
                    new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            runs = reopen();
            steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
            run = runs.createRun(new RunDefinition(OWNER, "session-1", "run-result", "plan-result", 1, "{}"));
            steps.initialize(run, List.of(new StepSpec(STEP, "{}", List.of(), Set.of("pages"), Set.of("pages"))));
            StepPermit step = steps.beginStep(run, STEP);
            runs.prepareAction(run, new ActionSpec("action-result", STEP, "TOOL", "statistics_query_job", "1", "{}"));
            runs.prepareChild(run, new ChildSpec(CHILD, "action-result", ChildMode.ASYNC, "request-result",
                    new WireRequest("POST", "/internal/statistics/frozen-jobs", "{\"requestId\":\"request-result\"}")));
            DispatchPermit submit = runs.beginDispatch(run, CHILD);
            runs.recordWaiting(submit, "job-result");
            runs.callbackExited(submit);
            DispatchPermit read = runs.beginReconciliation(run, CHILD);
            runs.publishReady(read, draft);
            runs.callbackExited(read);
            steps.settle(step, StepStatus.SUCCEEDED, Map.of("pages", draft.artifactId()), null, ALLOW);
            steps.callbackExited(step);
        }

        private CampaignRunStore reopen() { return new JdbcCampaignRunStore(jdbc, transactions, CLOCK); }
    }
}
