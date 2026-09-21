package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CampaignReplanPlanningHandoffTest {
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final Caller OTHER_OWNER = new Caller("tenant-2", "analyst-1", 7);

    @Test
    void createsStrictlyBoundTypedRequestWithoutExecutionCredentials() {
        Fixture fixture = fixture();
        CampaignReplanPlanningHandoff.Result first = CampaignReplanPlanningHandoff.create(fixture.request(
                fixture.pending(OWNER, "session-1", "run-1", "plan-1", 1,
                        "step-1", "candidate-hash-1", List.of("CANDIDATE_REPLAN_REQUESTED")),
                fixture.plan, fixture.assessment, Set.of("old-evidence", "old-artifact"),
                List.of(evidence("new-evidence", "new-artifact")),
                List.of(new ReplanRequest.UnsatisfiedRequirement("req-1", "NEEDS_INPUT", "Input is missing.")),
                "A new authorized source is required."));
        CampaignReplanPlanningHandoff.Result second = CampaignReplanPlanningHandoff.create(fixture.request(
                fixture.pending(OWNER, "session-1", "run-1", "plan-1", 1,
                        "step-1", "candidate-hash-1", List.of("CANDIDATE_REPLAN_REQUESTED")),
                fixture.plan, fixture.assessment, Set.of("old-evidence", "old-artifact"),
                List.of(evidence("new-evidence", "new-artifact")),
                List.of(new ReplanRequest.UnsatisfiedRequirement("req-1", "NEEDS_INPUT", "Input is missing.")),
                "A new authorized source is required."));

        assertThat(first.request().schemaVersion()).isEqualTo(ReplanRequest.SCHEMA_VERSION);
        assertThat(first.request().baseline().plan()).isEqualTo(fixture.plan);
        assertThat(first.request().baseline().assessment()).isEqualTo(fixture.assessment);
        assertThat(first.request().newEvidence()).containsExactly(evidence("new-evidence", "new-artifact"));
        assertThat(first.request().unsatisfiedRequirements()).hasSize(1);
        assertThat(first.assessment()).isEqualTo(fixture.assessment);
        assertThat(first.stepId()).isEqualTo("step-1");
        assertThat(first.expectedCandidateHash()).isEqualTo("candidate-hash-1");
        assertThat(first.reasonCodes()).containsExactly("CANDIDATE_REPLAN_REQUESTED");
        assertThat(first).isEqualTo(second);
        assertThatThrownBy(() -> first.reasonCodes().add("MUTATED"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(first.request().newEvidence()).isUnmodifiable();
        assertThat(first.request().unsatisfiedRequirements()).isUnmodifiable();
        assertThatThrownBy(() -> new CampaignReplanPlanningHandoff.Result(first.request(), first.assessment(),
                first.stepId(), first.expectedCandidateHash(), List.of("FORGED_REASON")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_REASON_CODE_INVALID");
    }

    @Test
    void rejectsOwnerSessionRunPlanAndRevisionDriftBeforeConstruction() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(
                fixture.pending(OWNER, "session-1", "run-1", "plan-1", 1, "step-1",
                        "candidate-hash-1", List.of("CANDIDATE_REPLAN_REQUESTED")),
                new CampaignReplanPlanningHandoff.Baseline(OTHER_OWNER, "session-1", fixture.plan,
                        fixture.assessment, Set.of()), List.of(evidence("new-evidence", "new-artifact")),
                List.of(), "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_OWNER_MISMATCH");

        CampaignReplanCandidateGate.PendingReplan sessionMismatch = fixture.pending(
                OWNER, "other-session", "run-1", "plan-1", 1, "step-1", "candidate-hash-1",
                List.of("CANDIDATE_REPLAN_REQUESTED"));
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(sessionMismatch,
                fixture.plan, fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")),
                List.of(), "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_SESSION_MISMATCH");

        PlanSpec changedRun = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "other-run", "inputs-1",
                fixture.plan.goals(), fixture.plan.steps());
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(
                fixture.pending(OWNER, "session-1", "run-1", "plan-1", 1, "step-1",
                        "candidate-hash-1", List.of("CANDIDATE_REPLAN_REQUESTED")), changedRun,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(),
                "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_RUN_MISMATCH");

        PlanSpec changedRevision = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 2, "run-1", "inputs-1",
                fixture.plan.goals(), fixture.plan.steps());
        PlanningAssessment changedAssessment = new PlanningAssessment("plan-1", 2, "catalog-v1",
                fixture.assessment.requirements(), fixture.assessment.coverageBindings(), fixture.assessment.gaps());
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(
                fixture.pending(OWNER, "session-1", "run-1", "plan-1", 1, "step-1",
                        "candidate-hash-1", List.of("CANDIDATE_REPLAN_REQUESTED")), changedRevision,
                changedAssessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(),
                "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_REVISION_MISMATCH");
    }

    @Test
    void rejectsInactiveRunsUnknownStepsAndForgedReasonCodes() {
        Fixture fixture = fixture();
        CampaignReplanCandidateGate.PendingReplan cancelled = new CampaignReplanCandidateGate.PendingReplan(
                OWNER, new CampaignRunHandle(OWNER, "session-1", "run-1", "plan-1", 1,
                        CampaignRunStore.RunStatus.CANCELLED), "step-1", "candidate-hash-1",
                List.of("CANDIDATE_REPLAN_REQUESTED"));
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(cancelled, fixture.plan,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(),
                "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_RUN_NOT_ACTIVE");

        CampaignReplanCandidateGate.PendingReplan unknownStep = fixture.pending(
                OWNER, "session-1", "run-1", "plan-1", 1, "unknown-step", "candidate-hash-1",
                List.of("CANDIDATE_REPLAN_REQUESTED"));
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(unknownStep, fixture.plan,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(),
                "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_STEP_MISMATCH");

        CampaignReplanCandidateGate.PendingReplan forgedReason = fixture.pending(
                OWNER, "session-1", "run-1", "plan-1", 1, "step-1", "candidate-hash-1",
                List.of("MODEL_SAYS_REPLAN"));
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(forgedReason, fixture.plan,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(),
                "new evidence")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_HANDOFF_REASON_CODE_INVALID");
    }

    @Test
    void rejectsOldOrUnboundedEvidenceAndUnknownRequirementReasons() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of("old-evidence"), List.of(evidence("old-evidence", "new-artifact")),
                List.of(), "new evidence")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_EVIDENCE_NOT_NEW");

        List<ReplanRequest.Evidence> tooMany = IntStream.range(0,
                        CampaignReplanPlanningHandoff.MAX_NEW_EVIDENCE + 1)
                .mapToObj(i -> evidence("new-evidence-" + i, "new-artifact-" + i)).toList();
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of(), tooMany, List.of(), "new evidence")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_HANDOFF_EVIDENCE_LIMIT");

        ReplanRequest.UnsatisfiedRequirement unknown = new ReplanRequest.UnsatisfiedRequirement(
                "req-1", "MODEL_DECIDED", "missing");
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of(), List.of(), List.of(unknown), "new evidence")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_HANDOFF_REQUIREMENT_INVALID");
    }

    @Test
    void rejectsMissingTriggerAndUnsafeRationale() {
        Fixture fixture = fixture();
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of(), List.of(), List.of(), "no trigger")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_TRIGGER_MISSING");

        String tooLong = "x".repeat(CampaignReplanPlanningHandoff.MAX_RATIONALE_CHARACTERS + 1);
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(), tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_HANDOFF_RATIONALE_INVALID");
        assertThatThrownBy(() -> CampaignReplanPlanningHandoff.create(fixture.request(fixture.pending(), fixture.plan,
                fixture.assessment, Set.of(), List.of(evidence("new-evidence", "new-artifact")), List.of(), "bad\ntext")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_HANDOFF_RATIONALE_INVALID");
    }

    private static ReplanRequest.Evidence evidence(String id, String artifact) {
        return new ReplanRequest.Evidence(id, artifact, "stats-v1", "content-hash");
    }

    private static Fixture fixture() {
        PlanSpec.Goal goal = new PlanSpec.Goal("goal-1", "Analyze visits", true, "Deliver evidence");
        PlanSpec.ExecutorRef executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1");
        PlanSpec.Step step = new PlanSpec.Step("step-1", List.of("goal-1"), PlanSpec.ExecutionMode.FIXED,
                executor, null, List.of(), Map.of(), Map.of("metric", "pv"), "stats-v1");
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(goal), List.of(step));
        PlanningAssessment.Requirement requirement = new PlanningAssessment.Requirement(
                "req-1", "goal-1", PlanningAssessment.RequirementKind.DATA, true,
                "data/v1", "1", Map.of());
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog-v1",
                List.of(requirement), List.of(), List.of(new PlanningAssessment.Gap(
                        "req-1", PlanningAssessment.GapReason.NEEDS_INPUT, "Input is missing.")));
        return new Fixture(plan, assessment);
    }

    private record Fixture(PlanSpec plan, PlanningAssessment assessment) {
        CampaignReplanCandidateGate.PendingReplan pending() {
            return pending(OWNER, "session-1", "run-1", "plan-1", 1, "step-1", "candidate-hash-1",
                    List.of("CANDIDATE_REPLAN_REQUESTED"));
        }

        CampaignReplanCandidateGate.PendingReplan pending(Caller owner, String session, String run,
                                                           String planId, int revision, String step,
                                                           String hash, List<String> reasons) {
            return new CampaignReplanCandidateGate.PendingReplan(owner,
                    new CampaignRunHandle(owner, session, run, planId, revision,
                            CampaignRunStore.RunStatus.ACTIVE), step, hash, reasons);
        }

        CampaignReplanPlanningHandoff.Request request(
                CampaignReplanCandidateGate.PendingReplan pending, PlanSpec plan,
                PlanningAssessment assessment, Set<String> evidenceIds,
                List<ReplanRequest.Evidence> newEvidence,
                List<ReplanRequest.UnsatisfiedRequirement> requirements, String rationale) {
            return new CampaignReplanPlanningHandoff.Request(pending,
                    new CampaignReplanPlanningHandoff.Baseline(OWNER, "session-1", plan, assessment,
                            evidenceIds), newEvidence, requirements, rationale);
        }

        CampaignReplanPlanningHandoff.Request request(
                CampaignReplanCandidateGate.PendingReplan pending,
                CampaignReplanPlanningHandoff.Baseline baseline,
                List<ReplanRequest.Evidence> newEvidence,
                List<ReplanRequest.UnsatisfiedRequirement> requirements, String rationale) {
            return new CampaignReplanPlanningHandoff.Request(pending, baseline,
                    newEvidence, requirements, rationale);
        }
    }
}
