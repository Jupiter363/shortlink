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
        return adapt(base, report);
    }

    /**
     * Carries an already-sanitized durable report view onto the legacy response envelope.
     *
     * <p>The report record is deliberately the only accepted presentation payload here.  This
     * overload does not accept a lifecycle snapshot, owner, capability or persistence payload, so
     * callers cannot accidentally widen the response when adapting a durable projection.</p>
     */
    public AgentRunResult adapt(AgentRunResult base, AgentRunResult.Report report) {
        if (base == null) throw new IllegalArgumentException("AGENT_BASE_RESULT_REQUIRED");
        if (report == null) throw new IllegalArgumentException("AGENT_RESULT_REPORT_REQUIRED");
        return new AgentRunResult(base.sessionId(), base.traceId(), report.answer(),
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
            case PARTIAL -> status == CampaignLegacyAnswerAdapter.ExecutionStatus.RUNNING
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN;
        };
        if (!valid)
            throw new IllegalArgumentException("AGENT_RESULT_STATUS_CONFLICT");

        CampaignReportReadProjection.Snapshot snapshot = view.snapshot();
        boolean hasReport = snapshot != null && snapshot.reportRef() != null;
        boolean completeEvidence = !view.goalAssessments().isEmpty()
                && view.goalAssessments().stream()
                .allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED)
                && view.blocks().stream().anyMatch(ReportBlock::isDeliverable);
        if (availability == CampaignLegacyAnswerAdapter.Availability.COMPLETE) {
            if (!hasReport || !completeEvidence)
                throw new IllegalArgumentException("AGENT_RESULT_REPORT_REQUIRED");
        } else if (availability == CampaignLegacyAnswerAdapter.Availability.PARTIAL) {
            if (!hasReport || completeEvidence)
                throw new IllegalArgumentException("AGENT_RESULT_STATUS_CONFLICT");
        } else if (availability == CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE) {
            boolean hasEvidence = !view.goalAssessments().isEmpty() || !view.blocks().isEmpty();
            if (status == CampaignLegacyAnswerAdapter.ExecutionStatus.CANCELLED && snapshot != null) {
                throw new IllegalArgumentException("AGENT_RESULT_STALE_REPORT");
            }
            if ((status == CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED
                    || status == CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN)
                    && hasEvidence) {
                throw new IllegalArgumentException("AGENT_RESULT_STATUS_CONFLICT");
            }
        }

        CampaignLegacyAnswerAdapter.GoalRollup expected = rollup(view.goalAssessments());
        if (!expected.equals(view.goalRollup()))
            throw new IllegalArgumentException("AGENT_RESULT_GOAL_ROLLUP_MISMATCH");
    }

    private static CampaignLegacyAnswerAdapter.GoalRollup rollup(List<GoalAssessment> goals) {
        int answered = 0;
        int partial = 0;
        int unresolved = 0;
        for (GoalAssessment goal : goals) {
            if (goal == null) throw new IllegalArgumentException("AGENT_RESULT_GOAL_INVALID");
            switch (goal.status()) {
                case ANSWERED -> answered++;
                case PARTIAL -> partial++;
                default -> unresolved++;
            }
        }
        return new CampaignLegacyAnswerAdapter.GoalRollup(goals.size(), answered, partial, unresolved);
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
