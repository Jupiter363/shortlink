package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.ProgressSnapshot;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal facts captured by the JDBC durable-read coordinator while its row locks are held.
 * It deliberately carries report reference identity and row version only; report payload and
 * lifecycle metadata stay behind the authorized report projection. The plan id in the identity
 * is the binding-side plan id because the lifecycle row has no independent plan-id column; the
 * later E69 projection remains responsible for validating report payload identity.
 */
public record CampaignDurableRunReadSnapshot(Binding binding,
                                             CampaignResultProgressReader.Snapshot progress,
                                             Optional<ReportIdentity> report) {
    public CampaignDurableRunReadSnapshot {
        Objects.requireNonNull(binding, "RUN_RESULT_BINDING_REQUIRED");
        Objects.requireNonNull(progress, "RUN_RESULT_PROGRESS_REQUIRED");
        report = report == null ? Optional.empty() : report;
        ProgressSnapshot execution = Objects.requireNonNull(progress.execution(), "RUN_RESULT_EXECUTION_REQUIRED");
        if (!binding.runId().equals(execution.run().definition().runId())
                || binding.revision() != execution.run().definition().revision()
                || !binding.planId().equals(execution.run().definition().planId())
                || binding.sourceRowVersion() != execution.run().version()
                || !binding.sourceAdvanceToken().equals(execution.run().advanceToken()))
            throw new IllegalStateException("RUN_RESULT_READ_CONFLICT");
        if (binding.reportRef() == null && report.isPresent())
            throw new IllegalStateException("RUN_RESULT_REPORT_IDENTITY_MISMATCH");
        if (binding.reportRef() != null) {
            ReportIdentity value = report.orElseThrow(() -> new IllegalStateException("RUN_RESULT_REPORT_NOT_FOUND"));
            if (!binding.reportRef().equals(value.reportRef())
                    || !binding.runId().equals(value.runId())
                    || binding.revision() != value.planRevision()
                    || !binding.planId().equals(value.planId()))
                throw new IllegalStateException("RUN_RESULT_REPORT_IDENTITY_MISMATCH");
        }
    }

    public record ReportIdentity(ReportRef reportRef, String runId, String planId, int planRevision,
                                 long rowVersion) {
        public ReportIdentity {
            Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED");
            if (runId == null || runId.isBlank() || planId == null || planId.isBlank()
                    || planRevision < 1 || rowVersion < 1)
                throw new IllegalArgumentException("REPORT_IDENTITY_INVALID");
        }
    }
}
