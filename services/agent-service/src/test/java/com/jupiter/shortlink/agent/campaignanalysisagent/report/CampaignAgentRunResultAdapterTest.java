package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignAgentRunResultAdapterTest {
    @Test
    void oldConstructorAndJsonRemainCompatibleWhenReportIsAbsent() throws Exception {
        AgentRunResult result = new AgentRunResult("session", "trace", "answer", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).contains("\"sessionId\":\"session\"").contains("\"warnings\":[]");
        assertThat(json).doesNotContain("\"report\"");
        assertThat(new ObjectMapper().readValue(json, AgentRunResult.class).report()).isNull();
    }

    @Test
    void adaptsCompleteAndPreservesLegacyFieldsImmutably() {
        List<Object> cards = new ArrayList<>(List.of(Map.of("type", "card")));
        AgentRunResult base = new AgentRunResult("session", "trace", "untrusted answer", cards,
                List.of("pending"), List.of("tool"), List.of("data"), List.of("trace"), List.of("warning"));
        AgentRunResult result = new CampaignAgentRunResultAdapter().adapt(request(base, view(
                CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED, CampaignLegacyAnswerAdapter.Availability.COMPLETE)));
        assertThat(result.report().availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(result.answer()).isEqualTo(result.report().answer());
        assertThat(result.answer()).isNotEqualTo("untrusted answer");
        assertThat(result.pendingActions()).containsExactly("pending");
        assertThatThrownBy(() -> result.cards().add(Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void adaptsPartialWaitingAndFailedStates() {
        CampaignAgentRunResultAdapter adapter = new CampaignAgentRunResultAdapter();
        AgentRunResult base = new AgentRunResult("session", "trace", "old", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThat(adapter.adapt(request(base, view(CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING,
                CampaignLegacyAnswerAdapter.Availability.PARTIAL))).report().availability())
                .isEqualTo(CampaignLegacyAnswerAdapter.Availability.PARTIAL);
        CampaignLegacyAnswerAdapter.LegacyView failed = new CampaignLegacyAnswerAdapter().adapt(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED, List.of("failed"));
        assertThat(adapter.adapt(request(base, failed)).report().availability())
                .isEqualTo(CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE);
    }

    @Test
    void rejectsNullAndContradictoryInputs() {
        CampaignAgentRunResultAdapter adapter = new CampaignAgentRunResultAdapter();
        assertThatThrownBy(() -> adapter.adapt(null)).hasMessage("AGENT_RESULT_REQUEST_REQUIRED");
        CampaignLegacyAnswerAdapter.LegacyView complete = view(CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED,
                CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        CampaignLegacyAnswerAdapter.LegacyView contradictory = new CampaignLegacyAnswerAdapter.LegacyView(
                CampaignLegacyAnswerAdapter.SCHEMA, CampaignLegacyAnswerAdapter.Availability.COMPLETE,
                CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED, complete.answer(), complete.snapshot(),
                complete.blocks(), complete.goalAssessments(), complete.goalRollup(), complete.limitations());
        AgentRunResult base = new AgentRunResult("session", "trace", "old", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> adapter.adapt(request(base, contradictory)))
                .hasMessage("AGENT_RESULT_STATUS_CONFLICT");
        CampaignLegacyAnswerAdapter.LegacyView empty = new CampaignLegacyAnswerAdapter().adapt(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY, List.of());
        CampaignLegacyAnswerAdapter.LegacyView successWithoutReport = new CampaignLegacyAnswerAdapter.LegacyView(
                CampaignLegacyAnswerAdapter.SCHEMA, CampaignLegacyAnswerAdapter.Availability.EMPTY,
                CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED, empty.answer(), null, List.of(), List.of(),
                empty.goalRollup(), empty.limitations());
        assertThatThrownBy(() -> adapter.adapt(request(base, successWithoutReport)))
                .hasMessage("AGENT_RESULT_STATUS_CONFLICT");
        assertThatThrownBy(() -> new AgentRunResult.Report(
                CampaignLegacyAnswerAdapter.SCHEMA, CampaignLegacyAnswerAdapter.Availability.COMPLETE,
                CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED, complete.answer(), complete.snapshot().reportRef(),
                complete.blocks(), complete.goalAssessments(), complete.goalRollup(), complete.limitations()))
                .hasMessage("AGENT_REPORT_STATUS_CONFLICT");
    }

    private static CampaignAgentRunResultAdapter.Request request(AgentRunResult base,
                                                                  CampaignLegacyAnswerAdapter.LegacyView view) {
        return new CampaignAgentRunResultAdapter.Request(base, view);
    }

    private static CampaignLegacyAnswerAdapter.LegacyView view(CampaignLegacyAnswerAdapter.ExecutionStatus status,
                                                               CampaignLegacyAnswerAdapter.Availability expected) {
        CampaignReportReadProjection.Snapshot snapshot = new CampaignReportReadProjection.Snapshot(
                CampaignReportReadProjection.SCHEMA, ReportLifecycleStore.Mode.HISTORY_VIEW,
                new CampaignReportPublisher.ReportRef("report", 1), "run", 1,
                new ReportDraft("report", 1, "run", "plan", 1,
                        List.of(new ReportSection("summary", 0, "Summary", List.of("goal"), List.of(
                                new ReportBlock("block", ReportBlock.Kind.METRIC, "Metric", null,
                                        Map.of("value", 1), List.of("artifact"), true)))), List.of()),
                List.of(new GoalAssessment("goal", expected == CampaignLegacyAnswerAdapter.Availability.COMPLETE
                        ? GoalAssessment.Status.ANSWERED : GoalAssessment.Status.PARTIAL, "INCOMPLETE",
                        List.of("artifact"), List.of(), List.of())), "a".repeat(64),
                Instant.parse("2026-09-20T09:00:00Z"), Instant.parse("2026-09-20T08:30:00Z"),
                Instant.parse("2026-09-20T08:45:00Z"));
        return new CampaignLegacyAnswerAdapter().adapt(snapshot, status, List.of());
    }
}
