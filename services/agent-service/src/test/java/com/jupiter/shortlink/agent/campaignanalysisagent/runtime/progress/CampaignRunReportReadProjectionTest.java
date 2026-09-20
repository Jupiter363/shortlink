package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportSection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignRunReportReadProjectionTest {
    private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");
    private static final Caller CALLER = new Caller("tenant", "subject", 7);

    @Test
    void readsOneAuthorizedReportKeyAndProjectsACompleteResult() throws Exception {
        List<CampaignReportApplicationService.ReadRequest> reads = new ArrayList<>();
        CampaignReportReadProjection.Snapshot report = snapshot("run-1", "plan-1", 1,
                GoalAssessment.Status.ANSWERED, ReportLifecycleStore.Mode.EXPORT);
        CampaignRunReportReadProjection projection = projection(
                progress("run-1", "plan-1", 1, CampaignProgressView.WorkState.EXECUTED, List.of()),
                reads, Optional.of(report));

        CampaignRunResultProjection.Projection result = projection.read(new CampaignRunReportReadProjection.Request(
                CALLER, "run-1", Optional.of(new CampaignRunReportReadProjection.ReportRead(
                        report.reportRef(), "owner", "campaign/report/v1", ReportLifecycleStore.Mode.EXPORT))));

        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.SUCCEEDED);
        assertThat(result.report().reportRef()).isEqualTo(report.reportRef());
        assertThat(result.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.NONE);
        assertThat(reads).singleElement().satisfies(request -> {
            assertThat(request.owner()).isEqualTo("owner");
            assertThat(request.capability()).isEqualTo("campaign/report/v1");
            assertThat(request.mode()).isEqualTo(ReportLifecycleStore.Mode.EXPORT);
        });
        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("owner", "capability", "retainedUntil", "manifestJson", "payloadJson");
    }

    @Test
    void keepsPartialReportAndWaitingActionTogether() {
        CampaignReportReadProjection.Snapshot report = snapshot("run-2", "plan-2", 1,
                GoalAssessment.Status.PARTIAL);
        CampaignRunResultProjection.Projection result = projection(
                progress("run-2", "plan-2", 1, CampaignProgressView.WorkState.WAITING, List.of()),
                new ArrayList<>(), Optional.of(report)).read(new CampaignRunReportReadProjection.Request(
                CALLER, "run-2", Optional.of(read(report))));

        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.WAITING);
        assertThat(result.report().goalRollup().partial()).isEqualTo(1);
        assertThat(result.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.WAIT);
        assertThat(result.nextAction().reasonCode()).isEqualTo("RESULT_PENDING");
    }

    @Test
    void doesNotScanHistoryWhenNoReportReferenceWasResolved() {
        List<CampaignReportApplicationService.ReadRequest> reads = new ArrayList<>();
        CampaignRunReportReadProjection projection = projection(
                progress("run-3", "plan-3", 1, CampaignProgressView.WorkState.EXECUTED, List.of()),
                reads, Optional.empty());

        CampaignRunResultProjection.Projection result = projection.read(
                new CampaignRunReportReadProjection.Request(CALLER, "run-3"));

        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.UNKNOWN);
        assertThat(result.report()).isNull();
        assertThat(result.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.WAIT);
        assertThat(result.nextAction().reasonCode()).isEqualTo("REPORT_NOT_AVAILABLE");
        assertThat(result.limitations()).containsExactly("REPORT_REFERENCE_REQUIRED");
        assertThat(reads).isEmpty();
    }

    @Test
    void distinguishesMissingRequestedReportFromAnUnresolvedReference() {
        List<CampaignReportApplicationService.ReadRequest> reads = new ArrayList<>();
        CampaignReportReadProjection.Snapshot expected = snapshot("run-4", "plan-4", 1,
                GoalAssessment.Status.ANSWERED);
        CampaignRunReportReadProjection projection = projection(
                progress("run-4", "plan-4", 1, CampaignProgressView.WorkState.EXECUTED, List.of()),
                reads, Optional.empty());

        CampaignRunResultProjection.Projection result = projection.read(
                new CampaignRunReportReadProjection.Request(CALLER, "run-4", Optional.of(read(expected))));

        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.UNKNOWN);
        assertThat(result.limitations()).containsExactly("REPORT_NOT_AVAILABLE");
        assertThat(result.nextAction().reasonCode()).isEqualTo("REPORT_NOT_AVAILABLE");
        assertThat(reads).hasSize(1);
    }

    @Test
    void mapsTypedPlanningGapsAndFailuresWithoutFreeTextActions() {
        PlanningAssessment.Gap gap = new PlanningAssessment.Gap("requirement-1",
                PlanningAssessment.GapReason.NEEDS_INPUT, "do not expose this explanation");
        CampaignRunReportReadProjection projection = projection(
                progress("run-5", "plan-5", 1, CampaignProgressView.WorkState.BLOCKED, List.of(gap)),
                new ArrayList<>(), Optional.empty());

        CampaignRunResultProjection.Projection blocked = projection.read(
                new CampaignRunReportReadProjection.Request(CALLER, "run-5"));
        CampaignRunResultProjection.Projection failed = projection(
                progress("run-6", "plan-6", 1, CampaignProgressView.WorkState.FAILED, List.of()),
                new ArrayList<>(), Optional.empty()).read(new CampaignRunReportReadProjection.Request(CALLER, "run-6"));

        assertThat(blocked.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.WAITING);
        assertThat(blocked.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.NEEDS_INPUT);
        assertThat(blocked.nextAction().requiredInputs()).containsExactly("requirement-1");
        assertThat(blocked.limitations()).containsExactly("PLANNING_GAP_NEEDS_INPUT");
        assertThat(failed.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.FAILED);
        assertThat(failed.nextAction().kind()).isEqualTo(CampaignRunResultProjection.NextActionKind.RETRY);
        assertThat(failed.nextAction().reasonCode()).isEqualTo("RUN_FAILED");
    }

    @Test
    void failsClosedWhenTheAuthorizedReportBelongsToAnotherRun() {
        CampaignReportReadProjection.Snapshot wrong = snapshot("other-run", "plan-7", 1,
                GoalAssessment.Status.PARTIAL);
        CampaignRunReportReadProjection projection = projection(
                progress("run-7", "plan-7", 1, CampaignProgressView.WorkState.WAITING, List.of()),
                new ArrayList<>(), Optional.of(wrong));

        assertThatThrownBy(() -> projection.read(new CampaignRunReportReadProjection.Request(
                CALLER, "run-7", Optional.of(read(wrong)))))
                .hasMessage("RUN_RESULT_IDENTITY_MISMATCH");

        assertThatThrownBy(() -> projection.read(new CampaignRunReportReadProjection.Request(
                CALLER, "run-7", Optional.of(new CampaignRunReportReadProjection.ReportRead(
                        new CampaignReportPublisher.ReportRef("requested-other-report", 1), "owner",
                        "campaign/report/v1", ReportLifecycleStore.Mode.EXPORT)))))
                .hasMessage("RUN_RESULT_REPORT_IDENTITY_MISMATCH");
    }

    private static CampaignRunReportReadProjection projection(CampaignProgressView progress,
                                                                List<CampaignReportApplicationService.ReadRequest> reads,
                                                                Optional<CampaignReportReadProjection.Snapshot> report) {
        return new CampaignRunReportReadProjection(
                (caller, runId) -> {
                    assertThat(caller).isEqualTo(CALLER);
                    assertThat(runId).isEqualTo(progress.runId());
                    return progress;
                }, request -> {
                    reads.add(request);
                    return report;
                });
    }

    private static CampaignRunReportReadProjection.ReportRead read(CampaignReportReadProjection.Snapshot report) {
        return new CampaignRunReportReadProjection.ReportRead(report.reportRef(), "owner",
                "campaign/report/v1", ReportLifecycleStore.Mode.HISTORY_VIEW);
    }

    private static CampaignProgressView progress(String runId, String planId, int revision,
                                                  CampaignProgressView.WorkState workState,
                                                  List<PlanningAssessment.Gap> gaps) {
        String goalId = "goal-" + runId.substring("run-".length());
        CampaignProgressView.GoalProgress goal = new CampaignProgressView.GoalProgress(
                goalId, "question", true, workState, List.of("step-1"), gaps, List.of(), List.of());
        CampaignProgressView.StepProgress step = new CampaignProgressView.StepProgress(
                "step-1", List.of(goalId), StepStatus.SUCCEEDED, workState, null, List.of(), List.of(), List.of());
        return new CampaignProgressView(CampaignProgressView.SCHEMA, runId, planId, revision, RunStatus.ACTIVE,
                workState, CampaignProgressView.DeliveryState.NOT_ASSESSED, List.of(goal), List.of(step));
    }

    private static CampaignReportReadProjection.Snapshot snapshot(String runId, String planId, int revision,
                                                                    GoalAssessment.Status status) {
        return snapshot(runId, planId, revision, status, ReportLifecycleStore.Mode.HISTORY_VIEW);
    }

    private static CampaignReportReadProjection.Snapshot snapshot(String runId, String planId, int revision,
                                                                    GoalAssessment.Status status,
                                                                    ReportLifecycleStore.Mode mode) {
        String goalId = "goal-" + runId.substring("run-".length());
        GoalAssessment goal = new GoalAssessment(goalId, status,
                status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + goalId), List.of(), List.of());
        ReportBlock block = new ReportBlock("block-" + goalId, ReportBlock.Kind.METRIC, "Metric", null,
                Map.of("value", 1), List.of("artifact-" + goalId), true);
        ReportDraft draft = new ReportDraft("report-" + runId, 1, runId, planId, revision,
                List.of(new ReportSection("summary", 0, "Summary", List.of(goalId), List.of(block))), List.of());
        return new CampaignReportReadProjection.Snapshot(CampaignReportReadProjection.SCHEMA,
                mode,
                new CampaignReportPublisher.ReportRef(draft.reportId(), draft.revision()), runId, revision,
                draft, List.of(goal), "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800),
                NOW.plusSeconds(2400));
    }
}
