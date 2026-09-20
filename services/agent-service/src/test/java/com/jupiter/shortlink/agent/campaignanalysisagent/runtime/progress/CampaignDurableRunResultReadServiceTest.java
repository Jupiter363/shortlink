package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportSection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignDurableRunResultReadServiceTest {
    private static final Caller CALLER = new Caller("tenant", "user", 1);
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void readsTheRequestedRevisionWithoutLatestFallbackAndNeedsNoReportCredentialsWhenAbsent() {
        RecordingStore store = new RecordingStore(null);
        CampaignRunReportReadProjection projection = projection(
                progress("run", "plan", 4, CampaignProgressView.WorkState.PENDING, List.of()), Optional.empty());
        CampaignDurableRunResultReadService service = new CampaignDurableRunResultReadService(store, projection);

        assertThat(service.read(new CampaignDurableRunResultReadService.Request(CALLER, "run", 4))).isEmpty();
        assertThat(store.requestedRevision).isEqualTo(4);
    }

    @Test
    void composesAWaitingPartialReportAndKeepsTheSanitizedProjection() {
        CampaignReportReadProjection.Snapshot report = snapshot("run", "plan", 2, GoalAssessment.Status.PARTIAL);
        CampaignRunResultStore.Binding binding = binding(CampaignRunResultProjection.ExecutionStatus.WAITING,
                new CampaignReportPublisher.ReportRef("report-run", 1),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResultReadService service = new CampaignDurableRunResultReadService(
                new RecordingStore(binding), projection(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING, List.of()),
                        Optional.of(report)));

        CampaignDurableRunResultReadService.Request request = new CampaignDurableRunResultReadService.Request(
                CALLER, "run", 2, Optional.of(new CampaignDurableRunResultReadService.ReportAccess(
                        "owner", "campaign/report/v1", ReportLifecycleStore.Mode.HISTORY_VIEW)));
        CampaignRunResultProjection.Projection result = service.read(request).orElseThrow();

        assertThat(result.executionStatus()).isEqualTo(CampaignRunResultProjection.ExecutionStatus.WAITING);
        assertThat(result.report().reportRef()).isEqualTo(report.reportRef());
        assertThat(result.nextAction()).isEqualTo(binding.nextAction());
        assertThatThrownBy(() -> result.report().blocks().add(result.report().blocks().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAReportBindingWithoutExplicitReportAccess() {
        CampaignReportPublisher.ReportRef ref = new CampaignReportPublisher.ReportRef("report-run", 1);
        CampaignRunResultStore.Binding binding = binding(CampaignRunResultProjection.ExecutionStatus.WAITING, ref,
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResultReadService service = new CampaignDurableRunResultReadService(
                new RecordingStore(binding), projection(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING, List.of()), Optional.empty()));

        assertThatThrownBy(() -> service.read(new CampaignDurableRunResultReadService.Request(CALLER, "run", 2)))
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");
    }

    @Test
    void rejectsWhenDurableStatusDisagreesWithCurrentProgress() {
        CampaignRunResultStore.Binding binding = binding(CampaignRunResultProjection.ExecutionStatus.RUNNING, null,
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.CONTINUE,
                        "RUN_IN_PROGRESS", List.of()));
        CampaignDurableRunResultReadService service = new CampaignDurableRunResultReadService(
                new RecordingStore(binding), projection(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING, List.of()), Optional.empty()));

        assertThatThrownBy(() -> service.read(new CampaignDurableRunResultReadService.Request(CALLER, "run", 2)))
                .hasMessage("RUN_RESULT_BINDING_FACT_MISMATCH");
    }

    private static CampaignRunResultStore.Binding binding(CampaignRunResultProjection.ExecutionStatus status,
                                                           CampaignReportPublisher.ReportRef ref,
                                                           CampaignRunResultProjection.NextAction action) {
        return new CampaignRunResultStore.Binding(CampaignRunResultStore.SCHEMA, "run", "plan", 2, status, ref,
                action, List.of(), 0, "token", 1, NOW, NOW);
    }

    private static CampaignRunReportReadProjection projection(CampaignProgressView progress,
                                                                Optional<CampaignReportReadProjection.Snapshot> report) {
        return new CampaignRunReportReadProjection((caller, runId) -> {
            assertThat(caller).isEqualTo(CALLER);
            assertThat(runId).isEqualTo(progress.runId());
            return progress;
        }, request -> report);
    }

    private static CampaignProgressView progress(String runId, String planId, int revision,
                                                 CampaignProgressView.WorkState workState,
                                                 List<PlanningAssessment.Gap> gaps) {
        String goalId = "goal-" + runId;
        CampaignProgressView.GoalProgress goal = new CampaignProgressView.GoalProgress(
                goalId, "question", true, workState, List.of("step-1"), gaps, List.of(), List.of());
        CampaignProgressView.StepProgress step = new CampaignProgressView.StepProgress(
                "step-1", List.of(goalId), StepStatus.SUCCEEDED, workState, null, List.of(), List.of(), List.of());
        return new CampaignProgressView(CampaignProgressView.SCHEMA, runId, planId, revision,
                com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus.ACTIVE,
                workState, CampaignProgressView.DeliveryState.NOT_ASSESSED, List.of(goal), List.of(step));
    }

    private static CampaignReportReadProjection.Snapshot snapshot(String runId, String planId, int revision,
                                                                   GoalAssessment.Status status) {
        String goalId = "goal-" + runId;
        GoalAssessment goal = new GoalAssessment(goalId, status,
                status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + goalId), List.of(), List.of());
        ReportBlock block = new ReportBlock("block-" + goalId, ReportBlock.Kind.METRIC, "Metric", null,
                Map.of("value", 1), List.of("artifact-" + goalId), true);
        ReportDraft draft = new ReportDraft("report-" + runId, 1, runId, planId, revision,
                List.of(new ReportSection("summary", 0, "Summary", List.of(goalId), List.of(block))), List.of());
        return new CampaignReportReadProjection.Snapshot(CampaignReportReadProjection.SCHEMA,
                ReportLifecycleStore.Mode.HISTORY_VIEW,
                new CampaignReportPublisher.ReportRef(draft.reportId(), draft.revision()), runId, revision,
                draft, List.of(goal), "a".repeat(64), NOW.plusSeconds(3600), NOW.plusSeconds(1800),
                NOW.plusSeconds(2400));
    }

    private static final class RecordingStore implements CampaignRunResultStore {
        private final Binding value;
        private int requestedRevision;
        private RecordingStore(Binding value) { this.value = value; }
        @Override public Binding bind(RunToken token, BindingDraft draft) { throw new UnsupportedOperationException(); }
        @Override public Optional<Binding> read(Caller caller, String runId, int revision) {
            requestedRevision = revision;
            return Optional.ofNullable(value);
        }
    }
}
