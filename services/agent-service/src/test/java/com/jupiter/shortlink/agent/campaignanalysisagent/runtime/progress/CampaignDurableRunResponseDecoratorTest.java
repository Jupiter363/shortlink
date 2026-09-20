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

class CampaignDurableRunResponseDecoratorTest {
    private static final Caller CALLER = new Caller("tenant", "user", 1);
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void makesAnAbsentBindingExplicitWithoutReadingProgressOrReport() {
        RecordingStore store = new RecordingStore(null);
        CampaignDurableRunResultReadService reads = new CampaignDurableRunResultReadService(
                store, new CampaignRunReportReadProjection((caller, runId) -> {
                    throw new AssertionError("progress must not be read without a binding");
                }, request -> {
                    throw new AssertionError("report must not be read without a binding");
                }));

        CampaignDurableRunResponseDecorator.Outcome outcome = decorator(reads).decorate(request("plan", 2));

        assertThat(outcome.status())
                .isEqualTo(CampaignDurableRunResponseDecorator.Outcome.Status.NO_BINDING);
        assertThat(outcome.response()).isEmpty();
    }

    @Test
    void composesBoundResponseOnlyWhenTheServerHandleMatchesPlan() throws Exception {
        CampaignReportReadProjection.Snapshot report = snapshot("run", "plan", 2, true);
        CampaignRunResultStore.Binding binding = binding("run", "plan", 2,
                CampaignRunResultProjection.ExecutionStatus.SUCCEEDED, report.reportRef(),
                CampaignRunResultProjection.NextAction.none());
        CampaignDurableRunResponseDecorator.Outcome outcome = decorator(reads(binding,
                progress("run", "plan", 2, CampaignProgressView.WorkState.EXECUTED), Optional.of(report)))
                .decorate(requestWithAccess("plan", 2));

        assertThat(outcome.status())
                .isEqualTo(CampaignDurableRunResponseDecorator.Outcome.Status.BOUND_RESPONSE);
        AgentRunResult result = outcome.response().orElseThrow();
        assertThat(result.report().availability())
                .isEqualTo(com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(result.pendingActions()).containsExactly("pending");
        assertThat(result.toolCalls()).containsExactly("tool");
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("owner", "capability", "payloadJson", "retainedUntil", "reuseExpiresAt");
    }

    @Test
    void rejectsPlanIdentityDriftBeforeResponseAdaptation() {
        CampaignRunResultStore.Binding binding = binding("run", "other-plan", 2,
                CampaignRunResultProjection.ExecutionStatus.WAITING, null,
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResponseDecorator decorator = decorator(reads(binding,
                progress("run", "other-plan", 2, CampaignProgressView.WorkState.WAITING), Optional.empty()));

        assertThatThrownBy(() -> decorator.decorate(request("plan", 2)))
                .hasMessage("RUN_RESULT_PLAN_IDENTITY_MISMATCH");
    }

    @Test
    void requiresReportAccessForAReportBindingAndDoesNotFallback() {
        CampaignReportReadProjection.Snapshot report = snapshot("run", "plan", 2, false);
        CampaignRunResultStore.Binding binding = binding("run", "plan", 2,
                CampaignRunResultProjection.ExecutionStatus.WAITING, report.reportRef(),
                new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                        "RESULT_PENDING", List.of()));
        CampaignDurableRunResponseDecorator decorator = decorator(reads(binding,
                progress("run", "plan", 2, CampaignProgressView.WorkState.WAITING), Optional.of(report)));

        assertThatThrownBy(() -> decorator.decorate(request("plan", 2)))
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");
    }

    private static CampaignDurableRunResponseDecorator decorator(
            CampaignDurableRunResultReadService reads) {
        return new CampaignDurableRunResponseDecorator(
                new CampaignDurableRunResponseService(reads, new CampaignDurableRunResponseAdapter()));
    }

    private static CampaignDurableRunResponseDecorator.Request request(String planId, int revision) {
        return new CampaignDurableRunResponseDecorator.Request(base(),
                new CampaignDurableRunResultReadService.Request(CALLER, "run", revision), planId);
    }

    private static CampaignDurableRunResponseDecorator.Request requestWithAccess(String planId, int revision) {
        return new CampaignDurableRunResponseDecorator.Request(base(),
                new CampaignDurableRunResultReadService.Request(CALLER, "run", revision,
                        Optional.of(new CampaignDurableRunResultReadService.ReportAccess(
                                "owner", "campaign/report/v1", ReportLifecycleStore.Mode.HISTORY_VIEW))), planId);
    }

    private static CampaignDurableRunResultReadService reads(CampaignRunResultStore.Binding binding,
                                                               CampaignProgressView progress,
                                                               Optional<CampaignReportReadProjection.Snapshot> report) {
        return new CampaignDurableRunResultReadService(new RecordingStore(binding),
                new CampaignRunReportReadProjection((caller, runId) -> progress, request -> report));
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
                                                                    int revision, boolean complete) {
        String goalId = "goal-" + runId;
        GoalAssessment goal = new GoalAssessment(goalId,
                complete ? GoalAssessment.Status.ANSWERED : GoalAssessment.Status.PARTIAL,
                complete ? null : "INCOMPLETE", List.of("artifact-" + goalId), List.of(), List.of());
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

        private RecordingStore(Binding value) {
            this.value = value;
        }

        @Override
        public Binding bind(RunToken token, BindingDraft draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Binding> read(Caller caller, String runId, int revision) {
            return Optional.ofNullable(value);
        }
    }
}
