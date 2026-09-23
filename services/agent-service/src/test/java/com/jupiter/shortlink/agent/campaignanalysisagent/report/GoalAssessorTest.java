package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoalAssessorTest {
    @Test
    void aCompletedStatisticCannotSatisfyAnotherTypedDeliveryRequirement() {
        PlanSpec plan = plan("goal-1");
        PlanningAssessment assessment = assessment(new PlanningAssessment.Requirement("dimensions", "goal-1",
                PlanningAssessment.RequirementKind.DELIVERY, true, "dimension-change-delivery", "1", Map.of()));
        ReportDraft draft = draft("goal-1", new ReportBlock("metric", ReportBlock.Kind.METRIC,
                "Metric", null, Map.of("value", 1), List.of("statistics"), true));
        GoalAssessment result = assess(plan, assessment, Map.of("dimensions", new GoalAssessor.RequirementObservation(
                RequirementAssessment.Verdict.UNKNOWN, "EVIDENCE_NOT_ASSESSED", List.of())), draft).goals().get(0);
        assertThat(result.status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(result.reasonCode()).isEqualTo("DELIVERY_MISSING");
    }

    @Test
    void singleToolWithoutCausalAnalysisCannotBeAnswered() {
        PlanSpec plan = plan("goal-1");
        PlanningAssessment assessment = assessment(requirement("causal", "goal-1",
                PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE));
        ReportDraft draft = draft("goal-1", new ReportBlock("metric", ReportBlock.Kind.METRIC,
                "Metric", null, Map.of("value", 1), List.of("artifact-1"), true));

        GoalAssessment result = assess(plan, assessment,
                Map.of("causal", observation(RequirementAssessment.Verdict.MET, "artifact-1")), draft)
                .goals().get(0);

        assertThat(result.status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(result.reasonCode()).isEqualTo("ANALYSIS_MISSING");
    }

    @Test
    void jointDistributionCannotSatisfyACausalRequirement() {
        PlanSpec plan = plan("goal-1");
        PlanningAssessment assessment = assessment(requirement("causal", "goal-1",
                PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE));
        ReportBlock joint = new ReportBlock("analysis", ReportBlock.Kind.ANALYSIS,
                "联合分布", null, Map.of("claimType", "CAUSAL", "method", "joint_distribution",
                        "coverage", Map.of("status", "COMPLETE"), "limitations", List.of("仅观察相关性")),
                List.of("artifact-1"), true);
        GoalAssessment result = assess(plan, assessment,
                Map.of("causal", observation(RequirementAssessment.Verdict.MET, "artifact-1")), draft("goal-1", joint))
                .goals().get(0);
        assertThat(result.status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(result.reasonCode()).isEqualTo("ANALYSIS_MISSING");
    }

    @Test
    void causalMethodRequiresCompleteCoverageAndExplicitLimitations() {
        PlanSpec plan = plan("goal-1");
        PlanningAssessment assessment = assessment(requirement("causal", "goal-1",
                PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE));
        ReportBlock causal = new ReportBlock("analysis", ReportBlock.Kind.ANALYSIS,
                "因果估计", null, Map.of("claimType", "CAUSAL", "method", "difference_in_differences",
                        "coverage", Map.of("status", "COMPLETE", "periods", 2),
                        "limitations", List.of("需在实验前提下解释")), List.of("artifact-1"), true);
        GoalAssessment result = assess(plan, assessment,
                Map.of("causal", observation(RequirementAssessment.Verdict.MET, "artifact-1")), draft("goal-1", causal))
                .goals().get(0);
        assertThat(result.status()).isEqualTo(GoalAssessment.Status.ANSWERED);
    }

    @Test
    void partialEvidenceIsPartialAndEvidenceDoesNotCrossGoals() {
        PlanSpec plan = plan("goal-1", "goal-2");
        PlanningAssessment assessment = assessment(
                requirement("data-1", "goal-1", PlanningAssessment.RequirementKind.DATA),
                requirement("data-2", "goal-2", PlanningAssessment.RequirementKind.DATA));
        ReportDraft draft = draft("goal-1", new ReportBlock("metric", ReportBlock.Kind.METRIC,
                "Metric", null, Map.of("value", 1), List.of("artifact-1"), true));
        GoalAssessor.Result result = new GoalAssessor().assess(new GoalAssessor.Input(
                plan, assessment, Map.of("data-1", observation(RequirementAssessment.Verdict.MET, "artifact-1")), draft));

        assertThat(result.goals().get(0).status()).isEqualTo(GoalAssessment.Status.ANSWERED);
        assertThat(result.goals().get(1).status()).isEqualTo(GoalAssessment.Status.PENDING);
        assertThat(result.goals().get(1).evidenceArtifactIds()).isEmpty();
    }

    @Test
    void waitingUnavailableAndUnsupportedNeverBecomeCompleted() {
        PlanSpec plan = plan("goal-1", "goal-2", "goal-3");
        PlanningAssessment assessment = assessment(
                requirement("waiting", "goal-1", PlanningAssessment.RequirementKind.DATA),
                requirement("unavailable", "goal-2", PlanningAssessment.RequirementKind.DATA),
                requirement("unsupported", "goal-3", PlanningAssessment.RequirementKind.DATA));
        Map<String, GoalAssessor.RequirementObservation> observations = Map.of(
                "waiting", observation(RequirementAssessment.Verdict.UNKNOWN, "NEEDS_INPUT"),
                "unavailable", observation(RequirementAssessment.Verdict.UNKNOWN, "EVIDENCE_UNAVAILABLE"),
                "unsupported", observation(RequirementAssessment.Verdict.UNKNOWN, "UNSUPPORTED"));
        GoalAssessor.Result result = assess(plan, assessment, observations, emptyDraft());

        assertThat(result.goals()).extracting(GoalAssessment::status).containsExactly(
                GoalAssessment.Status.NEEDS_INPUT, GoalAssessment.Status.UNAVAILABLE,
                GoalAssessment.Status.UNSUPPORTED);
        assertThat(result.goals()).allMatch(goal -> goal.status() != GoalAssessment.Status.ANSWERED);
    }

    @Test
    void goalWithoutRequirementsRemainsPending() {
        PlanSpec plan = plan("goal-1");
        GoalAssessor.Result result = assess(plan,
                new PlanningAssessment("plan-1", 1, "catalog", List.of(), List.of(), List.of()),
                Map.of(), emptyDraft());

        assertThat(result.goals()).singleElement().satisfies(goal -> {
            assertThat(goal.status()).isEqualTo(GoalAssessment.Status.PENDING);
            assertThat(goal.reasonCode()).isEqualTo("PENDING_EVIDENCE");
        });
    }

    @Test
    void planAssessmentAndDraftIdentityDriftFailsClosed() {
        PlanSpec plan = plan("goal-1");
        PlanningAssessment assessment = assessment(requirement("data", "goal-1",
                PlanningAssessment.RequirementKind.DATA));
        ReportDraft wrongRun = new ReportDraft("report-1", 1, "other-run", "plan-1", 1, List.of(), List.of());
        assertThatThrownBy(() -> assess(plan, assessment, Map.of(), wrongRun))
                .hasMessage("REPORT_DEFINITION_MISMATCH");
        PlanningAssessment wrongRevision = new PlanningAssessment("plan-1", 2, "catalog",
                List.of(requirement("data", "goal-1", PlanningAssessment.RequirementKind.DATA)), List.of(), List.of());
        assertThatThrownBy(() -> assess(plan, wrongRevision, Map.of(), emptyDraft()))
                .hasMessage("REPORT_DEFINITION_MISMATCH");
    }

    private static GoalAssessor.Result assess(PlanSpec plan, PlanningAssessment assessment,
                                         Map<String, GoalAssessor.RequirementObservation> observations,
                                         ReportDraft draft) {
        return new GoalAssessor().assess(new GoalAssessor.Input(plan, assessment, observations, draft));
    }

    private static PlanSpec plan(String... goalIds) {
        return new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                java.util.Arrays.stream(goalIds).map(id -> new PlanSpec.Goal(id, id, true, "deliver"))
                        .toList(), List.of());
    }

    private static PlanningAssessment assessment(PlanningAssessment.Requirement... requirements) {
        return new PlanningAssessment("plan-1", 1, "catalog", List.of(requirements), List.of(), List.of());
    }

    private static PlanningAssessment.Requirement requirement(String id, String goal,
                                                              PlanningAssessment.RequirementKind kind) {
        return new PlanningAssessment.Requirement(id, goal, kind, true, kind.name(), "1", Map.of());
    }

    private static GoalAssessor.RequirementObservation observation(RequirementAssessment.Verdict verdict,
                                                                   String... refsOrReason) {
        if (verdict == RequirementAssessment.Verdict.UNKNOWN)
            return new GoalAssessor.RequirementObservation(verdict, refsOrReason[0], List.of());
        return new GoalAssessor.RequirementObservation(verdict, null, List.of(refsOrReason[0]));
    }

    private static ReportDraft draft(String goal, ReportBlock block) {
        return new ReportDraft("report-1", 1, "run-1", "plan-1", 1,
                List.of(new ReportSection("section-1", 0, "Section", List.of(goal), List.of(block))), List.of());
    }

    private static ReportDraft emptyDraft() {
        return new ReportDraft("report-1", 1, "run-1", "plan-1", 1, List.of(), List.of());
    }
}
