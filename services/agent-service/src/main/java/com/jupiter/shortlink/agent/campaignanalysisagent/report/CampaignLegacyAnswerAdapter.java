package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the compatibility answer that older clients can consume without discarding the typed
 * report.  The adapter is intentionally pure: it never queries a store and never treats a free
 * text answer, owner, or capability as evidence of completion.
 */
public final class CampaignLegacyAnswerAdapter {
    public static final String SCHEMA = "campaign-legacy-view/v1";

    /** Execution state supplied by the trusted run boundary, not inferred from report text. */
    public enum ExecutionStatus { EMPTY, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

    /** Client-facing availability, constrained by both execution state and real goal evidence. */
    public enum Availability { EMPTY, PARTIAL, COMPLETE, UNAVAILABLE }

    /** Converts one typed report/status pair into a deterministic legacy view. */
    public LegacyView adapt(Request request) {
        Objects.requireNonNull(request, "LEGACY_REQUEST_REQUIRED");
        CampaignReportReadProjection.Snapshot snapshot = request.snapshot();
        if (snapshot == null && request.executionStatus() == ExecutionStatus.SUCCEEDED) {
            throw new IllegalArgumentException("LEGACY_SUCCESS_REPORT_REQUIRED");
        }
        if (snapshot != null && request.executionStatus() == ExecutionStatus.EMPTY) {
            throw new IllegalArgumentException("LEGACY_EMPTY_REPORT_MISMATCH");
        }

        List<GoalAssessment> goals = snapshot == null ? List.of() : snapshot.goalAssessments();
        List<ReportBlock> blocks = snapshot == null ? List.of() : snapshot.draft().blocks();
        GoalRollup rollup = rollup(goals);
        Availability availability = availability(request.executionStatus(), snapshot, blocks, rollup);
        String answer = answer(availability, snapshot, rollup);
        return new LegacyView(SCHEMA, availability, request.executionStatus(), answer, snapshot,
                blocks, goals, rollup, request.limitations());
    }

    /** Convenience overload for adapters that already hold the typed fields separately. */
    public LegacyView adapt(CampaignReportReadProjection.Snapshot snapshot,
                            ExecutionStatus executionStatus, List<String> limitations) {
        return adapt(new Request(snapshot, executionStatus, limitations));
    }

    private static Availability availability(ExecutionStatus status,
                                             CampaignReportReadProjection.Snapshot snapshot,
                                             List<ReportBlock> blocks,
                                             GoalRollup rollup) {
        if (snapshot == null) {
            return switch (status) {
                case EMPTY, RUNNING, WAITING -> Availability.EMPTY;
                case FAILED, CANCELLED, UNKNOWN -> Availability.UNAVAILABLE;
                case SUCCEEDED -> throw new IllegalArgumentException("LEGACY_SUCCESS_REPORT_REQUIRED");
            };
        }
        if (rollup.total() == 0 && blocks.isEmpty()) {
            return switch (status) {
                case FAILED, CANCELLED, UNKNOWN -> Availability.UNAVAILABLE;
                case EMPTY, RUNNING, WAITING, SUCCEEDED -> Availability.EMPTY;
            };
        }
        boolean completeEvidence = rollup.total() > 0
                && rollup.answered() == rollup.total()
                && !blocks.isEmpty();
        if (status == ExecutionStatus.SUCCEEDED && completeEvidence) return Availability.COMPLETE;
        if (blocks.isEmpty() && rollup.answered() == 0
                && (status == ExecutionStatus.FAILED || status == ExecutionStatus.CANCELLED
                || status == ExecutionStatus.UNKNOWN)) return Availability.UNAVAILABLE;
        return Availability.PARTIAL;
    }

    private static GoalRollup rollup(List<GoalAssessment> goals) {
        int answered = 0;
        int partial = 0;
        int unresolved = 0;
        for (GoalAssessment goal : goals) {
            if (goal == null) throw new IllegalArgumentException("LEGACY_GOAL_INVALID");
            switch (goal.status()) {
                case ANSWERED -> answered++;
                case PARTIAL -> partial++;
                default -> unresolved++;
            }
        }
        return new GoalRollup(goals.size(), answered, partial, unresolved);
    }

    private static String answer(Availability availability,
                                 CampaignReportReadProjection.Snapshot snapshot,
                                 GoalRollup rollup) {
        if (availability == Availability.EMPTY)
            return "当前没有可展示的分析结果。";
        if (availability == Availability.UNAVAILABLE)
            return "当前分析结果不可用，未生成可用报告。";
        String report = snapshot == null ? "" : "（报告 " + snapshot.reportRef().reportId()
                + " · 第" + snapshot.reportRef().revision() + "版）";
        if (availability == Availability.COMPLETE)
            return "分析报告已完成" + report + "，已回答 " + rollup.answered() + " 个目标。";
        return "分析报告部分完成" + report + "，已回答 " + rollup.answered() + "/"
                + rollup.total() + " 个目标。";
    }

    /** Typed input for the compatibility adapter; the status is server-owned. */
    public record Request(CampaignReportReadProjection.Snapshot snapshot,
                          ExecutionStatus executionStatus,
                          List<String> limitations) {
        public Request {
            Objects.requireNonNull(executionStatus, "LEGACY_EXECUTION_STATUS_REQUIRED");
            limitations = immutableLimitations(limitations);
        }
    }

    /** Full compatibility result; the typed snapshot and every block remain available. */
    public record LegacyView(String schemaVersion,
                             Availability availability,
                             ExecutionStatus executionStatus,
                             String answer,
                             CampaignReportReadProjection.Snapshot snapshot,
                             List<ReportBlock> blocks,
                             List<GoalAssessment> goalAssessments,
                             GoalRollup goalRollup,
                             List<String> limitations) {
        public LegacyView {
            if (!SCHEMA.equals(schemaVersion) || availability == null || executionStatus == null
                    || answer == null || answer.isBlank() || goalRollup == null)
                throw new IllegalArgumentException("LEGACY_VIEW_INVALID");
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
            limitations = immutableLimitations(limitations);
            if (snapshot != null
                    && (!snapshot.draft().blocks().equals(blocks)
                    || !snapshot.goalAssessments().equals(goalAssessments)))
                throw new IllegalArgumentException("LEGACY_VIEW_REPORT_MISMATCH");
            if (snapshot == null && (!blocks.isEmpty() || !goalAssessments.isEmpty()))
                throw new IllegalArgumentException("LEGACY_VIEW_REPORT_MISMATCH");
        }
    }

    /** Aggregate counts retain the distinction between answered, partial, and unresolved goals. */
    public record GoalRollup(int total, int answered, int partial, int unresolved) {
        public GoalRollup {
            if (total < 0 || answered < 0 || partial < 0 || unresolved < 0
                    || total != answered + partial + unresolved)
                throw new IllegalArgumentException("LEGACY_GOAL_ROLLUP_INVALID");
        }
    }

    private static List<String> immutableLimitations(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("LEGACY_LIMITATION_INVALID");
            copy.add(value);
        }
        return List.copyOf(copy);
    }
}
