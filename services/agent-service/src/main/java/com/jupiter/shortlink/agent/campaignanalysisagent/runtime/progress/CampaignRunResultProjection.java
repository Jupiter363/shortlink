package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.ReportBlock;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure response projection for one already-authorized run/progress snapshot.
 *
 * <p>This class intentionally has no store or Graph dependency.  A report is accepted only when
 * its identity matches the current progress snapshot; a historical report is never selected by
 * looking up a run id or by inspecting free text.</p>
 */
public final class CampaignRunResultProjection {
    public static final String SCHEMA = "campaign-run-result/v1";

    /**
     * Public execution state.  The public protocol intentionally folds durable BLOCKED progress
     * into WAITING; the typed next action carries the recoverable reason without inventing a
     * second client lifecycle.
     */
    public enum ExecutionStatus { EMPTY, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, SUPERSEDED, UNKNOWN }

    public enum NextActionKind { NONE, CONTINUE, WAIT, NEEDS_INPUT, REPLAN, RETRY, CANCEL }

    /** Explicit server-owned continuation instruction; no model/free-text inference is allowed. */
    public record NextAction(NextActionKind kind, String reasonCode, List<String> requiredInputs) {
        public NextAction {
            Objects.requireNonNull(kind, "RUN_RESULT_NEXT_ACTION_REQUIRED");
            if (reasonCode != null && (reasonCode.isBlank() || !reasonCode.matches("[A-Z][A-Z0-9_]{0,95}")))
                throw new IllegalArgumentException("RUN_RESULT_REASON_INVALID");
            requiredInputs = immutableNonblank(requiredInputs, "RUN_RESULT_INPUT_INVALID");
            if (kind == NextActionKind.NONE && (reasonCode != null || !requiredInputs.isEmpty()))
                throw new IllegalArgumentException("RUN_RESULT_NONE_ACTION_INVALID");
            if (kind == NextActionKind.NEEDS_INPUT && requiredInputs.isEmpty())
                throw new IllegalArgumentException("RUN_RESULT_INPUT_REQUIRED");
        }

        public static NextAction none() { return new NextAction(NextActionKind.NONE, null, List.of()); }
    }

    /** Safe report facts.  Payload, owner/capability and retention metadata are deliberately absent. */
    public record ReportSummary(CampaignReportPublisher.ReportRef reportRef,
                                List<ReportBlock> blocks,
                                CampaignLegacyRollup goalRollup,
                                Map<String, List<String>> blockGoals,
                                List<ReportDraft.ResultEntry> resultEntries) {
        public ReportSummary(CampaignReportPublisher.ReportRef reportRef,
                             List<ReportBlock> blocks,
                             CampaignLegacyRollup goalRollup) {
            this(reportRef, blocks, goalRollup, Map.of(), List.of());
        }

        public ReportSummary(CampaignReportPublisher.ReportRef reportRef,
                             List<ReportBlock> blocks,
                             CampaignLegacyRollup goalRollup,
                             Map<String, List<String>> blockGoals) {
            this(reportRef, blocks, goalRollup, blockGoals, List.of());
        }

        public ReportSummary {
            Objects.requireNonNull(reportRef, "RUN_RESULT_REPORT_REF_REQUIRED");
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            Objects.requireNonNull(goalRollup, "RUN_RESULT_GOAL_ROLLUP_REQUIRED");
            blockGoals = immutableBlockGoals(blockGoals);
            resultEntries = resultEntries == null ? List.of() : List.copyOf(resultEntries);
        }
    }

    /** Versioned response facts for the generic client. */
    public record Projection(String schemaVersion, ExecutionStatus executionStatus,
                             String runId, String planId, int revision,
                             List<GoalAssessment> goalAssessments,
                             ReportSummary report, NextAction nextAction,
                             List<String> limitations) {
        public Projection {
            if (!SCHEMA.equals(schemaVersion) || executionStatus == null || runId == null || runId.isBlank()
                    || planId == null || planId.isBlank() || revision < 1 || nextAction == null)
                throw new IllegalArgumentException("RUN_RESULT_PROJECTION_INVALID");
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
            limitations = immutableNonblank(limitations, "RUN_RESULT_LIMITATION_INVALID");
        }
    }

    /** Input assembled by a trusted runtime boundary; all fields are explicit and typed. */
    public record Request(CampaignProgressView progress,
                          ExecutionStatus executionStatus,
                          Optional<CampaignReportReadProjection.Snapshot> report,
                          NextAction nextAction,
                          List<String> limitations) {
        public Request {
            Objects.requireNonNull(progress, "RUN_RESULT_PROGRESS_REQUIRED");
            Objects.requireNonNull(executionStatus, "RUN_RESULT_STATUS_REQUIRED");
            report = report == null ? Optional.empty() : report;
            Objects.requireNonNull(nextAction, "RUN_RESULT_NEXT_ACTION_REQUIRED");
            limitations = immutableNonblank(limitations, "RUN_RESULT_LIMITATION_INVALID");
        }
    }

