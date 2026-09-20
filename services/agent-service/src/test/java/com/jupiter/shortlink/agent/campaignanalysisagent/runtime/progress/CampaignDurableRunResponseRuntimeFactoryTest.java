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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignDurableRunResponseRuntimeFactoryTest {
    private static final Caller CALLER = new Caller("tenant", "user", 1);
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void requiresBothTypedDependencies() {
        CampaignRunReportReadProjection reports = emptyReports();
        assertThatThrownBy(() -> new CampaignDurableRunResponseRuntimeFactory(null, reports))
                .hasMessage("RUN_RESULT_STORE_REQUIRED");
        assertThatThrownBy(() -> new CampaignDurableRunResponseRuntimeFactory(new RecordingStore(null), null))
                .hasMessage("RUN_REPORT_PROJECTION_REQUIRED");
    }

    @Test
    void createsFreshRequestIndependentServicesAndPreservesEmptyBinding() {
        RecordingStore store = new RecordingStore(null);
        CampaignDurableRunResponseRuntimeFactory factory =
                new CampaignDurableRunResponseRuntimeFactory(store, emptyReports());
        CampaignDurableRunResponseService first = factory.create();
        CampaignDurableRunResponseService second = factory.create();

        assertThat(first).isNotSameAs(second);
        CampaignDurableRunResultReadService.Request request =
                new CampaignDurableRunResultReadService.Request(CALLER, "run", 4);
        assertThat(first.read(new CampaignDurableRunResponseService.Request(base(), request))).isEmpty();
        assertThat(second.read(new CampaignDurableRunResponseService.Request(base(), request))).isEmpty();
        assertThat(store.readCount).isEqualTo(2);
    }

    @Test
    void composesWaitingPartialAndSucceededThroughFreshTypedAssembly() throws Exception {
        CampaignReportReadProjection.Snapshot partial = snapshot("run", "plan", 2,
                GoalAssessment.Status.PARTIAL, false);
        CampaignRunResultStore.Binding waitingBinding = binding("run", "plan", 2,
                CampaignRunResultProjection.ExecutionStatus.WAITING, partial.reportRef(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResponseService waiting = new CampaignDurableRunResponseRuntimeFactory(
                new RecordingStore(waitingBinding), reports(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING), Optional.of(partial)))
                .create();
        AgentRunResult waitingResult = waiting.read(new CampaignDurableRunResponseService.Request(
                base(), new CampaignDurableRunResultReadService.Request(CALLER, "run", 2,
                        Optional.of(access())))).orElseThrow();
        assertThat(waitingResult.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.PARTIAL);

        CampaignReportReadProjection.Snapshot complete = snapshot("run", "plan", 3,
                GoalAssessment.Status.ANSWERED, true);
        CampaignRunResultStore.Binding successBinding = binding("run", "plan", 3,
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED, complete.reportRef(),
                CampaignRunResultProjection.NextAction.none());
        CampaignDurableRunResponseService succeeded = new CampaignDurableRunResponseRuntimeFactory(
                new RecordingStore(successBinding), reports(
                        progress("run", "plan", 3, CampaignProgressView.WorkState.EXECUTED), Optional.of(complete)))
                .create();
        AgentRunResult successResult = succeeded.read(new CampaignDurableRunResponseService.Request(
                base(), new CampaignDurableRunResultReadService.Request(CALLER, "run", 3,
                        Optional.of(access())))).orElseThrow();
        assertThat(successResult.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(successResult.report().executionStatus())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED);
        assertThat(successResult.pendingActions()).containsExactly("pending");
        assertThat(new ObjectMapper().writeValueAsString(successResult))
                .doesNotContain("owner", "capability", "payloadJson", "retainedUntil", "reuseExpiresAt");
    }

    @Test
    void propagatesExactReadIdentityAndAccessFailures() {
        CampaignRunResultStore.Binding wrong = binding("other", "plan", 2,
                CampaignRunResultProjection.ExecutionStatus.WAITING, null,
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResponseService identity = new CampaignDurableRunResponseRuntimeFactory(
                new RecordingStore(wrong), reports(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING), Optional.empty()))
                .create();
        assertThatThrownBy(() -> identity.read(new CampaignDurableRunResponseService.Request(
                base(), new CampaignDurableRunResultReadService.Request(CALLER, "run", 2))))
                .hasMessage("RUN_RESULT_IDENTITY_MISMATCH");

        CampaignReportReadProjection.Snapshot report = snapshot("run", "plan", 2,
                GoalAssessment.Status.PARTIAL, false);
        CampaignRunResultStore.Binding withReport = binding("run", "plan", 2,
                CampaignRunResultProjection.ExecutionStatus.WAITING, report.reportRef(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResponseService access = new CampaignDurableRunResponseRuntimeFactory(
                new RecordingStore(withReport), reports(
                        progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING), Optional.of(report)))
                .create();
        assertThatThrownBy(() -> access.read(new CampaignDurableRunResponseService.Request(
                base(), new CampaignDurableRunResultReadService.Request(CALLER, "run", 2))))
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");
    }

    private static CampaignRunReportReadProjection emptyReports() {
        return new CampaignRunReportReadProjection((caller, runId) -> {
            throw new AssertionError("progress must not be read without a binding");
        }, request -> {
            throw new AssertionError("report must not be read without a binding");
        });
    }

    private static CampaignRunReportReadProjection reports(CampaignProgressView progress,
                                                             Optional<CampaignReportReadProjection.Snapshot> report) {
        return new CampaignRunReportReadProjection((caller, runId) -> {
            assertThat(caller).isEqualTo(CALLER);
            assertThat(runId).isEqualTo(progress.runId());
            return progress;
        }, request -> report);
    }

    private static CampaignDurableRunResultReadService.ReportAccess access() {
        return new CampaignDurableRunResultReadService.ReportAccess(
                "owner", "campaign/report/v1", ReportLifecycleStore.Mode.HISTORY_VIEW);
    }

    private static AgentRunResult base() {
        return new AgentRunResult("session", "trace", "old-answer", List.of(Map.of("card", 1)),
                List.of("pending"), List.of("tool"), List.of("source"), List.of("trace"),
                List.of("warning"));
    }

    private static CampaignRunResultStore.Binding binding(String runId, String planId, int revision,
                                                           CampaignRunResultProjection.ExecutionStatus status,
                                                           CampaignReportPublisher.ReportRef reportRef,
                                                           CampaignRunResultProjection.NextAction action) {
        return new CampaignRunResultStore.Binding(CampaignRunResultStore.SCHEMA, runId, planId, revision,
                status, reportRef, action, List.of(), 0, "token", 1, NOW, NOW);
    }

    private static CampaignProgressView progress(String runId, String planId, int revision,
                                                  CampaignProgressView.WorkState workState) {
        String goalId = "goal-" + runId;
        CampaignProgressView.GoalProgress goal = new CampaignProgressView.GoalProgress(
                goalId, "question", true, workState, List.of("step-1"), List.of(), List.of(), List.of());
        CampaignProgressView.StepProgress step = new CampaignProgressView.StepProgress(
                "step-1", List.of(goalId), StepStatus.SUCCEEDED, workState, null, List.of(), List.of(), List.of());
        return new CampaignProgressView(CampaignProgressView.SCHEMA, runId, planId, revision,
                CampaignRunStore.RunStatus.ACTIVE, workState, CampaignProgressView.DeliveryState.NOT_ASSESSED,
                List.of(goal), List.of(step));
    }

    private static CampaignReportReadProjection.Snapshot snapshot(String runId, String planId,
                                                                    int revision,
                                                                    GoalAssessment.Status status,
                                                                    boolean complete) {
        String goalId = "goal-" + runId;
        GoalAssessment goal = new GoalAssessment(goalId, status,
                status == GoalAssessment.Status.ANSWERED ? null : "INCOMPLETE",
                List.of("artifact-" + goalId), List.of(), List.of());
        ReportBlock block = new ReportBlock("block-" + goalId, ReportBlock.Kind.METRIC, "Metric", null,
                Map.of("value", 1), List.of("artifact-" + goalId), complete);
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
        private int readCount;

        private RecordingStore(Binding value) {
            this.value = value;
        }

        @Override
        public Binding bind(RunToken token, BindingDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Binding> read(Caller caller, String runId, int revision) {
            readCount++;
            return Optional.ofNullable(value);
        }
    }
}
