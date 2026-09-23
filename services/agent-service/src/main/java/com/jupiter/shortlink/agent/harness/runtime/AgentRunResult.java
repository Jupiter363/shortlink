package com.jupiter.shortlink.agent.harness.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportView;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignReportModuleResponse;
import java.util.List;

public record AgentRunResult(
        String sessionId,
        String traceId,
        String answer,
        List<Object> cards,
        List<Object> pendingActions,
        List<Object> toolCalls,
        List<Object> dataSources,
        List<Object> traceEvents,
        List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_NULL) Report report,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgentRunRequest.Continuation continuation,
        @JsonInclude(JsonInclude.Include.NON_NULL) Progress progress
) {
    public record Progress(String runId, String planId, int planRevision,
            CampaignRunResultProjection.ExecutionStatus executionStatus,
            CampaignRunResultProjection.NextAction nextAction,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<GoalProgress> goals) {
        public Progress {
            goals = goals == null ? null : List.copyOf(goals);
        }
        public Progress(String runId, String planId, int planRevision,
                CampaignRunResultProjection.ExecutionStatus executionStatus,
                CampaignRunResultProjection.NextAction nextAction) {
            this(runId, planId, planRevision, executionStatus, nextAction, null);
        }
    }

    /** Goal visibility does not imply an evidence-backed report has been published. */
    public record GoalProgress(String goalId, String title, GoalAssessment.Status status,
            List<String> limitations) {
        public GoalProgress {
            if (goalId == null || goalId.isBlank() || title == null || title.isBlank() || status == null)
                throw new IllegalArgumentException("AGENT_GOAL_PROGRESS_INVALID");
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public AgentRunResult(String sessionId, String traceId, String answer, List<Object> cards,
            List<Object> pendingActions, List<Object> toolCalls, List<Object> dataSources,
            List<Object> traceEvents, List<String> warnings, Report report) {
        this(sessionId, traceId, answer, cards, pendingActions, toolCalls, dataSources, traceEvents, warnings,
                report, null, null);
    }
    /** Backward-compatible constructor used by the existing campaign and risk graph paths. */
    public AgentRunResult(
            String sessionId,
            String traceId,
            String answer,
            List<Object> cards,
            List<Object> pendingActions,
            List<Object> toolCalls,
            List<Object> dataSources,
            List<Object> traceEvents,
            List<String> warnings) {
        this(sessionId, traceId, answer, cards, pendingActions, toolCalls, dataSources,
                traceEvents, warnings, null);
    }

    /**
     * Sanitized campaign report view carried at the response boundary.
     *
     * <p>This deliberately does not contain the durable read projection or its retention and
     * authorization metadata.  The adapter copies only the renderable report facts after the
     * report/status consistency checks have passed.</p>
     */
    public record Report(
            String schemaVersion,
            CampaignLegacyAnswerAdapter.Availability availability,
            CampaignLegacyAnswerAdapter.ExecutionStatus executionStatus,
            String answer,
            CampaignReportPublisher.ReportRef reportRef,
            List<ReportBlock> blocks,
            List<GoalAssessment> goalAssessments,
            CampaignLegacyAnswerAdapter.GoalRollup goalRollup,
            List<String> limitations,
            @JsonInclude(JsonInclude.Include.NON_NULL) CampaignReportModuleResponse modules,
            @JsonInclude(JsonInclude.Include.NON_NULL) CampaignReportView view) {
        public Report {
            if (!CampaignLegacyAnswerAdapter.SCHEMA.equals(schemaVersion)
                    || availability == null || executionStatus == null
                    || answer == null || answer.isBlank() || goalRollup == null) {
                throw new IllegalArgumentException("AGENT_REPORT_VIEW_INVALID");
            }
            boolean statusConsistent = switch (availability) {
                case EMPTY -> executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY
                        || executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.RUNNING
                        || executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING;
                case UNAVAILABLE -> executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED
                        || executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.CANCELLED
                        || executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN;
                case COMPLETE -> executionStatus == CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED;
                case PARTIAL -> executionStatus != CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY;
            };
            if (!statusConsistent) throw new IllegalArgumentException("AGENT_REPORT_STATUS_CONFLICT");
            if ((availability == CampaignLegacyAnswerAdapter.Availability.COMPLETE
                    || availability == CampaignLegacyAnswerAdapter.Availability.PARTIAL)
                    && reportRef == null) {
                throw new IllegalArgumentException("AGENT_REPORT_REF_REQUIRED");
            }
            blocks = immutable(blocks);
            goalAssessments = immutable(goalAssessments);
            limitations = immutable(limitations);
        }

        public Report(String schemaVersion, CampaignLegacyAnswerAdapter.Availability availability,
                      CampaignLegacyAnswerAdapter.ExecutionStatus executionStatus, String answer,
                      CampaignReportPublisher.ReportRef reportRef, List<ReportBlock> blocks,
                      List<GoalAssessment> goalAssessments, CampaignLegacyAnswerAdapter.GoalRollup goalRollup,
                      List<String> limitations, CampaignReportModuleResponse modules) {
            this(schemaVersion, availability, executionStatus, answer, reportRef, blocks, goalAssessments,
                    goalRollup, limitations, modules, null);
        }

        /** Backward-compatible report constructor; modules are optional for legacy callers. */
        public Report(String schemaVersion,
                      CampaignLegacyAnswerAdapter.Availability availability,
                      CampaignLegacyAnswerAdapter.ExecutionStatus executionStatus,
                      String answer,
                      CampaignReportPublisher.ReportRef reportRef,
                      List<ReportBlock> blocks,
                      List<GoalAssessment> goalAssessments,
                      CampaignLegacyAnswerAdapter.GoalRollup goalRollup,
                      List<String> limitations) {
            this(schemaVersion, availability, executionStatus, answer, reportRef, blocks,
                    goalAssessments, goalRollup, limitations, null);
        }

        private static <T> List<T> immutable(List<T> values) {
            return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
        }
    }
}
