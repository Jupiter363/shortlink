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
import org.junit.jupiter.api.Test;

class CampaignReplanCandidateAdmissionTest {
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);

    @Test
    void admitsAcceptedCandidateWithOpaqueClaimAndNoExecutionState() {
        Fixture fixture = fixture("candidate-hash-1");
        PlanSpec candidate = fixture.candidate();

        CampaignReplanCandidateAdmission.CandidateAdmission first =
                CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), candidate,
                        fixture.candidateAssessment(), fixture.handle());
        CampaignReplanCandidateAdmission.CandidateAdmission second =
                CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), candidate,
                        fixture.candidateAssessment(), fixture.handle());

        assertThat(first.candidate()).isEqualTo(candidate);
        assertThat(first.candidateAssessment()).isEqualTo(fixture.candidateAssessment());
        assertThat(first.replanAssessment().accepted()).isTrue();
        assertThat(first.stepId()).isEqualTo("step-1");
        assertThat(first.expectedCandidateHash()).isEqualTo("candidate-hash-1");
        assertThat(first.candidatePlanHash()).isEqualTo(ReplanRequest.planHash(candidate));
        assertThat(first.hashBinding()).isEqualTo(CampaignReplanCandidateAdmission.HashBinding.OPAQUE_UNVERIFIED);
        assertThat(first.hashVerified()).isFalse();
        assertThat(first.reasonCodes()).containsExactly("CANDIDATE_REPLAN_REQUESTED");
        assertThat(first).isEqualTo(second);
        assertThatThrownBy(() -> first.reasonCodes().add("MUTATED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsCanonicalClaimOnlyWhenItMatchesComputedPlanHash() {
        Fixture opaque = fixture("candidate-hash-1");
        String computed = ReplanRequest.planHash(opaque.candidate());
        Fixture verified = fixture(computed);

        CampaignReplanCandidateAdmission.CandidateAdmission admission =
                CampaignReplanCandidateAdmission.admit(verified.handoff(), verified.baseline(), verified.candidate(),
                        verified.candidateAssessment(), verified.handle());

        assertThat(admission.hashBinding()).isEqualTo(CampaignReplanCandidateAdmission.HashBinding.VERIFIED);
        assertThat(admission.hashVerified()).isTrue();
        assertThat(admission.candidatePlanHash()).isEqualTo(computed);
    }

    @Test
    void rejectsVerifiableHashMismatchBeforeAnyExecutionBoundary() {
        Fixture fixture = fixture("0".repeat(64));

        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_CANDIDATE_HASH_MISMATCH");
    }

    @Test
    void rejectsFreshHandleOwnerSessionRunPlanRevisionAndStatusDrift() {
        Fixture fixture = fixture("candidate-hash-1");
        List<CampaignRunHandle> invalid = List.of(
                new CampaignRunHandle(new Caller("tenant-2", "analyst-1", 7), "session-1", "run-1",
                        "plan-1", 1, CampaignRunStore.RunStatus.ACTIVE),
                new CampaignRunHandle(OWNER, "other-session", "run-1", "plan-1", 1,
                        CampaignRunStore.RunStatus.ACTIVE),
                new CampaignRunHandle(OWNER, "session-1", "other-run", "plan-1", 1,
                        CampaignRunStore.RunStatus.ACTIVE),
                new CampaignRunHandle(OWNER, "session-1", "run-1", "other-plan", 1,
                        CampaignRunStore.RunStatus.ACTIVE),
                new CampaignRunHandle(OWNER, "session-1", "run-1", "plan-1", 2,
                        CampaignRunStore.RunStatus.ACTIVE),
                new CampaignRunHandle(OWNER, "session-1", "run-1", "plan-1", 1,
                        CampaignRunStore.RunStatus.CANCELLED));

        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(0)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_OWNER_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(1)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_SESSION_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(2)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_RUN_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(3)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_PLAN_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(4)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_REVISION_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), invalid.get(5)))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_RUN_NOT_ACTIVE");
    }

    @Test
    void rejectsTrustedBaselineThatDoesNotExactlyMatchHandoffFacts() {
        Fixture fixture = fixture("candidate-hash-1");
        CampaignReplanPlanningHandoff.Baseline drifted = new CampaignReplanPlanningHandoff.Baseline(
                OWNER, "session-1", fixture.basePlan(), fixture.baseAssessment(), Set.of("different-evidence"));

        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), drifted,
                fixture.candidate(), fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_BASELINE_MISMATCH");
    }

    @Test
    void rejectsTrustedBaselineOwnerOrSessionReplacementEvenWhenPlanFactsMatch() {
        Fixture fixture = fixture("candidate-hash-1");
        CampaignReplanPlanningHandoff.Baseline ownerDrift = new CampaignReplanPlanningHandoff.Baseline(
                new Caller("tenant-2", "analyst-1", 7), "session-1", fixture.basePlan(),
                fixture.baseAssessment(), Set.of("old-evidence"));
        CampaignReplanPlanningHandoff.Baseline sessionDrift = new CampaignReplanPlanningHandoff.Baseline(
                OWNER, "other-session", fixture.basePlan(), fixture.baseAssessment(), Set.of("old-evidence"));

        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), ownerDrift,
                fixture.candidate(), fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_BASELINE_MISMATCH");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), sessionDrift,
                fixture.candidate(), fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_BASELINE_MISMATCH");
    }

    @Test
    void rejectsCandidateAssessmentReasonsAndEquivalentPlans() {
        Fixture fixture = fixture("candidate-hash-1");
        PlanSpec unchanged = fixture.basePlan();
        PlanningAssessment unchangedAssessment = fixture.baseAssessment();
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(),
                unchanged, unchangedAssessment, fixture.handle()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_CANDIDATE_NOT_ACCEPTED:REVISION_NOT_ADVANCED");

        PlanSpec changedGoals = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 2, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal-2", "Changed", true, "Deliver")), fixture.candidate().steps());
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(),
                changedGoals, fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_CANDIDATE_NOT_ACCEPTED:ORIGINAL_GOALS_CHANGED");

        PlanningAssessment changedRequirements = new PlanningAssessment("plan-1", 2, "catalog-v1",
                List.of(new PlanningAssessment.Requirement("other", "goal-1",
                        PlanningAssessment.RequirementKind.DATA, true, "criterion", "v1", Map.of())),
                List.of(), List.of());
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), fixture.candidate(),
                changedRequirements, fixture.handle()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_CANDIDATE_NOT_ACCEPTED:ORIGINAL_REQUIREMENTS_CHANGED");
    }

    @Test
    void publicAdmissionRecordRejectsForgedComputedHashAndMissingInputs() {
        Fixture fixture = fixture("candidate-hash-1");
        var assessed = fixture.handoff().request().assess(fixture.candidate(), fixture.candidateAssessment());
        assertThatThrownBy(() -> new CampaignReplanCandidateAdmission.CandidateAdmission(
                fixture.candidate(), fixture.candidateAssessment(), assessed, "step-1", "candidate-hash-1",
                "0".repeat(64), CampaignReplanCandidateAdmission.HashBinding.OPAQUE_UNVERIFIED,
                List.of("CANDIDATE_REPLAN_REQUESTED")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_COMPUTED_HASH_MISMATCH");

        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(null, fixture.baseline(), fixture.candidate(),
                fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_ADMISSION_HANDOFF_REQUIRED");
        assertThatThrownBy(() -> CampaignReplanCandidateAdmission.admit(fixture.handoff(), fixture.baseline(), null,
                fixture.candidateAssessment(), fixture.handle()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("REPLAN_ADMISSION_CANDIDATE_REQUIRED");
    }

    private static Fixture fixture(String candidateHash) {
        PlanSpec.Goal goal = new PlanSpec.Goal("goal-1", "Analyze visits", true, "Deliver evidence");
        PlanSpec basePlan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(goal), List.of(step("step-1")));
        PlanningAssessment baseAssessment = assessment(1);
        CampaignRunHandle handle = new CampaignRunHandle(OWNER, "session-1", "run-1", "plan-1", 1,
                CampaignRunStore.RunStatus.ACTIVE);
        CampaignReplanCandidateGate.PendingReplan pending = new CampaignReplanCandidateGate.PendingReplan(
                OWNER, handle, "step-1", candidateHash, List.of("CANDIDATE_REPLAN_REQUESTED"));
        CampaignReplanPlanningHandoff.Baseline baseline = new CampaignReplanPlanningHandoff.Baseline(
                OWNER, "session-1", basePlan, baseAssessment, Set.of("old-evidence"));
        CampaignReplanPlanningHandoff.Result handoff = CampaignReplanPlanningHandoff.create(
                new CampaignReplanPlanningHandoff.Request(pending, baseline,
                        List.of(new ReplanRequest.Evidence("new-evidence", "new-artifact", "stats-v1", "hash")),
                        List.of(), "new evidence"));
        return new Fixture(basePlan, baseAssessment, handle, baseline, handoff);
    }

    private static PlanSpec.Step step(String id) {
        PlanSpec.ExecutorRef executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1");
        return new PlanSpec.Step(id, List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, executor, null,
                List.of(), Map.of(), Map.of("metric", "pv"), "stats-v1");
    }

    private static PlanningAssessment assessment(int revision) {
        PlanningAssessment.Requirement requirement = new PlanningAssessment.Requirement(
                "req-1", "goal-1", PlanningAssessment.RequirementKind.DATA, true,
                "data/v1", "1", Map.of());
        return new PlanningAssessment("plan-1", revision, "catalog-v1", List.of(requirement), List.of(),
                List.of(new PlanningAssessment.Gap("req-1", PlanningAssessment.GapReason.NEEDS_INPUT,
                        "Input is missing.")));
    }

    private record Fixture(PlanSpec basePlan, PlanningAssessment baseAssessment,
                           CampaignRunHandle handle,
                           CampaignReplanPlanningHandoff.Baseline baseline,
                           CampaignReplanPlanningHandoff.Result handoff) {
        PlanSpec candidate() {
            return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 2, "run-1", "inputs-1",
                    basePlan.goals(), List.of(step("step-1"), step("step-2")));
        }

        PlanningAssessment candidateAssessment() { return assessment(2); }
    }
}
