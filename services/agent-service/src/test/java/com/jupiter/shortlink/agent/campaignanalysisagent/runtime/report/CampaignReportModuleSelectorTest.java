package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignReportModuleSelectorTest {
    private final CampaignReportModuleSelector selector = new CampaignReportModuleSelector();

    @Test
    void missingEvidenceCannotRemainAnsweredAndWaitingIsPreserved() {
        CampaignReportModuleResponse response = selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.WAITING,
                List.of(goal("z", GoalAssessment.Status.ANSWERED, List.of()),
                        goal("a", GoalAssessment.Status.PARTIAL, List.of("e-a"))), List.of(), Map.of()));

        assertThat(response.modules()).extracting(CampaignReportModuleResponse.Module::goalId)
                .containsExactly("a", "z");
        assertThat(response.modules().get(1).status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(response.modules().get(1).reasonCode()).isEqualTo("EVIDENCE_NOT_ASSESSED");
        assertThat(response.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.WAITING);
    }

    @Test
    void answeredGoalNeedsAReachableDeliverableBlock() {
        ReportBlock analysisOnly = new ReportBlock("analysis-1", ReportBlock.Kind.ANALYSIS,
                "解释", "text", Map.of(), List.of("e-a"), true);
        CampaignReportModuleResponse response = selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-a", GoalAssessment.Status.ANSWERED, List.of("e-a"))),
                List.of(analysisOnly), Map.of("analysis-1", List.of("goal-a"))));

        assertThat(response.modules().get(0).status()).isEqualTo(GoalAssessment.Status.PARTIAL);
        assertThat(response.modules().get(0).reasonCode()).isEqualTo("DELIVERY_NOT_RENDERABLE");

        ReportBlock metric = new ReportBlock("metric-1", ReportBlock.Kind.METRIC,
                "PV", "1", Map.of(), List.of("e-a"), true);
        CampaignReportModuleResponse complete = selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-a", GoalAssessment.Status.ANSWERED, List.of("e-a"))),
                List.of(metric), Map.of("metric-1", List.of("goal-a"))));
        assertThat(complete.modules().get(0).status()).isEqualTo(GoalAssessment.Status.ANSWERED);
    }

    @Test
    void modulesAreStableAndGoalOwnershipDoesNotCrossContaminate() {
        ReportBlock block = new ReportBlock("b-1", ReportBlock.Kind.METRIC, "Clicks", "ignored text",
                Map.of("privatePayload", "must not escape"), List.of("e-a"), true);
        CampaignReportModuleResponse response = selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.UNKNOWN,
                List.of(goal("goal-b", GoalAssessment.Status.UNAVAILABLE, List.of()),
                        goal("goal-a", GoalAssessment.Status.PARTIAL, List.of())),
                List.of(block), Map.of("b-1", List.of("goal-a"))));

        assertThat(response.modules()).extracting(CampaignReportModuleResponse.Module::goalId)
                .containsExactly("goal-a", "goal-b");
        assertThat(response.modules().get(0).blocks()).hasSize(1);
        assertThat(response.modules().get(1).blocks()).isEmpty();
        assertThat(response.modules().get(0).blocks().get(0)).hasNoNullFieldsOrProperties();
        assertThat(CampaignReportModuleResponse.RenderableBlock.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("blockId", "kind", "title", "evidenceArtifactIds", "completeResult");
    }

    @Test
    void outputCollectionsAreImmutableAndPrivateMetadataIsNotAcceptedAsOutput() {
        CampaignReportModuleResponse response = selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.RUNNING,
                List.of(goal("goal-a", GoalAssessment.Status.PARTIAL, List.of("e-a"))), List.of(), Map.of()));

        assertThatThrownBy(() -> response.modules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.modules().get(0).evidenceArtifactIds().add("e-b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(CampaignReportModuleResponse.Module.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("owner", "token", "payload", "capability");
    }

    @Test
    void rejectsAmbiguousOrUnknownBlockOwnership() {
        ReportBlock block = new ReportBlock("b-1", ReportBlock.Kind.METRIC, "PV", "1",
                Map.of(), List.of("e-a"), true);
        assertThatThrownBy(() -> selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-a", GoalAssessment.Status.PARTIAL, List.of("e-a")),
                        goal("goal-b", GoalAssessment.Status.PARTIAL, List.of("e-b"))),
                List.of(block), Map.of("b-1", List.of("goal-a", "goal-b")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_MODULE_BLOCK_SCOPE_AMBIGUOUS");
        assertThatThrownBy(() -> selector.select(request(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-a", GoalAssessment.Status.PARTIAL, List.of("e-a"))),
                List.of(block), Map.of("missing", List.of("goal-a")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REPORT_MODULE_SCOPE_INVALID");
    }

    private static CampaignReportModuleSelector.Request request(
            CampaignRunResultProjection.ExecutionStatus status, List<GoalAssessment> goals,
            List<ReportBlock> blocks, Map<String, List<String>> blockGoals) {
        return new CampaignReportModuleSelector.Request("run-1", "plan-1", 1, status, goals, blocks, blockGoals);
    }

    private static GoalAssessment goal(String id, GoalAssessment.Status status, List<String> evidence) {
        return new GoalAssessment(id, status, null, evidence, List.of(), List.of());
    }
}
