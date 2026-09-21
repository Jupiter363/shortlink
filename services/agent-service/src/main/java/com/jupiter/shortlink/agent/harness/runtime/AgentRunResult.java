package com.jupiter.shortlink.agent.harness.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Report report
) {
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
            @JsonInclude(JsonInclude.Include.NON_NULL) CampaignReportModuleResponse modules) {
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