    public Projection project(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_REQUEST_REQUIRED");
        CampaignProgressView progress = request.progress();
        CampaignReportReadProjection.Snapshot snapshot = request.report().orElse(null);
        validateRunIdentity(progress, snapshot);
        validateStatus(progress, request.executionStatus(), snapshot);
        validateAction(request.executionStatus(), request.nextAction());

        ReportSummary summary = snapshot == null ? null : new ReportSummary(
                snapshot.reportRef(), snapshot.draft().blocks(), rollup(snapshot.goalAssessments()),
                blockGoals(snapshot), snapshot.draft().resultEntries());
        return new Projection(SCHEMA, request.executionStatus(), progress.runId(), progress.planId(),
                progress.revision(), snapshot == null ? List.of() : snapshot.goalAssessments(), summary,
                request.nextAction(), request.limitations());
    }

    public Projection project(CampaignProgressView progress, ExecutionStatus executionStatus,
                              Optional<CampaignReportReadProjection.Snapshot> report,
                              NextAction nextAction, List<String> limitations) {
        return project(new Request(progress, executionStatus, report, nextAction, limitations));
    }

    private static void validateRunIdentity(CampaignProgressView progress,
                                            CampaignReportReadProjection.Snapshot snapshot) {
        if (progress == null || !CampaignProgressView.SCHEMA.equals(progress.schemaVersion())
                || progress.runId() == null || progress.runId().isBlank()
                || progress.planId() == null || progress.planId().isBlank()
                || progress.revision() < 1 || progress.runStatus() == null || progress.workState() == null) {
            throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
        }
        if (snapshot == null) return;
        if (!CampaignReportReadProjection.SCHEMA.equals(snapshot.schemaVersion())
                || snapshot.reportRef() == null || snapshot.draft() == null
                || !snapshot.runId().equals(snapshot.draft().runId())
                || !snapshot.reportRef().reportId().equals(snapshot.draft().reportId())
                || snapshot.reportRef().revision() != snapshot.draft().revision()
                || !goalIds(progress).equals(goalIds(snapshot))
                || !progress.runId().equals(snapshot.runId())
                || progress.revision() != snapshot.planRevision()
                || !progress.planId().equals(snapshot.draft().planId())
                || progress.revision() != snapshot.draft().planRevision())
            throw new IllegalArgumentException("RUN_RESULT_IDENTITY_MISMATCH");
    }

    private static Set<String> goalIds(CampaignProgressView progress) {
        Set<String> ids = new HashSet<>();
        for (CampaignProgressView.GoalProgress goal : progress.goals()) {
            if (goal == null || goal.goalId() == null || goal.goalId().isBlank() || !ids.add(goal.goalId()))
                throw new IllegalArgumentException("RUN_RESULT_IDENTITY_MISMATCH");
        }
        return ids;
    }

    private static Set<String> goalIds(CampaignReportReadProjection.Snapshot snapshot) {
        Set<String> ids = new HashSet<>();
        for (GoalAssessment goal : snapshot.goalAssessments()) {
            if (goal == null || !ids.add(goal.goalId()))
                throw new IllegalArgumentException("RUN_RESULT_IDENTITY_MISMATCH");
        }
        return ids;
    }

