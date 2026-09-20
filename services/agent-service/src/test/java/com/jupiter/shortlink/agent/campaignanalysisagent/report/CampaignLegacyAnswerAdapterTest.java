package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignLegacyAnswerAdapterTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final String REPORT_ID = "legacy-report";
    private static final int REVISION = 3;

    @Test
    void allAnsweredGoalsWithDeliverablesBecomeComplete() {
        CampaignReportReadProjection.Snapshot snapshot = snapshot(
                List.of(goal("goal-a", GoalAssessment.Status.ANSWERED),
                        goal("goal-b", GoalAssessment.Status.ANSWERED)),
                List.of(block("block-a", "goal-a"), block("block-b", "goal-b")));

        CampaignLegacyAnswerAdapter.LegacyView view = new CampaignLegacyAnswerAdapter().adapt(
                new CampaignLegacyAnswerAdapter.Request(snapshot,
                        CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED,
                        List.of("source snapshot retained")));

        assertThat(view.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(view.goalRollup()).isEqualTo(new CampaignLegacyAnswerAdapter.GoalRollup(2, 2, 0, 0));
        assertThat(view.answer()).isEqualTo("分析报告已完成（报告 legacy-report · 第3版），已回答 2 个目标。");
        assertThat(view.limitations()).containsExactly("source snapshot retained");
        assertThatThrownBy(() -> view.limitations().add("new limitation"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void partialAndUnknownExecutionNeverBecomeComplete() {
        CampaignReportReadProjection.Snapshot snapshot = snapshot(
                List.of(goal("goal-a", GoalAssessment.Status.ANSWERED),
                        goal("goal-b", GoalAssessment.Status.PARTIAL)),
                List.of(block("block-a", "goal-a"), block("block-b", "goal-b")));
        CampaignLegacyAnswerAdapter adapter = new CampaignLegacyAnswerAdapter();

        CampaignLegacyAnswerAdapter.LegacyView waiting = adapter.adapt(snapshot,
                CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING, List.of());
        CampaignLegacyAnswerAdapter.LegacyView unknown = adapter.adapt(snapshot,
                CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN, List.of());

        assertThat(waiting.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.PARTIAL);
        assertThat(unknown.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.PARTIAL);
        assertThat(waiting.goalRollup()).isEqualTo(new CampaignLegacyAnswerAdapter.GoalRollup(2, 1, 1, 0));
        assertThat(waiting.answer()).contains("1/2");
    }

    @Test
    void emptyAndUnavailableStatesRemainDistinctWithoutAReport() {
        CampaignLegacyAnswerAdapter adapter = new CampaignLegacyAnswerAdapter();

        CampaignLegacyAnswerAdapter.LegacyView empty = adapter.adapt(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY, null);
        CampaignLegacyAnswerAdapter.LegacyView unavailable = adapter.adapt(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED, List.of("provider unavailable"));
        CampaignLegacyAnswerAdapter.LegacyView unavailableSnapshot = adapter.adapt(snapshot(List.of(), List.of()),
                CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED, List.of());

        assertThat(empty.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.EMPTY);
        assertThat(empty.answer()).isEqualTo("当前没有可展示的分析结果。");
        assertThat(unavailable.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE);
        assertThat(unavailableSnapshot.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE);
        assertThat(unavailable.answer()).isEqualTo("当前分析结果不可用，未生成可用报告。");
        assertThat(unavailable.limitations()).containsExactly("provider unavailable");
    }

    @Test
    void fullTypedSnapshotAndBlocksAreRetainedAndImmutable() {
        List<GoalAssessment> goals = List.of(goal("goal-a", GoalAssessment.Status.ANSWERED));
        List<ReportBlock> blocks = List.of(block("block-a", "goal-a"), block("block-extra", "goal-a"));
        CampaignReportReadProjection.Snapshot snapshot = snapshot(goals, blocks);

        CampaignLegacyAnswerAdapter.LegacyView view = new CampaignLegacyAnswerAdapter().adapt(snapshot,
                CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED, List.of());

        assertThat(view.snapshot()).isSameAs(snapshot);
        assertThat(view.blocks()).containsExactlyElementsOf(blocks);
        assertThat(view.goalAssessments()).containsExactlyElementsOf(goals);
        assertThatThrownBy(() -> view.blocks().add(block("new", "goal-a")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.goalAssessments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contradictoryOrUntrustedInputsAreRejected() {
        CampaignLegacyAnswerAdapter adapter = new CampaignLegacyAnswerAdapter();
        CampaignReportReadProjection.Snapshot snapshot = snapshot(
                List.of(goal("goal-a", GoalAssessment.Status.PARTIAL)),
                List.of(block("block-a", "goal-a")));

        assertThatThrownBy(() -> adapter.adapt(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LEGACY_SUCCESS_REPORT_REQUIRED");
        assertThatThrownBy(() -> adapter.adapt(snapshot,
                CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LEGACY_EMPTY_REPORT_MISMATCH");
        assertThatThrownBy(() -> new CampaignLegacyAnswerAdapter.Request(snapshot,
                CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LEGACY_LIMITATION_INVALID");
        CampaignLegacyAnswerAdapter.Request request = new CampaignLegacyAnswerAdapter.Request(null,
                CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY, List.of("retained"));
        assertThatThrownBy(() -> request.limitations().add("mutate"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new CampaignLegacyAnswerAdapter.LegacyView(
                CampaignLegacyAnswerAdapter.SCHEMA,
                CampaignLegacyAnswerAdapter.Availability.EMPTY,
                CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY,
                "当前没有可展示的分析结果。", null, List.of(), List.of(),
                new CampaignLegacyAnswerAdapter.GoalRollup(0, 0, 0, 0), List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LEGACY_LIMITATION_INVALID");
    }

    private static CampaignReportReadProjection.Snapshot snapshot(List<GoalAssessment> goals,
                                                                   List<ReportBlock> blocks) {
        ReportDraft draft = new ReportDraft(REPORT_ID, REVISION, "legacy-run", "legacy-plan", REVISION,
                List.of(new ReportSection("summary", 0, "Summary",
                        goals.stream().map(GoalAssessment::goalId).toList(), blocks)), List.of());
        return new CampaignReportReadProjection.Snapshot(
                CampaignReportReadProjection.SCHEMA,
                ReportLifecycleStore.Mode.HISTORY_VIEW,
                new CampaignReportPublisher.ReportRef(REPORT_ID, REVISION),
                "legacy-run", REVISION, draft, goals, "a".repeat(64),
                NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400));
    }

    private static GoalAssessment goal(String id, GoalAssessment.Status status) {
        return new GoalAssessment(id, status, status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + id), List.of(), List.of());
    }

    private static ReportBlock block(String id, String goalId) {
        return new ReportBlock(id, ReportBlock.Kind.METRIC, "Metric " + goalId, null,
                Map.of("value", 1), List.of("artifact-" + goalId), true);
    }
}
