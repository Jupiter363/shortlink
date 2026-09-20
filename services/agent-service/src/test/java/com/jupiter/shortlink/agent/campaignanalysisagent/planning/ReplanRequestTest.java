package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import static com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanTestFixtures.fixed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReplanRequestTest {
    @Test
    void evidenceGatePreservesOriginalQuestionsAndRejectsRevisionOnlyNoOp() {
        var fixture = fixed();
        var request = ReplanRequest.create(fixture.plan(), fixture.assessment(), Set.of("artifact-old"),
                List.of(new ReplanRequest.Evidence("evidence-new", "artifact-new", "stats-v1", "hash-new")),
                List.of(), "A newly published result changes the requested comparison.");

        var oldStep = fixture.plan().steps().get(0);
        var changedStep = new PlanSpec.Step(oldStep.stepId(), oldStep.goalIds(), oldStep.executionMode(),
                oldStep.executor(), oldStep.explorationPolicy(), oldStep.dependsOn(), oldStep.inputBindings(),
                Map.of("metric", "uv"), oldStep.outputContractRef());
        var candidate = new PlanSpec(fixture.plan().schemaVersion(), fixture.plan().planId(), 2,
                fixture.plan().runId(), fixture.plan().inputSetRef(), fixture.plan().goals(), List.of(changedStep));
        var candidateAssessment = new PlanningAssessment(candidate.planId(), candidate.revision(),
                fixture.assessment().capabilityCatalogVersion(), fixture.assessment().requirements(),
                fixture.assessment().coverageBindings(), fixture.assessment().gaps());

        var accepted = request.assess(candidate, candidateAssessment);
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.candidateRevision()).isEqualTo(2);
        assertThat(request.originalGoals()).containsExactlyElementsOf(fixture.plan().goals());
        assertThat(request.originalRequirements()).containsExactlyElementsOf(fixture.assessment().requirements());
        assertThat(request.requestHash()).hasSize(64);

        var revisionOnly = new PlanSpec(fixture.plan().schemaVersion(), fixture.plan().planId(), 2,
                fixture.plan().runId(), fixture.plan().inputSetRef(), fixture.plan().goals(), fixture.plan().steps());
        var revisionOnlyAssessment = new PlanningAssessment(revisionOnly.planId(), revisionOnly.revision(),
                fixture.assessment().capabilityCatalogVersion(), fixture.assessment().requirements(),
                fixture.assessment().coverageBindings(), fixture.assessment().gaps());
        var noOp = request.assess(revisionOnly, revisionOnlyAssessment);
        assertThat(noOp.status()).isEqualTo(ReplanRequest.ReplanAssessment.Status.REJECTED);
        assertThat(noOp.reasonCode()).isEqualTo(ReplanRequest.ReasonCode.EQUIVALENT_PLAN);

        var changedGoal = new PlanSpec.Goal("goal", "A rewritten question", true,
                "Deliver evidence and interpretation");
        var changedGoalPlan = new PlanSpec(fixture.plan().schemaVersion(), fixture.plan().planId(), 2,
                fixture.plan().runId(), fixture.plan().inputSetRef(), List.of(changedGoal), List.of(changedStep));
        var changedGoalAssessment = new PlanningAssessment(changedGoalPlan.planId(), changedGoalPlan.revision(),
                fixture.assessment().capabilityCatalogVersion(), fixture.assessment().requirements(),
                fixture.assessment().coverageBindings(), fixture.assessment().gaps());
        assertThat(request.assess(changedGoalPlan, changedGoalAssessment).reasonCode())
                .isEqualTo(ReplanRequest.ReasonCode.ORIGINAL_GOALS_CHANGED);

        assertThatThrownBy(() -> ReplanRequest.create(fixture.plan(), fixture.assessment(), Set.of("artifact-old"),
                List.of(new ReplanRequest.Evidence("evidence-new", "artifact-old", "stats-v1", "hash-new")),
                List.of(), "duplicate evidence"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_EVIDENCE_NOT_NEW");
        assertThatThrownBy(() -> ReplanRequest.create(fixture.plan(), fixture.assessment(), Set.of(),
                List.of(), List.of(), "no trigger"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPLAN_TRIGGER_MISSING");
    }
}
