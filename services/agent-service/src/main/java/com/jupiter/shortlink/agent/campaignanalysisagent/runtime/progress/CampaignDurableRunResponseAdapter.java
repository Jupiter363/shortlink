package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignAgentRunResultAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignLegacyAnswerAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure response boundary for an already authorized durable run-result projection.
 *
 * <p>The projection is the only source of report facts and status.  This adapter does not read a
 * store, inspect a graph checkpoint, infer state from text, or expose lifecycle metadata.  An
 * absent projection means that no binding was resolved and is represented by an empty result.</p>
 */
public final class CampaignDurableRunResponseAdapter {
    private final CampaignAgentRunResultAdapter responseAdapter;

    public CampaignDurableRunResponseAdapter() {
        this(new CampaignAgentRunResultAdapter());
    }

    CampaignDurableRunResponseAdapter(CampaignAgentRunResultAdapter responseAdapter) {
        this.responseAdapter = Objects.requireNonNull(responseAdapter, "AGENT_RESPONSE_ADAPTER_REQUIRED");
    }

    /**
     * Converts one optional durable projection.  Empty means no binding was found; it is not
     * converted into a guessed or historical report.
     */
    public Optional<AgentRunResult> adapt(AgentRunResult base,
                                          Optional<CampaignRunResultProjection.Projection> projection) {
        requireBase(base);
        if (projection == null) throw new IllegalArgumentException("AGENT_RESULT_PROJECTION_REQUIRED");
        return projection.map(value -> adapt(base, value));
    }

    /** Applies an optional expected identity before composing the response. */
    public Optional<AgentRunResult> adapt(Request request) {
        if (request == null) throw new IllegalArgumentException("AGENT_RESULT_REQUEST_REQUIRED");
        requireBase(request.base());
        if (request.projection().isEmpty()) return Optional.empty();
        return Optional.of(adapt(request.base(), request.projection().get(), request.expectedIdentity().orElse(null)));
    }

    /** Converts a present projection when no transport identity is available to compare. */
    public AgentRunResult adapt(AgentRunResult base,
                                CampaignRunResultProjection.Projection projection) {
        return adapt(base, projection, null);
    }

    private AgentRunResult adapt(AgentRunResult base,
                                 CampaignRunResultProjection.Projection projection,
                                 Identity expectedIdentity) {
        requireBase(base);
        if (projection == null) throw new IllegalArgumentException("AGENT_RESULT_PROJECTION_REQUIRED");
        validateProjection(projection, expectedIdentity);

        CampaignLegacyAnswerAdapter.ExecutionStatus status = legacyStatus(projection.executionStatus());
        CampaignLegacyAnswerAdapter.Availability availability = availability(projection, status);
        CampaignRunResultProjection.ReportSummary summary = projection.report();
        CampaignLegacyAnswerAdapter.GoalRollup rollup = summary == null
                ? new CampaignLegacyAnswerAdapter.GoalRollup(0, 0, 0, 0)
                : toLegacyRollup(summary.goalRollup());
        String answer = answer(availability, summary, rollup);
        AgentRunResult.Report report = new AgentRunResult.Report(
                CampaignLegacyAnswerAdapter.SCHEMA,
                availability,
                status,
                answer,
                summary == null ? null : summary.reportRef(),
                summary == null ? List.of() : summary.blocks(),
                projection.goalAssessments(),
                rollup,
                projection.limitations());
        return responseAdapter.adapt(base, report);
    }

    private static void requireBase(AgentRunResult base) {
        if (base == null) throw new IllegalArgumentException("AGENT_BASE_RESULT_REQUIRED");
    }

