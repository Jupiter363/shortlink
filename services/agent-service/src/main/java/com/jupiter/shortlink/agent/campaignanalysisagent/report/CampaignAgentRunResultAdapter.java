package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;

/** Pure, trusted boundary from the legacy campaign view to the sanitized agent response. */
public final class CampaignAgentRunResultAdapter {
    public AgentRunResult adapt(Request request) {
        if (request == null) throw new IllegalArgumentException("AGENT_RESULT_REQUEST_REQUIRED");
        return adapt(request.base(), request.view());
    }

    /**
     * Replaces only the presentation answer/report on an existing run result.  The trusted typed
     * view is the sole source for the answer; every operational field remains from the base result.
     */
    public AgentRunResult adapt(AgentRunResult base,
                                CampaignLegacyAnswerAdapter.LegacyView view) {
        if (base == null) throw new IllegalArgumentException("AGENT_BASE_RESULT_REQUIRED");
        validateConsistency(view);
        CampaignReportReadProjection.Snapshot snapshot = view.snapshot();
        AgentRunResult.Report report = new AgentRunResult.Report(
                CampaignLegacyAnswerAdapter.SCHEMA, view.availability(), view.executionStatus(), view.answer(),
                snapshot == null ? null : snapshot.reportRef(), view.blocks(), view.goalAssessments(),
                view.goalRollup(), view.limitations());
        return new AgentRunResult(base.sessionId(), base.traceId(), view.answer(),
                copy(base.cards()), copy(base.pendingActions()), copy(base.toolCalls()),
                copy(base.dataSources()), copy(base.traceEvents()), copy(base.warnings()), report);
    }

    private static void validateConsistency(CampaignLegacyAnswerAdapter.LegacyView view) {
        if (view == null) throw new IllegalArgumentException("AGENT_RESULT_VIEW_REQUIRED");
        var status = view.executionStatus();
        var availability = view.availability();
        boolean valid = switch (availability) {
            case EMPTY -> status == CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.RUNNING
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING;
            case UNAVAILABLE -> status == CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.CANCELLED
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN;
            case COMPLETE -> status == CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED;
            case PARTIAL -> status != CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY;
        };
        if (!valid)
            throw new IllegalArgumentException("AGENT_RESULT_STATUS_CONFLICT");
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }

    public record Request(AgentRunResult base,
                          CampaignLegacyAnswerAdapter.LegacyView view) {
        public Request {
            if (base == null) throw new IllegalArgumentException("AGENT_BASE_RESULT_REQUIRED");
            if (view == null) throw new IllegalArgumentException("AGENT_RESULT_VIEW_REQUIRED");
        }
    }
}
