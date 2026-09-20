package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authorized, read-only composition of the durable run progress and one explicitly requested
 * report revision.  It never discovers a report by scanning history, dispatches work, refreshes
 * a job, or falls back to a previous report.  The caller that owns this boundary must resolve the
 * report reference and report credentials; this class only verifies the returned identities.
 */
public final class CampaignRunReportReadProjection {
    private final ProgressReader progressReader;
    private final ReportReader reportReader;
    private final CampaignRunResultProjection resultProjection;

    public CampaignRunReportReadProjection(CampaignProgressService progress,
                                           CampaignReportReadProjection reports) {
        this(Objects.requireNonNull(progress, "PROGRESS_SERVICE_REQUIRED")::read,
                Objects.requireNonNull(reports, "REPORT_PROJECTION_REQUIRED")::read);
    }

    /** Constructor for deterministic adapters and tests; both readers remain read-only. */
    public CampaignRunReportReadProjection(ProgressReader progressReader, ReportReader reportReader) {
        this.progressReader = Objects.requireNonNull(progressReader, "PROGRESS_READER_REQUIRED");
        this.reportReader = Objects.requireNonNull(reportReader, "REPORT_READER_REQUIRED");
        this.resultProjection = new CampaignRunResultProjection();
    }

    /**
     * Reads the latest authorized progress and, when supplied, one fixed report key.  A missing
     * report is represented as an absent report in the typed result; no alternate key is tried.
     */
    public CampaignRunResultProjection.Projection read(Request request) {
        Objects.requireNonNull(request, "RUN_REPORT_REQUEST_REQUIRED");
        CampaignProgressView progress = Objects.requireNonNull(
                progressReader.read(request.caller(), request.runId()), "RUN_RESULT_PROGRESS_REQUIRED");
        validateProgress(progress);
        if (!request.runId().equals(progress.runId()))
            throw new IllegalArgumentException("RUN_RESULT_IDENTITY_MISMATCH");

        Optional<CampaignReportReadProjection.Snapshot> report = Optional.empty();
        if (request.report().isPresent()) {
            ReportRead read = request.report().get();
            report = Objects.requireNonNull(reportReader.read(new CampaignReportApplicationService.ReadRequest(
                    read.reportRef().reportId(), read.reportRef().revision(), read.owner(), read.capability(),
                    read.mode())), "RUN_RESULT_REPORT_READ_REQUIRED");
            report.ifPresent(snapshot -> {
                if (!read.reportRef().equals(snapshot.reportRef()) || snapshot.mode() != read.mode())
                    throw new IllegalArgumentException("RUN_RESULT_REPORT_IDENTITY_MISMATCH");
            });
        }

        CampaignRunResultProjection.ExecutionStatus status = status(progress, report.orElse(null));
        CampaignRunResultProjection.NextAction nextAction = nextAction(progress, status);
        List<String> limitations = limitations(progress, status, request.report().isPresent(), report.orElse(null));
        return resultProjection.project(progress, status, report, nextAction, limitations);
    }

    private static void validateProgress(CampaignProgressView progress) {
        if (!CampaignProgressView.SCHEMA.equals(progress.schemaVersion())
                || progress.runId() == null || progress.runId().isBlank()
                || progress.planId() == null || progress.planId().isBlank()
                || progress.revision() < 1 || progress.runStatus() == null || progress.workState() == null)
            throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
        Set<String> goalIds = new LinkedHashSet<>();
        for (CampaignProgressView.GoalProgress goal : progress.goals()) {
            if (goal == null || goal.goalId() == null || goal.goalId().isBlank() || !goalIds.add(goal.goalId())
                    || goal.workState() == null)
                throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
            for (PlanningAssessment.Gap gap : goal.planningGaps()) {
                if (gap == null || gap.reason() == null || gap.requirementId() == null
                        || !gap.requirementId().matches("[A-Za-z0-9][A-Za-z0-9._:-]*"))
                    throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
            }
            for (CampaignProgressView.UnavailableOutput output : goal.unavailableOutputs()) {
                if (output == null) throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
            }
        }
    }

    private static CampaignRunResultProjection.ExecutionStatus status(CampaignProgressView progress,
                                                                        CampaignReportReadProjection.Snapshot report) {
        RunStatus runStatus = progress.runStatus();
        CampaignProgressView.WorkState work = progress.workState();
        if (runStatus == null || work == null) throw new IllegalArgumentException("RUN_RESULT_PROGRESS_INVALID");
        if (runStatus == RunStatus.CANCELLED) return CampaignRunResultProjection.ExecutionStatus.CANCELLED;
        if (runStatus == RunStatus.SUPERSEDED) return CampaignRunResultProjection.ExecutionStatus.SUPERSEDED;
        return switch (work) {
            case PENDING -> CampaignRunResultProjection.ExecutionStatus.EMPTY;
            case RUNNING -> CampaignRunResultProjection.ExecutionStatus.RUNNING;
            case WAITING, BLOCKED -> CampaignRunResultProjection.ExecutionStatus.WAITING;
            case FAILED -> CampaignRunResultProjection.ExecutionStatus.FAILED;
            case EXECUTED -> report != null && CampaignRunResultProjection.complete(report)
                    ? CampaignRunResultProjection.ExecutionStatus.SUCCEEDED
                    : CampaignRunResultProjection.ExecutionStatus.UNKNOWN;
            case CANCELLED -> CampaignRunResultProjection.ExecutionStatus.CANCELLED;
            case SUPERSEDED -> CampaignRunResultProjection.ExecutionStatus.SUPERSEDED;
        };
    }

