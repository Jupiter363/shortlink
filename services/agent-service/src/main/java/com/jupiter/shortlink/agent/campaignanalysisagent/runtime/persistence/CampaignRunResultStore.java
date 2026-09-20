package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable binding between one run revision and its current typed result facts.
 *
 * <p>This is deliberately a small business store.  It does not persist a report body, a graph
 * checkpoint, or a capability token.  A report reference is verified by a trusted caller at bind
 * time and remains only a typed reference in this row.</p>
 */
public interface CampaignRunResultStore {
    String SCHEMA = CampaignRunResultProjection.SCHEMA;

    /**
     * The report lifecycle is intentionally injected rather than queried by this store.  The
     * production composition must provide a verifier; there is no implicit allow-all verifier.
     */
    @FunctionalInterface
    interface ReportBindingVerifier {
        boolean mayBind(RunDefinition run, ReportRef reportRef);
    }

    record BindingDraft(ReportRef reportRef, ExecutionStatus executionStatus,
                        NextAction nextAction, List<String> limitations) {
        public BindingDraft {
            if (executionStatus == null) throw new IllegalArgumentException("RUN_RESULT_STATUS_REQUIRED");
            if (nextAction == null) throw new IllegalArgumentException("RUN_RESULT_NEXT_ACTION_REQUIRED");
            limitations = immutableNonblank(limitations, "RUN_RESULT_LIMITATION_INVALID");
            validateFacts(reportRef, executionStatus, nextAction);
        }

        public BindingDraft(ExecutionStatus executionStatus, NextAction nextAction) {
            this(null, executionStatus, nextAction, List.of());
        }

        public BindingDraft(ReportRef reportRef, ExecutionStatus executionStatus, NextAction nextAction) {
            this(reportRef, executionStatus, nextAction, List.of());
        }
    }

    /** Sanitized durable facts suitable for a response adapter; no owner/capability is exposed. */
    record Binding(String schemaVersion, String runId, String planId, int revision,
                   ExecutionStatus executionStatus, ReportRef reportRef, NextAction nextAction,
                   List<String> limitations, long sourceRowVersion, String sourceAdvanceToken,
                   long bindingVersion, Instant createdAt, Instant updatedAt) {
        public Binding {
            if (!SCHEMA.equals(schemaVersion) || runId == null || runId.isBlank()
                    || planId == null || planId.isBlank() || revision < 1
                    || executionStatus == null || nextAction == null
                    || sourceRowVersion < 0 || sourceAdvanceToken == null || sourceAdvanceToken.isBlank()
                    || bindingVersion < 1 || createdAt == null || updatedAt == null)
                throw new IllegalArgumentException("RUN_RESULT_BINDING_INVALID");
            limitations = immutableNonblank(limitations, "RUN_RESULT_LIMITATION_INVALID");
            validateFacts(reportRef, executionStatus, nextAction);
        }
    }

    /** Atomically records the facts fenced by the exact current run token. */
    Binding bind(RunToken token, BindingDraft draft);

    /** Reads one exact revision only when the caller still owns the run. */
    Optional<Binding> read(Caller caller, String runId, int revision);

    static void validateFacts(ReportRef reportRef, ExecutionStatus status, NextAction action) {
        if (status == ExecutionStatus.SUCCEEDED && reportRef == null)
            throw new IllegalArgumentException("RUN_RESULT_REPORT_REQUIRED");
        if (reportRef != null && (status == ExecutionStatus.EMPTY
                || status == ExecutionStatus.CANCELLED || status == ExecutionStatus.SUPERSEDED))
            throw new IllegalArgumentException("RUN_RESULT_REPORT_STATUS_MISMATCH");
        if (status == ExecutionStatus.SUCCEEDED && action.kind() != CampaignRunResultProjection.NextActionKind.NONE)
            throw new IllegalArgumentException("RUN_RESULT_COMPLETED_ACTION_INVALID");
        if ((status == ExecutionStatus.CANCELLED || status == ExecutionStatus.SUPERSEDED)
                && action.kind() != CampaignRunResultProjection.NextActionKind.NONE
                && action.kind() != CampaignRunResultProjection.NextActionKind.CANCEL)
            throw new IllegalArgumentException("RUN_RESULT_TERMINAL_ACTION_INVALID");
    }

    private static List<String> immutableNonblank(List<String> values, String error) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > 64) throw new IllegalArgumentException(error);
        java.util.ArrayList<String> copy = new java.util.ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException(error);
            copy.add(value);
        }
        return List.copyOf(copy);
    }
}
