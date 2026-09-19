package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.WorkState.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.DeliveryState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.GoalProgress;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.StepProgress;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignProgressView.UnavailableOutput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Progress is projected from real business ledgers, without a Graph, HTTP or fake store. */
@Timeout(20)
class CampaignProgressServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final ArtifactAuthorizer ALLOW = (caller, artifact) -> true;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final PlanningAssessment.Gap GAP = new PlanningAssessment.Gap("requirement-query",
            PlanningAssessment.GapReason.EVIDENCE_UNAVAILABLE, "Conversion evidence has not been collected");

    @Test
    void waitingPropagatesAcrossGoalDependenciesWhileIndependentCompletedEvidenceRemainsAvailable() {
        Fixture fixture = fixture(false);
        waiting(fixture, "a", "job-existing");
        succeed(fixture, "d", NOW.plusSeconds(3600));
        var before = fixture.steps().snapshot(OWNER, "run-1");

        CampaignProgressView view = fixture.service(CLOCK, ALLOW).read(OWNER, "run-1");

        assertEquals(WAITING, view.workState());
        assertEquals(DeliveryState.NOT_ASSESSED, view.deliveryState());
        assertEquals(StepStatus.WAITING, step(view, "a").recordedStatus());
        assertEquals("RESULT_PENDING", step(view, "a").reasonCode());
        assertTrue(step(view, "a").availableOutputs().isEmpty());
        assertEquals(StepStatus.PENDING, step(view, "b").recordedStatus());
        assertEquals(WAITING, step(view, "b").workState());
        assertEquals(List.of("a"), step(view, "b").blockedBy());
        assertEquals("DEPENDENCY_WAITING", step(view, "b").reasonCode());
        assertEquals(WAITING, step(view, "c").workState(), "A waiting grandparent must reach the downstream goal");
        assertEquals(List.of("b"), step(view, "c").blockedBy());
        assertEquals(EXECUTED, step(view, "d").workState());
        assertEquals("artifact-d-private", step(view, "d").availableOutputs().get(0).artifact().artifactId());
        assertEquals(WAITING, goal(view, "goal-query").workState());
        assertEquals(WAITING, goal(view, "goal-followup").workState());
        assertEquals(List.of("b", "c"), goal(view, "goal-followup").stepIds());
        assertEquals(EXECUTED, goal(view, "goal-independent").workState());
        assertEquals(step(view, "d").availableOutputs(), goal(view, "goal-independent").availableOutputs());
        assertTrue(goal(view, "goal-followup").availableOutputs().isEmpty());
        assertEquals(before, fixture.steps().snapshot(OWNER, "run-1"), "Reading progress must not acquire or mutate the run");
        assertEquals("job-existing", fixture.runs().child(fixture.token(), "child-a").orElseThrow().jobId());
        assertCallbacksExited(fixture);
    }

    @Test
    void everyStepSucceededMeansExecutedButNeverAnsweredOrAssessedDelivery() throws Exception {
        Fixture fixture = fixture(false);
        for (String id : List.of("a", "b", "c", "d")) succeed(fixture, id, NOW.plusSeconds(3600));
        RunToken before = fixture.runs().loadRun(OWNER, "run-1").orElseThrow().token();

        CampaignProgressView view = fixture.service(CLOCK, ALLOW).read(OWNER, "run-1");

        assertEquals(CampaignProgressView.SCHEMA, view.schemaVersion());
        assertEquals("run-1", view.runId());
        assertEquals("plan-1", view.planId());
        assertEquals(1, view.revision());
        assertEquals(RunStatus.ACTIVE, view.runStatus());
        assertEquals(EXECUTED, view.workState());
        assertEquals(DeliveryState.NOT_ASSESSED, view.deliveryState());
        assertEquals(4, view.steps().size());
        view.steps().forEach(step -> {
            assertEquals(StepStatus.SUCCEEDED, step.recordedStatus());
            assertEquals(EXECUTED, step.workState());
            assertEquals(1, step.availableOutputs().size());
            assertTrue(step.unavailableOutputs().isEmpty());
        });
        assertEquals(3, view.goals().size());
        view.goals().forEach(goal -> assertEquals(EXECUTED, goal.workState()));
        assertEquals("Explain the query evidence", goal(view, "goal-followup").question());
        assertFalse(goal(view, "goal-independent").required());
        assertEquals(before, fixture.runs().loadRun(OWNER, "run-1").orElseThrow().token());
        String json = JSON.writeValueAsString(view);
        assertFalse(json.contains("ANSWERED"));
        assertFalse(json.contains("PRIVATE_PAYLOAD_MARKER"), "Only short result references belong in progress");
        assertFalse(json.contains("PRIVATE_PROVENANCE_MARKER"));
        assertCallbacksExited(fixture);
    }

    @Test
    void revokedAndExpiredArtifactsHideTheirIdentitiesWhileOtherCompletedReferencesRemainAvailable() throws Exception {
        Fixture fixture = fixture(false);
        succeed(fixture, "a", NOW.plusSeconds(3600));
        succeed(fixture, "b", NOW.plusSeconds(60));
        succeed(fixture, "c", NOW.plusSeconds(3600));
        succeed(fixture, "d", NOW.plusSeconds(3600));
        Clock later = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        ArtifactAuthorizer current = (caller, metadata) -> !metadata.ref().artifactId().equals("artifact-a-private");

        CampaignProgressView view = fixture.service(later, current).read(OWNER, "run-1");

        assertEquals(List.of(new UnavailableOutput("a", "evidence", "OUTPUT_ACCESS_DENIED")), step(view, "a").unavailableOutputs());
        assertEquals(List.of(new UnavailableOutput("b", "evidence", "OUTPUT_EXPIRED")), step(view, "b").unavailableOutputs());
        assertTrue(step(view, "a").availableOutputs().isEmpty());
        assertTrue(step(view, "b").availableOutputs().isEmpty());
        assertEquals(StepStatus.SUCCEEDED, step(view, "a").recordedStatus());
        assertEquals(StepStatus.SUCCEEDED, step(view, "b").recordedStatus());
        assertEquals("artifact-c-private", step(view, "c").availableOutputs().get(0).artifact().artifactId());
        assertEquals("artifact-d-private", step(view, "d").availableOutputs().get(0).artifact().artifactId());
        assertEquals(step(view, "a").unavailableOutputs(), goal(view, "goal-query").unavailableOutputs());
        assertEquals(step(view, "b").unavailableOutputs(), goal(view, "goal-followup").unavailableOutputs());
        assertEquals(step(view, "c").availableOutputs(), goal(view, "goal-followup").availableOutputs());
        assertTrue(goal(view, "goal-independent").unavailableOutputs().isEmpty());
        assertEquals(DeliveryState.NOT_ASSESSED, view.deliveryState());
        String json = JSON.writeValueAsString(view);
        for (String id : List.of("a", "b")) {
            assertFalse(json.contains("artifact-" + id + "-private"), id);
            assertFalse(json.contains("private-scope-" + id), id);
            assertFalse(json.contains("PRIVATE_TYPE_" + id), id);
            assertFalse(json.contains("private-schema-" + id), id);
        }
        assertTrue(json.contains("artifact-c-private"));
        assertTrue(json.contains("artifact-d-private"));
        assertFalse(json.contains("PRIVATE_PAYLOAD_MARKER"));
    }

    @Test
    void cancellationRetainsGoalSpecificGapsAndCompletedEvidenceWithoutPublishingRawFailureText() throws Exception {
        Fixture fixture = fixture(true);
        succeed(fixture, "a", NOW.plusSeconds(3600));
        String privateFailure = "upstream rejected https://private.internal?token=DO_NOT_EXPOSE";
        StepPermit failing = fixture.steps().beginStep(fixture.token(), "b");
        try {
            fixture.steps().settle(failing, StepStatus.BLOCKED, Map.of(), privateFailure, ALLOW);
        } finally {
            fixture.steps().callbackExited(failing);
        }
        succeed(fixture, "d", NOW.plusSeconds(3600));
        CampaignProgressService service = fixture.service(CLOCK, ALLOW);

        CampaignProgressView active = service.read(OWNER, "run-1");
        assertEquals(BLOCKED, goal(active, "goal-query").workState(),
                "A completed query artifact cannot erase a remaining requirement of the same goal");
        assertEquals(BLOCKED, goal(active, "goal-followup").workState());
        assertEquals(EXECUTED, goal(active, "goal-independent").workState());
        assertEquals(List.of(GAP), goal(active, "goal-query").planningGaps());
        assertTrue(goal(active, "goal-followup").planningGaps().isEmpty());
        assertTrue(goal(active, "goal-independent").planningGaps().isEmpty());
        assertEquals("STEP_BLOCKED", step(active, "b").reasonCode());
        assertEquals(BLOCKED, step(active, "c").workState());
        assertEquals(List.of("b"), step(active, "c").blockedBy());
        assertEquals("DEPENDENCY_BLOCKED", step(active, "c").reasonCode());
        assertFalse(JSON.writeValueAsString(active).contains(privateFailure));
        fixture.runs().cancel(fixture.token());

        CampaignProgressView cancelled = service.read(OWNER, "run-1");
        assertEquals(RunStatus.CANCELLED, cancelled.runStatus());
        assertEquals(CANCELLED, cancelled.workState());
        assertEquals(DeliveryState.NOT_ASSESSED, cancelled.deliveryState());
        cancelled.steps().forEach(step -> assertEquals(CANCELLED, step.workState()));
        cancelled.goals().forEach(goal -> assertEquals(CANCELLED, goal.workState()));
        assertEquals(StepStatus.BLOCKED, step(cancelled, "b").recordedStatus());
        assertEquals(StepStatus.PENDING, step(cancelled, "c").recordedStatus());
        assertEquals(List.of(GAP), goal(cancelled, "goal-query").planningGaps());
        assertTrue(goal(cancelled, "goal-followup").planningGaps().isEmpty());
        assertTrue(goal(cancelled, "goal-independent").planningGaps().isEmpty());
        assertEquals(goal(active, "goal-query").availableOutputs(), goal(cancelled, "goal-query").availableOutputs());
        assertEquals(goal(active, "goal-independent").availableOutputs(), goal(cancelled, "goal-independent").availableOutputs());
        assertFalse(JSON.writeValueAsString(cancelled).contains("DO_NOT_EXPOSE"));
        for (Caller unauthorized : List.of(new Caller("tenant-other", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "another-subject", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class, () -> service.read(unauthorized, "run-1"));
        }
        assertCallbacksExited(fixture);
    }

    private static void waiting(Fixture fixture, String stepId, String jobId) {
        StepPermit step = fixture.steps().beginStep(fixture.token(), stepId);
        try {
            DispatchPermit child = child(fixture, stepId, ChildMode.ASYNC);
            try {
                fixture.runs().recordWaiting(child, jobId);
            } finally {
                fixture.runs().callbackExited(child);
            }
            fixture.steps().settle(step, StepStatus.WAITING, Map.of(), null, ALLOW);
        } finally {
            fixture.steps().callbackExited(step);
        }
    }

    private static void succeed(Fixture fixture, String stepId, Instant expiresAt) {
        StepPermit step = fixture.steps().beginStep(fixture.token(), stepId);
        try {
            DispatchPermit child = child(fixture, stepId, ChildMode.SYNC);
            String artifactId = "artifact-" + stepId + "-private";
            try {
                fixture.runs().publishReady(child, new ArtifactDraft(artifactId, "PRIVATE_TYPE_" + stepId,
                        "private-schema-" + stepId, "private-scope-" + stepId, "periods-" + stepId,
                        "{\"status\":\"COMPLETE\"}", "{\"snapshotId\":\"PRIVATE_PROVENANCE_MARKER\"}",
                        expiresAt, "{\"pv\":13,\"detail\":\"PRIVATE_PAYLOAD_MARKER\"}"));
            } finally {
                fixture.runs().callbackExited(child);
            }
            fixture.steps().settle(step, StepStatus.SUCCEEDED, Map.of("evidence", artifactId), null, ALLOW);
        } finally {
            fixture.steps().callbackExited(step);
        }
    }

    private static DispatchPermit child(Fixture fixture, String stepId, ChildMode mode) {
        fixture.runs().prepareAction(fixture.token(), new ActionSpec("action-" + stepId, stepId, "TOOL", "query", "1", "{}"));
        fixture.runs().prepareChild(fixture.token(), new ChildSpec("child-" + stepId, "action-" + stepId, mode,
                "request-" + stepId, new WireRequest("POST", "/statistics/query", "{}")));
        return fixture.runs().beginDispatch(fixture.token(), "child-" + stepId);
    }

    private static StepProgress step(CampaignProgressView view, String id) {
        return view.steps().stream().filter(step -> step.stepId().equals(id)).findFirst().orElseThrow();
    }

    private static GoalProgress goal(CampaignProgressView view, String id) {
        return view.goals().stream().filter(goal -> goal.goalId().equals(id)).findFirst().orElseThrow();
    }

    private static Fixture fixture(boolean withGap) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_progress_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        CampaignRunStore runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        CampaignStepStore steps = new JdbcCampaignStepStore(jdbc, transactions, CLOCK);
        List<PlanSpec.Step> planSteps = List.of(planStep("a", "goal-query", List.of()),
                planStep("b", "goal-followup", List.of("a")), planStep("c", "goal-followup", List.of("b")),
                planStep("d", "goal-independent", List.of()));
        List<PlanSpec.Goal> goals = List.of(new PlanSpec.Goal("goal-query", "Query campaign evidence", true, "Deliver query evidence"),
                new PlanSpec.Goal("goal-followup", "Explain the query evidence", true, "Deliver the explanation"),
                new PlanSpec.Goal("goal-independent", "Review an independent result", false, "Deliver independent evidence"));
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1", goals, planSteps);
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("requirement-query", "goal-query",
                        PlanningAssessment.RequirementKind.DATA, true, "conversion-evidence", "1", Map.of())),
                List.of(), withGap ? List.of(GAP) : List.of());
        FrozenCampaignRun frozen = FrozenCampaignRun.freeze(plan,
                new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()), assessment);
        RunToken token = steps.acquireRun(runs.createRun(frozen.definition(OWNER, "session-1")));
        steps.initialize(token, planSteps.stream().map(step -> new StepSpec(step.stepId(),
                "{\"stepId\":\"" + step.stepId() + "\"}", step.dependsOn(), Set.of("evidence"), Set.of("evidence"))).toList());
        return new Fixture(jdbc, transactions, runs, steps, token);
    }

    private static PlanSpec.Step planStep(String id, String goalId, List<String> dependencies) {
        return new PlanSpec.Step(id, List.of(goalId), PlanSpec.ExecutionMode.FIXED,
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1"), null,
                dependencies, Map.of(), Map.of(), "evidence/v1");
    }

    private static void assertCallbacksExited(Fixture fixture) {
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, CampaignRunStore runs,
                           CampaignStepStore steps, RunToken token) {
        CampaignProgressService service(Clock clock, ArtifactAuthorizer authorizer) {
            return new CampaignProgressService(new JdbcCampaignStepStore(jdbc, transactions, clock),
                    new JdbcCampaignRunStore(jdbc, transactions, clock), authorizer);
        }
    }
}