    private static CampaignRunResultProjection.NextAction nextAction(CampaignProgressView progress,
                                                                       CampaignRunResultProjection.ExecutionStatus status) {
        CampaignProgressView.WorkState work = progress.workState();
        if (status == CampaignRunResultProjection.ExecutionStatus.SUCCEEDED
                || status == CampaignRunResultProjection.ExecutionStatus.CANCELLED
                || status == CampaignRunResultProjection.ExecutionStatus.SUPERSEDED)
            return CampaignRunResultProjection.NextAction.none();
        if (status == CampaignRunResultProjection.ExecutionStatus.UNKNOWN && work == CampaignProgressView.WorkState.EXECUTED)
            return new CampaignRunResultProjection.NextAction(CampaignRunResultProjection.NextActionKind.WAIT,
                    "REPORT_NOT_AVAILABLE", List.of());
        return switch (work) {
            case PENDING -> {
                List<String> inputs = requiredInputs(progress);
                if (!inputs.isEmpty())
                    yield new CampaignRunResultProjection.NextAction(
                            CampaignRunResultProjection.NextActionKind.NEEDS_INPUT, "MISSING_INPUT", inputs);
                yield new CampaignRunResultProjection.NextAction(
                        CampaignRunResultProjection.NextActionKind.CONTINUE, "RUN_NOT_STARTED", List.of());
            }
            case RUNNING -> new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.CONTINUE, "RUN_IN_PROGRESS", List.of());
            case WAITING -> new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.WAIT, "RESULT_PENDING", List.of());
            case BLOCKED -> blockedAction(progress);
            case FAILED -> new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.RETRY, "RUN_FAILED", List.of());
            case EXECUTED -> new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.WAIT, "REPORT_NOT_AVAILABLE", List.of());
            case CANCELLED, SUPERSEDED -> CampaignRunResultProjection.NextAction.none();
        };
    }

    private static CampaignRunResultProjection.NextAction blockedAction(CampaignProgressView progress) {
        List<String> inputs = requiredInputs(progress);
        if (!inputs.isEmpty())
            return new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.NEEDS_INPUT, "MISSING_INPUT", inputs);
        if (hasGap(progress, PlanningAssessment.GapReason.UNSUPPORTED)
                || hasGap(progress, PlanningAssessment.GapReason.PLANNING_UNRESOLVED))
            return new CampaignRunResultProjection.NextAction(
                    CampaignRunResultProjection.NextActionKind.REPLAN, "PLAN_REVIEW_REQUIRED", List.of());
        return new CampaignRunResultProjection.NextAction(
                CampaignRunResultProjection.NextActionKind.WAIT, "STEP_BLOCKED", List.of());
    }

    private static List<String> requiredInputs(CampaignProgressView progress) {
        Set<String> ids = new LinkedHashSet<>();
        for (CampaignProgressView.GoalProgress goal : progress.goals()) {
            for (PlanningAssessment.Gap gap : goal.planningGaps()) {
                if (gap.reason() == PlanningAssessment.GapReason.NEEDS_INPUT) ids.add(gap.requirementId());
            }
        }
        return List.copyOf(ids);
    }

    private static boolean hasGap(CampaignProgressView progress, PlanningAssessment.GapReason reason) {
        for (CampaignProgressView.GoalProgress goal : progress.goals())
            for (PlanningAssessment.Gap gap : goal.planningGaps()) if (gap.reason() == reason) return true;
        return false;
    }

    private static List<String> limitations(CampaignProgressView progress,
                                             CampaignRunResultProjection.ExecutionStatus status,
                                             boolean reportRequested,
                                             CampaignReportReadProjection.Snapshot report) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (CampaignProgressView.GoalProgress goal : progress.goals()) {
            for (PlanningAssessment.Gap gap : goal.planningGaps())
                result.add("PLANNING_GAP_" + gap.reason().name());
            for (CampaignProgressView.UnavailableOutput output : goal.unavailableOutputs())
                result.add(code(output.reasonCode(), "OUTPUT_UNAVAILABLE"));
        }
        if (status == CampaignRunResultProjection.ExecutionStatus.UNKNOWN
                && progress.workState() == CampaignProgressView.WorkState.EXECUTED)
            result.add(reportRequested && report == null ? "REPORT_NOT_AVAILABLE" : "REPORT_REFERENCE_REQUIRED");
        return List.copyOf(result);
    }

    private static String code(String value, String fallback) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,95}") ? value : fallback;
    }

    @FunctionalInterface
    public interface ProgressReader {
        CampaignProgressView read(Caller caller, String runId);
    }

    @FunctionalInterface
    public interface ReportReader {
        Optional<CampaignReportReadProjection.Snapshot> read(CampaignReportApplicationService.ReadRequest request);
    }

    public record ReportRead(CampaignReportPublisher.ReportRef reportRef, String owner, String capability,
                             ReportLifecycleStore.Mode mode) {
        public ReportRead {
            Objects.requireNonNull(reportRef, "RUN_REPORT_REF_REQUIRED");
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }
    }

    public record Request(Caller caller, String runId, Optional<ReportRead> report) {
        public Request {
            Objects.requireNonNull(caller, "RUN_REPORT_CALLER_REQUIRED");
            ReportLifecycleStore.require(runId, "RUN_REPORT_RUN_ID_INVALID");
            report = report == null ? Optional.empty() : report;
        }

        public Request(Caller caller, String runId) {
            this(caller, runId, Optional.empty());
        }
    }
}
