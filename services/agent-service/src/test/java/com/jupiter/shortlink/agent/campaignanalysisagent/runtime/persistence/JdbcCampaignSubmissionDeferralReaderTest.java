package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.CapacityKind;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.UnresolvedReason;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.SubmissionBackoff;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCampaignSubmissionDeferralReaderTest {
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final Instant BASE = Instant.parse("2026-09-21T00:00:00Z");
    private static final SubmissionBackoff BACKOFF = new SubmissionBackoff(1_000, 4_000);

    @Test
    void returnsOnlyDueActiveCandidatesInStableOrderAndHonorsLimit() {
        Fixture fixture = fixture();
        deferred(fixture, OWNER, "run-z", BASE.toEpochMilli() - 3_000, "step-z", "child-z");
        deferred(fixture, OWNER, "run-a", BASE.toEpochMilli() - 2_000, "step-a", "child-a");
        deferred(fixture, OWNER, "run-future", BASE.toEpochMilli() + 1_000, "step-future", "child-future");
        deferred(fixture, new Caller("tenant-b", "analyst-a", 7), "run-foreign", BASE.toEpochMilli() - 3_000,
                "step-foreign", "child-foreign");

        List<CampaignSubmissionDeferralReader.DueCandidate> first = fixture.reader()
                .pendingDue(OWNER, BASE.toEpochMilli(), 2);
        assertEquals(List.of("run-z", "run-a"), first.stream()
                .map(CampaignSubmissionDeferralReader.DueCandidate::runId).toList());
        assertEquals(List.of("step-z", "step-a"), first.stream()
                .map(CampaignSubmissionDeferralReader.DueCandidate::stepId).toList());
        assertTrue(first.stream().allMatch(candidate -> candidate.capacityKind() == CapacityKind.ACTIVE_EXECUTION));
        assertTrue(first.stream().allMatch(candidate -> candidate.rejectedAttempts() == 1));
        assertTrue(first.stream().allMatch(candidate -> candidate.sourceRunVersion() == 0));
        assertEquals(first, fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 2));
        assertTrue(fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 10).stream()
                .noneMatch(candidate -> candidate.runId().equals("run-future")
                        || candidate.runId().equals("run-foreign")));
    }

    @Test
    void excludesTerminalRevisionsAndAllNonEligibleChildStates() {
        Fixture fixture = fixture();
        deferred(fixture, OWNER, "run-cancelled", BASE.toEpochMilli() - 2_000, "step-cancelled", "child-cancelled");
        RunToken cancelled = fixture.runs().loadRun(OWNER, "run-cancelled").orElseThrow().token();
        fixture.runs().cancel(cancelled);

        Seed superseded = deferred(fixture, OWNER, "run-revision", BASE.toEpochMilli() - 2_000,
                "step-old", "child-old");
        RunToken replacement = fixture.runs().revise(superseded.token(), 2, "{\"revision\":2}");
        deferredRevision(fixture, replacement, BASE.toEpochMilli() - 2_000, "step-new", "child-new");

        Seed ready = deferred(fixture, OWNER, "run-ready", BASE.toEpochMilli() - 2_000, "step-ready", "child-ready");
        fixture.jdbc().update("UPDATE campaign_child_ledger SET child_state='READY',unresolved_reason=NULL "
                        + "WHERE run_id=? AND revision=? AND child_id=?",
                ready.token().definition().runId(), ready.token().definition().revision(), ready.child().childId());
        Seed job = deferred(fixture, OWNER, "run-job", BASE.toEpochMilli() - 2_000, "step-job", "child-job");
        fixture.jdbc().update("UPDATE campaign_child_ledger SET job_id=? WHERE run_id=? AND revision=? AND child_id=?",
                "remote-job", job.token().definition().runId(), job.token().definition().revision(), job.child().childId());
        Seed callback = deferred(fixture, OWNER, "run-callback", BASE.toEpochMilli() - 2_000,
                "step-callback", "child-callback");
        fixture.jdbc().update("UPDATE campaign_child_ledger SET callback_active=TRUE WHERE run_id=? AND revision=? AND child_id=?",
                callback.token().definition().runId(), callback.token().definition().revision(), callback.child().childId());
        Seed unknown = deferred(fixture, OWNER, "run-unknown", BASE.toEpochMilli() - 2_000,
                "step-unknown", "child-unknown");
        fixture.jdbc().update("UPDATE campaign_child_ledger SET unresolved_reason='SUBMISSION_UNRESOLVED' "
                        + "WHERE run_id=? AND revision=? AND child_id=?",
                unknown.token().definition().runId(), unknown.token().definition().revision(), unknown.child().childId());

        List<CampaignSubmissionDeferralReader.DueCandidate> candidates = fixture.reader()
                .pendingDue(OWNER, BASE.toEpochMilli(), 256);
        assertEquals(List.of("run-revision"), candidates.stream()
                .map(CampaignSubmissionDeferralReader.DueCandidate::runId).toList());
        assertEquals(List.of(2), candidates.stream()
                .map(CampaignSubmissionDeferralReader.DueCandidate::revision).toList());
        assertEquals(List.of("child-new"), candidates.stream()
                .map(CampaignSubmissionDeferralReader.DueCandidate::childId).toList());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.childId().equals("child-new")));
    }

    @Test
    void rejectsInvalidCallerNowAndLimitWithoutOpeningAQuery() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> fixture.reader().pendingDue(new Caller("", "analyst-a", 7), BASE.toEpochMilli(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.reader().pendingDue(OWNER, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 257));
    }

    @Test
    void failsClosedWhenDeferralIdentityOrAttemptFenceIsCorrupted() {
        assertCorrupted(fixture -> fixture.jdbc().update("UPDATE campaign_submission_deferral SET request_id=?",
                "tampered-request"));
        assertCorrupted(fixture -> fixture.jdbc().update("UPDATE campaign_submission_deferral SET wire_hash=?",
                "0".repeat(64)));
        assertCorrupted(fixture -> fixture.jdbc().update("UPDATE campaign_submission_deferral SET last_attempt_id=?",
                "tampered-attempt"));
        assertCorrupted(fixture -> fixture.jdbc().update("UPDATE campaign_submission_deferral SET capacity_kind=?",
                "NOT_A_CAPACITY_KIND"));
    }

    @Test
    void readDoesNotMutateAnyLedgerRows() {
        Fixture fixture = fixture();
        deferred(fixture, OWNER, "run-read-only", BASE.toEpochMilli() - 2_000, "step-read", "child-read");
        Map<String, List<Map<String, Object>>> before = snapshot(fixture.jdbc());
        fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 256);
        assertEquals(before, snapshot(fixture.jdbc()));
    }

    private static void assertCorrupted(Consumer<Fixture> mutation) {
        Fixture fixture = fixture();
        deferred(fixture, OWNER, "run-corrupt", BASE.toEpochMilli() - 2_000, "step-corrupt", "child-corrupt");
        mutation.accept(fixture);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.reader().pendingDue(OWNER, BASE.toEpochMilli(), 1));
        assertEquals("CAPACITY_PENDING_CORRUPTED", failure.getMessage());
    }

    private static Seed deferred(Fixture fixture, Caller owner, String runId, long startedAt,
                                 String stepId, String childId) {
        RunToken token = fixture.runs().createRun(new RunDefinition(owner, "session-" + runId,
                runId, "plan-" + runId, 1, "{}"));
        return deferredRevision(fixture, token, startedAt, stepId, childId);
    }

    private static Seed deferredRevision(Fixture fixture, RunToken token, long startedAt,
                                         String stepId, String childId) {
        fixture.steps().initialize(token, List.of(new StepSpec(stepId, "{}", List.of(), Set.of(), Set.of())));
        String actionId = "action-" + childId;
        fixture.runs().prepareAction(token, new CampaignRunStore.ActionSpec(actionId, stepId,
                "TOOL", "statistics", "1", "{}"));
        ChildSpec child = new ChildSpec(childId, actionId, ChildMode.ASYNC, "request-" + childId,
                new CampaignRunStore.WireRequest("POST", "/statistics/jobs",
                        "{\"requestId\":\"request-" + childId + "\"}"));
        fixture.runs().prepareChild(token, child);
        fixture.clock().set(startedAt);
        DispatchPermit permit = fixture.runs().beginDispatch(token, childId);
        try {
            fixture.runs().deferUnadmitted(permit, CapacityKind.ACTIVE_EXECUTION);
        } finally {
            fixture.runs().callbackExited(permit);
        }
        assertEquals(ChildState.PREPARED, fixture.runs().child(token, childId).orElseThrow().state());
        assertEquals(UnresolvedReason.QUERY_CAPACITY_EXHAUSTED,
                fixture.runs().child(token, childId).orElseThrow().reason());
        return new Seed(token, child);
    }

    private static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        return Map.of(
                "runs", jdbc.queryForList("SELECT * FROM campaign_run_ledger ORDER BY run_id,revision"),
                "actions", jdbc.queryForList("SELECT * FROM campaign_action_ledger ORDER BY run_id,revision,action_id"),
                "steps", jdbc.queryForList("SELECT * FROM campaign_step_ledger ORDER BY run_id,revision,step_id"),
                "children", jdbc.queryForList("SELECT * FROM campaign_child_ledger ORDER BY run_id,revision,child_id"),
                "deferrals", jdbc.queryForList("SELECT * FROM campaign_submission_deferral ORDER BY run_id,revision,child_id"));
    }

    private static Fixture fixture() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:capacity_reader_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_3__campaign_submission_deferral.sql"))
                .execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        MutableClock clock = new MutableClock();
        JdbcCampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, clock, Limits.defaults(), BACKOFF);
        JdbcCampaignStepStore steps = new JdbcCampaignStepStore(jdbc, transactions, clock);
        return new Fixture(jdbc, transactions, clock, runs, steps,
                new JdbcCampaignSubmissionDeferralReader(jdbc, transactions));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, MutableClock clock,
                           JdbcCampaignRunStore runs, JdbcCampaignStepStore steps,
                           JdbcCampaignSubmissionDeferralReader reader) { }

    private record Seed(RunToken token, ChildSpec child) { }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(BASE.toEpochMilli());

        void set(long value) { millis.set(value); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public long millis() { return millis.get(); }

        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    }
}