    private static void validateStatus(CampaignProgressView progress, ExecutionStatus status,
                                       CampaignReportReadProjection.Snapshot snapshot) {
        Objects.requireNonNull(status, "RUN_RESULT_STATUS_REQUIRED");
        RunStatus runStatus = progress.runStatus();
        CampaignProgressView.WorkState workState = progress.workState();
        if (runStatus == null || workState == null) {
            throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
        }

        boolean active = runStatus == RunStatus.ACTIVE;
        switch (status) {
            case EMPTY -> requireState(active && workState == CampaignProgressView.WorkState.PENDING,
                    "RUN_RESULT_EMPTY_STATE_MISMATCH");
            case RUNNING -> requireState(active && workState == CampaignProgressView.WorkState.RUNNING,
                    "RUN_RESULT_RUNNING_STATE_MISMATCH");
            case WAITING -> requireState(active && (workState == CampaignProgressView.WorkState.WAITING
                            || workState == CampaignProgressView.WorkState.BLOCKED),
                    "RUN_RESULT_WAITING_STATE_MISMATCH");
            case FAILED -> requireState(active && workState == CampaignProgressView.WorkState.FAILED,
                    "RUN_RESULT_FAILED_STATE_MISMATCH");
            case SUCCEEDED -> {
                if (snapshot == null) throw new IllegalArgumentException("RUN_RESULT_REPORT_REQUIRED");
                requireState(active && workState == CampaignProgressView.WorkState.EXECUTED,
                        "RUN_RESULT_SUCCESS_INCOMPLETE");
                if (!complete(snapshot)) throw new IllegalArgumentException("RUN_RESULT_SUCCESS_INCOMPLETE");
            }
            case CANCELLED -> {
                requireState(runStatus == RunStatus.CANCELLED
                                && workState == CampaignProgressView.WorkState.CANCELLED,
                        "RUN_RESULT_STATUS_MISMATCH");
                if (snapshot != null) throw new IllegalArgumentException("RUN_RESULT_STALE_REPORT");
            }
            case SUPERSEDED -> {
                requireState(runStatus == RunStatus.SUPERSEDED
                                && workState == CampaignProgressView.WorkState.SUPERSEDED,
                        "RUN_RESULT_STATUS_MISMATCH");
                if (snapshot != null) throw new IllegalArgumentException("RUN_RESULT_STALE_REPORT");
            }
            case UNKNOWN -> requireState(active, "RUN_RESULT_STATUS_MISMATCH");
        }
        if (snapshot != null && status != ExecutionStatus.SUCCEEDED && complete(snapshot))
            throw new IllegalArgumentException("RUN_RESULT_COMPLETE_STATUS_MISMATCH");
        if (status == ExecutionStatus.EMPTY && snapshot != null)
            throw new IllegalArgumentException("RUN_RESULT_EMPTY_REPORT_MISMATCH");
    }

    private static void requireState(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static void validateAction(ExecutionStatus status, NextAction action) {
        if (status == ExecutionStatus.SUCCEEDED && action.kind() != NextActionKind.NONE)
            throw new IllegalArgumentException("RUN_RESULT_COMPLETED_ACTION_INVALID");
        if ((status == ExecutionStatus.CANCELLED || status == ExecutionStatus.SUPERSEDED)
                && action.kind() != NextActionKind.NONE && action.kind() != NextActionKind.CANCEL)
            throw new IllegalArgumentException("RUN_RESULT_TERMINAL_ACTION_INVALID");
    }

    static boolean complete(CampaignReportReadProjection.Snapshot snapshot) {
        List<GoalAssessment> goals = snapshot.goalAssessments();
        return !goals.isEmpty() && goals.stream().allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED)
                && snapshot.draft().blocks().stream().anyMatch(ReportBlock::isDeliverable);
    }

    private static Map<String, List<String>> blockGoals(
            CampaignReportReadProjection.Snapshot snapshot) {
        Map<String, LinkedHashSet<String>> owners = new LinkedHashMap<>();
        snapshot.draft().sections().forEach(section -> section.blocks().forEach(block ->
                owners.computeIfAbsent(block.blockId(), ignored -> new LinkedHashSet<>())
                        .addAll(section.goalIds())));
        Map<String, List<String>> result = new LinkedHashMap<>();
        owners.forEach((blockId, goalIds) -> result.put(blockId, List.copyOf(goalIds)));
        return Map.copyOf(result);
    }

    private static CampaignLegacyRollup rollup(List<GoalAssessment> goals) {
        int answered = 0, partial = 0, unresolved = 0;
        for (GoalAssessment goal : goals) {
            switch (goal.status()) {
                case ANSWERED -> answered++;
                case PARTIAL -> partial++;
                default -> unresolved++;
            }
        }
        return new CampaignLegacyRollup(goals.size(), answered, partial, unresolved);
    }

    public record CampaignLegacyRollup(int total, int answered, int partial, int unresolved) {
        public CampaignLegacyRollup {
            if (total < 0 || answered < 0 || partial < 0 || unresolved < 0
                    || total != answered + partial + unresolved)
                throw new IllegalArgumentException("RUN_RESULT_GOAL_ROLLUP_INVALID");
        }
    }

    private static <T> List<T> immutableNonblank(List<T> values, String error) {
        if (values == null || values.isEmpty()) return List.of();
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null || (value instanceof String text && text.isBlank()))
                throw new IllegalArgumentException(error);
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static Map<String, List<String>> immutableBlockGoals(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((blockId, goalIds) -> {
            if (blockId == null || blockId.isBlank() || goalIds == null
                    || goalIds.stream().anyMatch(goalId -> goalId == null || goalId.isBlank())) {
                throw new IllegalArgumentException("RUN_RESULT_BLOCK_SCOPE_INVALID");
            }
            copy.put(blockId, List.copyOf(new LinkedHashSet<>(goalIds)));
        });
        return Map.copyOf(copy);
    }
}
