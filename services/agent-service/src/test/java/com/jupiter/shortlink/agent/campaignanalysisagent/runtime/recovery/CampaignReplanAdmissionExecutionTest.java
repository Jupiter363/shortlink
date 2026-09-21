package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignReplanAdmissionExecutionTest {
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);

    @Test
    void executesOnlyTheAdmittedCandidateAfterExactResolution() throws Exception {
        Fixture fixture = fixture();
        CampaignReplanCandidateAdmission.CandidateAdmission admission = CampaignReplanCandidateAdmission.admit(
                fixture.handoff(), fixture.baseline(), fixture.candidate(), fixture.candidateAssessment(),
                fixture.handle());
        AtomicInteger calls = new AtomicInteger();
        CampaignReplanAdmissionExecutor executor = new CampaignReplanAdmissionExecutor(
                (owner, session, run) -> java.util.Optional.of(fixture.token()),
                (owner, token, request) -> {
                    calls.incrementAndGet();
                    assertThat(request.candidate()).isEqualTo(admission.candidate());
                    assertThat(request.replan()).isEqualTo(fixture.handoff().request());
                    return new CampaignReplanApplicationService.Response(
                            ReplanCoordinator.Outcome.APPLIED, "ACCEPTED", null, token,
                            admission.replanAssessment());
                });

        CampaignReplanApplicationService.Response response = executor.executeAdmitted(
                new CampaignReplanAdmissionExecutor.Request(OWNER, "session-1", "run-1",
                        CampaignReplanApplicationService.REQUIRED_CAPABILITY, fixture.handoff(), admission));

        assertThat(response.applied()).isTrue();
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsLegacyHandoffAndIdentityDriftBeforeResolverOrRuntime() {
        Fixture fixture = fixture();
        CampaignReplanCandidateAdmission.CandidateAdmission admission = CampaignReplanCandidateAdmission.admit(
                fixture.handoff(), fixture.baseline(), fixture.candidate(), fixture.candidateAssessment(),
                fixture.handle());
        AtomicInteger calls = new AtomicInteger();
        CampaignReplanAdmissionExecutor executor = new CampaignReplanAdmissionExecutor(
                (owner, session, run) -> {
                    calls.incrementAndGet();
                    return java.util.Optional.of(fixture.token());
                }, (owner, token, request) -> {
                    calls.incrementAndGet();
                    return null;
                });
        CampaignReplanPlanningHandoff.Result legacy = new CampaignReplanPlanningHandoff.Result(
                fixture.handoff().request(), fixture.handoff().assessment(), fixture.handoff().stepId(),
                fixture.handoff().expectedCandidateHash(), fixture.handoff().reasonCodes());

        assertThatThrownBy(() -> executor.executeAdmitted(new CampaignReplanAdmissionExecutor.Request(
                OWNER, "session-1", "run-1", CampaignReplanApplicationService.REQUIRED_CAPABILITY,
                legacy, admission))).isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_EXECUTION_HANDOFF_IDENTITY_MISSING");
        assertThatThrownBy(() -> executor.executeAdmitted(new CampaignReplanAdmissionExecutor.Request(
                OWNER, "other-session", "run-1", CampaignReplanApplicationService.REQUIRED_CAPABILITY,
                fixture.handoff(), admission))).isInstanceOf(SecurityException.class)
                .hasMessage("REPLAN_ADMISSION_EXECUTION_IDENTITY_MISMATCH");
        assertThat(calls).hasValue(0);
    }

    @Test
    void missingTokenAndAdmissionCapabilityNeverReachRuntime() {
        Fixture fixture = fixture();
        CampaignReplanCandidateAdmission.CandidateAdmission admission = CampaignReplanCandidateAdmission.admit(
                fixture.handoff(), fixture.baseline(), fixture.candidate(), fixture.candidateAssessment(),
                fixture.handle());
        AtomicInteger calls = new AtomicInteger();
        CampaignReplanAdmissionExecutor executor = new CampaignReplanAdmissionExecutor(
                (owner, session, run) -> java.util.Optional.empty(),
                (owner, token, request) -> {
                    calls.incrementAndGet();
                    return null;
                });
        assertThatThrownBy(() -> executor.executeAdmitted(new CampaignReplanAdmissionExecutor.Request(
                OWNER, "session-1", "run-1", CampaignReplanApplicationService.REQUIRED_CAPABILITY,
                fixture.handoff(), admission))).isInstanceOf(IllegalStateException.class)
                .hasMessage("REPLAN_RUN_TOKEN_NOT_FOUND");
        assertThat(calls).hasValue(0);
    }

    private static Fixture fixture() {
        PlanSpec base = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal-1", "Analyze", true, "Deliver")), List.of(step("step-1")));
        PlanningAssessment assessment = assessment(1);
        CampaignRunHandle handle = new CampaignRunHandle(OWNER, "session-1", "run-1", "plan-1", 1,
                CampaignRunStore.RunStatus.ACTIVE);
        CampaignReplanCandidateGate.PendingReplan pending = new CampaignReplanCandidateGate.PendingReplan(
                OWNER, handle, "step-1", "candidate-1", List.of("CANDIDATE_REPLAN_REQUESTED"));
        CampaignReplanPlanningHandoff.Baseline baseline = new CampaignReplanPlanningHandoff.Baseline(
                OWNER, "session-1", base, assessment, Set.of("old-evidence"));
        CampaignReplanPlanningHandoff.Result handoff = CampaignReplanPlanningHandoff.create(
                new CampaignReplanPlanningHandoff.Request(pending, baseline,
                        List.of(new ReplanRequest.Evidence("new-evidence", "new-artifact", "stats-v1", "hash")),
                        List.of(), "new evidence"));
        return new Fixture(base, assessment, handle, baseline, handoff);
    }

    private static PlanSpec.Step step(String id) {
        PlanSpec.ExecutorRef executor = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1");
        return new PlanSpec.Step(id, List.of("goal-1"), PlanSpec.ExecutionMode.FIXED, executor, null,
                List.of(), Map.of(), Map.of("metric", "pv"), "stats-v1");
    }

    private static PlanningAssessment assessment(int revision) {
        return new PlanningAssessment("plan-1", revision, "catalog-v1",
                List.of(new PlanningAssessment.Requirement("req-1", "goal-1",
                        PlanningAssessment.RequirementKind.DATA, true, "data/v1", "1", Map.of())), List.of(),
                List.of(new PlanningAssessment.Gap("req-1", PlanningAssessment.GapReason.NEEDS_INPUT,
                        "Input is missing.")));
    }

    private record Fixture(PlanSpec base, PlanningAssessment baseAssessment, CampaignRunHandle handle,
                           CampaignReplanPlanningHandoff.Baseline baseline,
                           CampaignReplanPlanningHandoff.Result handoff) {
        PlanSpec candidate() {
            return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 2, "run-1", "inputs-1", base.goals(),
                    List.of(step("step-1"), step("step-2")));
        }

        PlanningAssessment candidateAssessment() { return assessment(2); }

        RunToken token() {
            return new RunToken(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1,
                    "{\"planId\":\"plan-1\",\"runId\":\"run-1\",\"revision\":1}"), 1, "advance-1");
        }
    }
}
