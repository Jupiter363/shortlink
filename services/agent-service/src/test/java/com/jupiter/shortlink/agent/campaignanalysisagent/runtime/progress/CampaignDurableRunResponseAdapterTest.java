package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignDurableRunResponseAdapterTest {
    @Test
    void composesCompleteProjectionAndKeepsOperationalFields() throws Exception {
        List<Object> cards = new ArrayList<>(List.of(Map.of("kind", "metric")));
        AgentRunResult base = base(cards);
        CampaignRunResultProjection.Projection projection = projection(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-1", GoalAssessment.Status.ANSWERED)),
                List.of(block("block-1", true)),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.NONE,
                        null, List.of()), List.of("FIXED_WINDOW"));

        AgentRunResult result = new CampaignDurableRunResponseAdapter().adapt(base, projection);

        assertThat(result.answer()).isEqualTo(result.report().answer());
        assertThat(result.answer()).contains("分析报告已完成");
        assertThat(result.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(result.report().executionStatus())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED);
        assertThat(result.report().reportRef()).isEqualTo(new CampaignReportPublisher.ReportRef("report-run-1", 1));
        assertThat(result.report().goalRollup().answered()).isEqualTo(1);
        assertThat(result.cards()).containsExactly(Map.of("kind", "metric"));
        assertThat(result.pendingActions()).containsExactly("pending");
        assertThat(result.toolCalls()).containsExactly("tool");
        assertThat(result.dataSources()).containsExactly("source");
        assertThat(result.traceEvents()).containsExactly("trace-event");
        assertThat(result.warnings()).containsExactly("warning");
        assertThatThrownBy(() -> result.cards().add(Map.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.report().blocks().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.report().goalAssessments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.report().limitations().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);

        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("owner", "capability", "payloadJson", "retainedUntil", "reuseExpiresAt");
    }

    @Test
    void keepsWaitingAndUnknownPartialReportsPartial() {
        CampaignDurableRunResponseAdapter adapter = new CampaignDurableRunResponseAdapter();
        AgentRunResult base = base(List.of());
        CampaignRunResultProjection.Projection waiting = projection(
                CampaignRunResultProjection.ExecutionStatus.WAITING,
                List.of(goal("goal-2", GoalAssessment.Status.PARTIAL)),
                List.of(block("block-2", false)),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()), List.of());
        CampaignRunResultProjection.Projection unknown = projection(
                CampaignRunResultProjection.ExecutionStatus.UNKNOWN,
                List.of(goal("goal-3", GoalAssessment.Status.PARTIAL)),
                List.of(block("block-3", false)), CampaignRunResultProjection.NextAction.none(), List.of());

        assertThat(adapter.adapt(base, waiting).report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.PARTIAL);
        assertThat(adapter.adapt(base, unknown).report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.PARTIAL);
    }

    @Test
    void returnsEmptyForMissingBindingAndUnavailableForFailureWithoutReport() {
        CampaignDurableRunResponseAdapter adapter = new CampaignDurableRunResponseAdapter();
        AgentRunResult base = base(List.of());
        assertThat(adapter.adapt(base, Optional.empty())).isEmpty();

        CampaignRunResultProjection.Projection failed = projection(
                CampaignRunResultProjection.ExecutionStatus.FAILED, List.of(), List.of(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.RETRY,
                        "RUN_FAILED", List.of()), List.of("RUN_FAILED"));
        AgentRunResult result = adapter.adapt(base, failed);
        assertThat(result.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE);
        assertThat(result.report().executionStatus())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED);
    }

    @Test
    void rejectsMissingOptionalsIdentityDriftAndContradictoryEvidence() {
        CampaignDurableRunResponseAdapter adapter = new CampaignDurableRunResponseAdapter();
        AgentRunResult base = base(List.of());
        CampaignRunResultProjection.Projection complete = projection(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-4", GoalAssessment.Status.ANSWERED)), List.of(block("block-4", true)),
                CampaignRunResultProjection.NextAction.none(), List.of());

        assertThatThrownBy(() -> adapter.adapt(base, (Optional<CampaignRunResultProjection.Projection>) null))
                .hasMessage("AGENT_RESULT_PROJECTION_REQUIRED");
        assertThatThrownBy(() -> adapter.adapt(new CampaignDurableRunResponseAdapter.Request(base,
                Optional.of(new CampaignDurableRunResponseAdapter.Identity("other", "plan-1", 1)),
                Optional.of(complete))))
                .hasMessage("AGENT_RESULT_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> adapter.adapt(base, projection(
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                List.of(goal("goal-5", GoalAssessment.Status.PARTIAL)), List.of(block("block-5", false)),
                CampaignRunResultProjection.NextAction.none(), List.of())))
                .hasMessage("AGENT_RESULT_SUCCESS_INCOMPLETE");
        assertThatThrownBy(() -> adapter.adapt(base, projection(
                CampaignRunResultProjection.ExecutionStatus.WAITING,
                List.of(goal("goal-6", GoalAssessment.Status.ANSWERED)), List.of(block("block-6", true)),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()), List.of())))
                .hasMessage("AGENT_RESULT_COMPLETE_STATUS_MISMATCH");
        assertThatThrownBy(() -> adapter.adapt(base, withReport(
                CampaignRunResultProjection.ExecutionStatus.CANCELLED,
                List.of(goal("goal-7", GoalAssessment.Status.PARTIAL)), List.of(block("block-7", false)),
                CampaignRunResultProjection.NextAction.none(), List.of())))
                .hasMessage("AGENT_RESULT_STALE_REPORT");
        assertThatThrownBy(() -> adapter.adapt(base, projection(
                CampaignRunResultProjection.ExecutionStatus.SUPERSEDED, List.of(), List.of(),
                CampaignRunResultProjection.NextAction.none(), List.of())))
                .hasMessage("AGENT_RESULT_STATUS_UNSUPPORTED");
    }

    @Test
    void keepsFailedPartialAndRejectsForgedRollup() {
        CampaignDurableRunResponseAdapter adapter = new CampaignDurableRunResponseAdapter();
        AgentRunResult base = base(List.of());
        AgentRunResult failedPartial = adapter.adapt(base, withReport(
                CampaignRunResultProjection.ExecutionStatus.FAILED,
                List.of(goal("goal-8", GoalAssessment.Status.PARTIAL)), List.of(block("block-8", false)),
                CampaignRunResultProjection.NextAction.none(), List.of()));
        assertThat(failedPartial.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.PARTIAL);

        CampaignRunResultProjection.ReportSummary forged = new CampaignRunResultProjection.ReportSummary(
                new CampaignReportPublisher.ReportRef("report-run-1", 1), List.of(block("block-9", false)),
                new CampaignRunResultProjection.CampaignLegacyRollup(1, 1, 0, 0));
        CampaignRunResultProjection.Projection projection = new CampaignRunResultProjection.Projection(
                CampaignRunResultProjection.SCHEMA, CampaignRunResultProjection.ExecutionStatus.WAITING,
                "run-1", "plan-1", 1, List.of(goal("goal-9", GoalAssessment.Status.PARTIAL)), forged,
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()), List.of());
        assertThatThrownBy(() -> adapter.adapt(base, projection)).hasMessage("AGENT_RESULT_ROLLUP_MISMATCH");

        assertThatThrownBy(() -> adapter.adapt(base, withReport(
                CampaignRunResultProjection.ExecutionStatus.WAITING, List.of(), List.of(block("block-10", false)),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()), List.of())))
                .hasMessage("AGENT_RESULT_REPORT_EMPTY");
    }

    private static AgentRunResult base(List<Object> cards) {
        return new AgentRunResult("session", "trace", "untrusted", cards,
                List.of("pending"), List.of("tool"), List.of("source"), List.of("trace-event"),
                List.of("warning"));
    }

    private static CampaignRunResultProjection.Projection projection(
            CampaignRunResultProjection.ExecutionStatus status,
            List<GoalAssessment> goals,
            List<ReportBlock> blocks,
            CampaignRunResultProjection.NextAction action,
            List<String> limitations) {
        CampaignRunResultProjection.ReportSummary report = status == CampaignRunResultProjection.ExecutionStatus.EMPTY
                || status == CampaignRunResultProjection.ExecutionStatus.FAILED
                || status == CampaignRunResultProjection.ExecutionStatus.CANCELLED
                || status == CampaignRunResultProjection.ExecutionStatus.SUPERSEDED
                ? null
                : new CampaignRunResultProjection.ReportSummary(
                        new CampaignReportPublisher.ReportRef("report-run-1", 1), blocks,
                        rollup(goals));
        return new CampaignRunResultProjection.Projection(CampaignRunResultProjection.SCHEMA, status,
                "run-1", "plan-1", 1, goals, report, action, limitations);
    }

    private static CampaignRunResultProjection.Projection withReport(
            CampaignRunResultProjection.ExecutionStatus status,
            List<GoalAssessment> goals,
            List<ReportBlock> blocks,
            CampaignRunResultProjection.NextAction action,
            List<String> limitations) {
        return new CampaignRunResultProjection.Projection(CampaignRunResultProjection.SCHEMA, status,
                "run-1", "plan-1", 1, goals,
                new CampaignRunResultProjection.ReportSummary(
                        new CampaignReportPublisher.ReportRef("report-run-1", 1), blocks, rollup(goals)),
                action, limitations);
    }

    private static CampaignRunResultProjection.CampaignLegacyRollup rollup(List<GoalAssessment> goals) {
        int answered = 0;
        int partial = 0;
        int unresolved = 0;
        for (GoalAssessment goal : goals) {
            switch (goal.status()) {
                case ANSWERED -> answered++;
                case PARTIAL -> partial++;
                default -> unresolved++;
            }
        }
        return new CampaignRunResultProjection.CampaignLegacyRollup(goals.size(), answered, partial, unresolved);
    }

    private static GoalAssessment goal(String id, GoalAssessment.Status status) {
        return new GoalAssessment(id, status,
                status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + id), List.of(), List.of());
    }

    private static ReportBlock block(String id, boolean complete) {
        return new ReportBlock(id, ReportBlock.Kind.METRIC, "Metric", null,
                Map.of("value", 1), List.of("artifact-" + id), complete);
    }
}
