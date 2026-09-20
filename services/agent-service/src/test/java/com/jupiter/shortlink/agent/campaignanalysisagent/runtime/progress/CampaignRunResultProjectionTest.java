package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportSection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignRunResultProjectionTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void projectsCompleteTypedResultAndStripsStorageMetadata() throws Exception {
        CampaignProgressView progress = progress("run-1", "plan-1", 2, RunStatus.ACTIVE,
                CampaignProgressView.WorkState.EXECUTED);
        CampaignReportReadProjection.Snapshot snapshot = snapshot("run-1", "plan-1", 2,
                List.of(goal("goal-1", GoalAssessment.Status.ANSWERED)), List.of(block("block-1")));

        CampaignRunResultProjection.Projection result = new CampaignRunResultProjection().project(
                progress, CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                Optional.of(snapshot), CampaignRunResultProjection.NextAction.none(),
                List.of("evidence window is fixed"));

        assertThat(result.schemaVersion()).isEqualTo(CampaignRunResultProjection.SCHEMA);
        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.SUCCEEDED);
        assertThat(result.report().reportRef()).isEqualTo(snapshot.reportRef());
        assertThat(result.report().goalRollup()).isEqualTo(
                new CampaignRunResultProjection.CampaignLegacyRollup(1, 1, 0, 0));
        assertThat(result.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.NONE);
        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("retainedUntil", "owner", "capability", "payloadJson", "reuseExpiresAt");
    }

    @Test
    void allowsWaitingAndRunningResultsWithoutPretendingTheyAreComplete() {
        CampaignRunResultProjection projection = new CampaignRunResultProjection();
        CampaignProgressView waiting = progress("run-2", "plan-2", 1, RunStatus.ACTIVE,
                CampaignProgressView.WorkState.WAITING);
        CampaignReportReadProjection.Snapshot partial = snapshot("run-2", "plan-2", 1,
                List.of(goal("goal-2", GoalAssessment.Status.PARTIAL)), List.of(block("block-2")));

        CampaignRunResultProjection.Projection waitingResult = projection.project(waiting,
                CampaignRunResultProjection.ExecutionStatus.WAITING, Optional.of(partial),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.NEEDS_INPUT,
                        "MISSING_SCOPE", List.of("groupId")), List.of());
        CampaignRunResultProjection.Projection runningResult = projection.project(
                progress("run-3", "plan-3", 1, RunStatus.ACTIVE, CampaignProgressView.WorkState.RUNNING),
                CampaignRunResultProjection.ExecutionStatus.RUNNING, Optional.empty(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.CONTINUE,
                        "STEP_IN_FLIGHT", List.of()), List.of());

        assertThat(waitingResult.report().goalRollup().answered()).isZero();
        assertThat(waitingResult.nextAction().requiredInputs()).containsExactly("groupId");
        assertThat(runningResult.report()).isNull();
    }

    @Test
    void rejectsIdentityDriftAndStaleOrIncompleteTerminalResults() {
        CampaignRunResultProjection projection = new CampaignRunResultProjection();
        CampaignReportReadProjection.Snapshot snapshot = snapshot("run-1", "plan-1", 2,
                List.of(goal("goal-1", GoalAssessment.Status.ANSWERED)), List.of(block("block-1")));

        assertThatThrownBy(() -> projection.project(progress("other-run", "plan-1", 2, RunStatus.ACTIVE,
                CampaignProgressView.WorkState.EXECUTED), CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                Optional.of(snapshot), CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> projection.project(progress("run-1", "plan-1", 2, RunStatus.CANCELLED,
                CampaignProgressView.WorkState.CANCELLED), CampaignRunResultProjection.ExecutionStatus.CANCELLED,
                Optional.of(snapshot), CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_STALE_REPORT");
        assertThatThrownBy(() -> projection.project(progress("run-1", "plan-1", 2, RunStatus.SUPERSEDED,
                CampaignProgressView.WorkState.SUPERSEDED), CampaignRunResultProjection.ExecutionStatus.SUPERSEDED,
                Optional.of(snapshot), CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_STALE_REPORT");
        CampaignReportReadProjection.Snapshot partial = snapshot("run-1", "plan-1", 2,
                List.of(goal("goal-1", GoalAssessment.Status.PARTIAL)), List.of(block("block-1")));
        assertThatThrownBy(() -> projection.project(progress("run-1", "plan-1", 2, RunStatus.ACTIVE,
                CampaignProgressView.WorkState.EXECUTED), CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                Optional.of(partial), CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_SUCCESS_INCOMPLETE");
        assertThatThrownBy(() -> projection.project(progress("run-1", "plan-1", 2, RunStatus.ACTIVE,
                CampaignProgressView.WorkState.EXECUTED), CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                Optional.empty(), CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_REPORT_REQUIRED");
    }

    @Test
    void enforcesTheFullProgressStateMatrixAndRejectsForgedSnapshotIdentity() {
        CampaignRunResultProjection projection = new CampaignRunResultProjection();
        assertThatThrownBy(() -> projection.project(
                progress("run-matrix", "plan-matrix", 1, RunStatus.ACTIVE,
                        CampaignProgressView.WorkState.PENDING),
                CampaignRunResultProjection.ExecutionStatus.RUNNING, Optional.empty(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.CONTINUE,
                        "START_NOT_RECORDED", List.of()), List.of()))
                .hasMessage("RUN_RESULT_RUNNING_STATE_MISMATCH");
        assertThatThrownBy(() -> projection.project(
                progress("run-matrix", "plan-matrix", 1, RunStatus.ACTIVE,
                        CampaignProgressView.WorkState.PENDING),
                CampaignRunResultProjection.ExecutionStatus.FAILED, Optional.empty(),
                CampaignRunResultProjection.NextAction.none(), List.of()))
                .hasMessage("RUN_RESULT_FAILED_STATE_MISMATCH");

        CampaignReportReadProjection.Snapshot complete = snapshot("run-matrix", "plan-matrix", 1,
                List.of(goal("goal-matrix", GoalAssessment.Status.ANSWERED)), List.of(block("block-matrix")));
        assertThatThrownBy(() -> projection.project(
                progress("run-matrix", "plan-matrix", 1, RunStatus.ACTIVE,
                        CampaignProgressView.WorkState.WAITING),
                CampaignRunResultProjection.ExecutionStatus.WAITING, Optional.of(complete),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "REMOTE_PENDING", List.of()), List.of()))
                .hasMessage("RUN_RESULT_COMPLETE_STATUS_MISMATCH");

        ReportDraft forgedDraft = new ReportDraft("different-report", 2, "different-run", "plan-matrix", 1,
                List.of(new ReportSection("summary", 0, "Summary", List.of("goal-matrix"),
                        List.of(block("block-matrix")))), List.of());
        CampaignReportReadProjection.Snapshot forged = new CampaignReportReadProjection.Snapshot(
                CampaignReportReadProjection.SCHEMA, ReportLifecycleStore.Mode.HISTORY_VIEW,
                new CampaignReportPublisher.ReportRef("report-matrix", 1), "run-matrix", 1, forgedDraft,
                List.of(goal("goal-matrix", GoalAssessment.Status.PARTIAL)), "b".repeat(64),
                NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400));
        assertThatThrownBy(() -> projection.project(
                progress("run-matrix", "plan-matrix", 1, RunStatus.ACTIVE,
                        CampaignProgressView.WorkState.WAITING),
                CampaignRunResultProjection.ExecutionStatus.WAITING, Optional.of(forged),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "REMOTE_PENDING", List.of()), List.of()))
                .hasMessage("RUN_RESULT_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsInvalidActionsAndKeepsCollectionsImmutable() {
        assertThatThrownBy(() -> new CampaignRunResultProjection.NextAction(
                CampaignRunResultProjection.NextActionKind.NONE, "SHOULD_NOT_EXIST", List.of()))
                .hasMessage("RUN_RESULT_NONE_ACTION_INVALID");
        assertThatThrownBy(() -> new CampaignRunResultProjection.NextAction(
                CampaignRunResultProjection.NextActionKind.NEEDS_INPUT, "MISSING_SCOPE", List.of()))
                .hasMessage("RUN_RESULT_INPUT_REQUIRED");

        CampaignRunResultProjection.Projection result = new CampaignRunResultProjection().project(
                progress("run-4", "plan-4", 1, RunStatus.ACTIVE, CampaignProgressView.WorkState.EXECUTED),
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED,
                Optional.of(snapshot("run-4", "plan-4", 1,
                        List.of(goal("goal-4", GoalAssessment.Status.ANSWERED)), List.of(block("block-4")))),
                CampaignRunResultProjection.NextAction.none(), List.of("bounded"));

        assertThatThrownBy(() -> result.goalAssessments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.limitations().add("new")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.report().blocks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static CampaignProgressView progress(String runId, String planId, int revision,
                                                  RunStatus status, CampaignProgressView.WorkState workState) {
        CampaignProgressView.GoalProgress goal = new CampaignProgressView.GoalProgress(
                runId.replaceFirst("^run-", "goal-"), "question", true, workState,
                List.of("step-1"), List.of(), List.of(), List.of());
        CampaignProgressView.StepProgress step = new CampaignProgressView.StepProgress(
                "step-1", List.of(goal.goalId()), StepStatus.SUCCEEDED, workState, null, List.of(), List.of(), List.of());
        return new CampaignProgressView(CampaignProgressView.SCHEMA, runId, planId, revision, status, workState,
                CampaignProgressView.DeliveryState.NOT_ASSESSED, List.of(goal), List.of(step));
    }

    private static CampaignReportReadProjection.Snapshot snapshot(String runId, String planId, int revision,
                                                                    List<GoalAssessment> goals, List<ReportBlock> blocks) {
        ReportDraft draft = new ReportDraft("report-" + runId, 1, runId, planId, revision,
                List.of(new ReportSection("summary", 0, "Summary",
                        goals.stream().map(GoalAssessment::goalId).toList(), blocks)), List.of());
        return new CampaignReportReadProjection.Snapshot(CampaignReportReadProjection.SCHEMA,
                ReportLifecycleStore.Mode.HISTORY_VIEW,
                new CampaignReportPublisher.ReportRef(draft.reportId(), draft.revision()), runId, revision,
                draft, goals, "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW.plusSeconds(2400));
    }

    private static GoalAssessment goal(String id, GoalAssessment.Status status) {
        return new GoalAssessment(id, status, status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + id), List.of(), List.of());
    }

    private static ReportBlock block(String id) {
        return new ReportBlock(id, ReportBlock.Kind.METRIC, "Metric", null,
                Map.of("value", 1), List.of("artifact-" + id), true);
    }
}