    private static void validateProjection(CampaignRunResultProjection.Projection projection,
                                           Identity expectedIdentity) {
        if (!CampaignRunResultProjection.SCHEMA.equals(projection.schemaVersion())
                || projection.executionStatus() == null
                || blank(projection.runId()) || blank(projection.planId()) || projection.revision() < 1
                || projection.nextAction() == null || projection.goalAssessments() == null
                || projection.limitations() == null) {
            throw new IllegalArgumentException("AGENT_RESULT_PROJECTION_INVALID");
        }
        if (expectedIdentity != null
                && (!expectedIdentity.runId().equals(projection.runId())
                || !expectedIdentity.planId().equals(projection.planId())
                || expectedIdentity.revision() != projection.revision())) {
            throw new IllegalArgumentException("AGENT_RESULT_IDENTITY_MISMATCH");
        }

        Set<String> goalIds = new HashSet<>();
        int answered = 0;
        int partial = 0;
        int unresolved = 0;
        for (GoalAssessment goal : projection.goalAssessments()) {
            if (goal == null || blank(goal.goalId()) || !goalIds.add(goal.goalId()))
                throw new IllegalArgumentException("AGENT_RESULT_GOALS_INVALID");
            switch (goal.status()) {
                case ANSWERED -> answered++;
                case PARTIAL -> partial++;
                default -> unresolved++;
            }
        }

        CampaignRunResultProjection.ReportSummary report = projection.report();
        if (report != null) {
            if (report.reportRef() == null || report.blocks() == null || report.goalRollup() == null)
                throw new IllegalArgumentException("AGENT_RESULT_REPORT_INVALID");
            Set<String> blockIds = new HashSet<>();
            for (ReportBlock block : report.blocks()) {
                if (block == null || blank(block.blockId()) || !blockIds.add(block.blockId()))
                    throw new IllegalArgumentException("AGENT_RESULT_BLOCKS_INVALID");
            }
            CampaignRunResultProjection.CampaignLegacyRollup counts = report.goalRollup();
            if (counts.total() != projection.goalAssessments().size()
                    || counts.answered() != answered || counts.partial() != partial
                    || counts.unresolved() != unresolved) {
                throw new IllegalArgumentException("AGENT_RESULT_ROLLUP_MISMATCH");
            }
            if (projection.goalAssessments().isEmpty())
                throw new IllegalArgumentException("AGENT_RESULT_REPORT_EMPTY");
        } else {
            if (!projection.goalAssessments().isEmpty())
                throw new IllegalArgumentException("AGENT_RESULT_REPORT_MISMATCH");
            if (projection.executionStatus() == CampaignRunResultProjection.ExecutionStatus.SUCCEEDED) {
                throw new IllegalArgumentException("AGENT_RESULT_REPORT_REQUIRED");
            }
        }

        boolean complete = report != null && !projection.goalAssessments().isEmpty()
                && projection.goalAssessments().stream().allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED)
                && report.blocks().stream().anyMatch(ReportBlock::isDeliverable);
        switch (projection.executionStatus()) {
            case SUCCEEDED -> {
                if (report == null || !complete || projection.nextAction().kind()
                        != CampaignRunResultProjection.NextActionKind.NONE)
                    throw new IllegalArgumentException("AGENT_RESULT_SUCCESS_INCOMPLETE");
            }
            case CANCELLED, SUPERSEDED -> {
                if (report != null) throw new IllegalArgumentException("AGENT_RESULT_STALE_REPORT");
                if (projection.nextAction().kind() != CampaignRunResultProjection.NextActionKind.NONE
                        && projection.nextAction().kind() != CampaignRunResultProjection.NextActionKind.CANCEL)
                    throw new IllegalArgumentException("AGENT_RESULT_TERMINAL_ACTION_INVALID");
            }
            case EMPTY -> {
                if (report != null) throw new IllegalArgumentException("AGENT_RESULT_EMPTY_REPORT_MISMATCH");
            }
            case RUNNING, WAITING -> {
                if (complete) throw new IllegalArgumentException("AGENT_RESULT_COMPLETE_STATUS_MISMATCH");
            }
            case FAILED -> {
                if (complete) throw new IllegalArgumentException("AGENT_RESULT_COMPLETE_STATUS_MISMATCH");
            }
            case UNKNOWN -> {
                if (complete) throw new IllegalArgumentException("AGENT_RESULT_COMPLETE_STATUS_MISMATCH");
            }
        }
    }

    private static CampaignLegacyAnswerAdapter.ExecutionStatus legacyStatus(
            CampaignRunResultProjection.ExecutionStatus status) {
        return switch (status) {
            case EMPTY -> CampaignLegacyAnswerAdapter.ExecutionStatus.EMPTY;
            case RUNNING -> CampaignLegacyAnswerAdapter.ExecutionStatus.RUNNING;
            case WAITING -> CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING;
            case SUCCEEDED -> CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED;
            case FAILED -> CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED;
            case CANCELLED -> CampaignLegacyAnswerAdapter.ExecutionStatus.CANCELLED;
            case UNKNOWN -> CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN;
            case SUPERSEDED -> throw new IllegalArgumentException("AGENT_RESULT_STATUS_UNSUPPORTED");
        };
    }

    private static CampaignLegacyAnswerAdapter.Availability availability(
            CampaignRunResultProjection.Projection projection,
            CampaignLegacyAnswerAdapter.ExecutionStatus status) {
        if (projection.report() == null) {
            return switch (status) {
                case EMPTY, RUNNING, WAITING -> CampaignLegacyAnswerAdapter.Availability.EMPTY;
                case FAILED, CANCELLED, UNKNOWN -> CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE;
                case SUCCEEDED -> throw new IllegalArgumentException("AGENT_RESULT_REPORT_REQUIRED");
            };
        }
        if (status != CampaignLegacyAnswerAdapter.ExecutionStatus.RUNNING
                && status != CampaignLegacyAnswerAdapter.ExecutionStatus.WAITING
                && status != CampaignLegacyAnswerAdapter.ExecutionStatus.UNKNOWN
                && status != CampaignLegacyAnswerAdapter.ExecutionStatus.FAILED) {
            if (status == CampaignLegacyAnswerAdapter.ExecutionStatus.SUCCEEDED)
                return CampaignLegacyAnswerAdapter.Availability.COMPLETE;
            throw new IllegalArgumentException("AGENT_RESULT_PARTIAL_STATUS_CONFLICT");
        }
        return CampaignLegacyAnswerAdapter.Availability.PARTIAL;
    }

    private static String answer(CampaignLegacyAnswerAdapter.Availability availability,
                                 CampaignRunResultProjection.ReportSummary report,
                                 CampaignLegacyAnswerAdapter.GoalRollup rollup) {
        if (availability == CampaignLegacyAnswerAdapter.Availability.EMPTY)
            return "当前没有可展示的分析结果。";
        if (availability == CampaignLegacyAnswerAdapter.Availability.UNAVAILABLE)
            return "当前分析结果不可用，未生成可用报告。";
        String ref = "（报告 " + report.reportRef().reportId() + " · 第"
                + report.reportRef().revision() + "版）";
        if (availability == CampaignLegacyAnswerAdapter.Availability.COMPLETE)
            return "分析报告已完成" + ref + "，已回答 " + rollup.answered() + " 个目标。";
        return "分析报告部分完成" + ref + "，已回答 " + rollup.answered() + "/"
                + rollup.total() + " 个目标。";
    }

    private static CampaignLegacyAnswerAdapter.GoalRollup toLegacyRollup(
            CampaignRunResultProjection.CampaignLegacyRollup rollup) {
        return new CampaignLegacyAnswerAdapter.GoalRollup(rollup.total(), rollup.answered(),
                rollup.partial(), rollup.unresolved());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Transport-resolved identity used to prevent attaching a projection to another run. */
    public record Identity(String runId, String planId, int revision) {
        public Identity {
            if (blank(runId) || blank(planId) || revision < 1)
                throw new IllegalArgumentException("AGENT_RESULT_IDENTITY_INVALID");
        }
    }

    /** Explicit composition input; an absent projection means no durable binding was resolved. */
    public record Request(AgentRunResult base,
                          Optional<Identity> expectedIdentity,
                          Optional<CampaignRunResultProjection.Projection> projection) {
        public Request {
            if (base == null) throw new IllegalArgumentException("AGENT_BASE_RESULT_REQUIRED");
            if (expectedIdentity == null) throw new IllegalArgumentException("AGENT_RESULT_IDENTITY_REQUIRED");
            if (projection == null) throw new IllegalArgumentException("AGENT_RESULT_PROJECTION_REQUIRED");
        }

        public Request(AgentRunResult base,
                       Optional<CampaignRunResultProjection.Projection> projection) {
            this(base, Optional.empty(), projection);
        }

        public Request(AgentRunResult base, Identity expectedIdentity,
                       Optional<CampaignRunResultProjection.Projection> projection) {
            this(base, Optional.ofNullable(expectedIdentity), projection);
        }
    }
}
