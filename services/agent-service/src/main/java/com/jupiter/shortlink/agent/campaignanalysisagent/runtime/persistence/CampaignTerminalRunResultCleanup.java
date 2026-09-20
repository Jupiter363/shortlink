package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Releases the durable report reference held by a terminal run result.
 *
 * <p>This is a trusted maintenance composition, not a report-read API. It locks the exact run
 * revision and binding first, then removes only the deterministic reference from the report
 * lifecycle. It never checks report owner, capability, mode, or expiry, so an expired report can
 * still be cleaned up without being exposed.</p>
 */
public final class CampaignTerminalRunResultCleanup {
    private final JdbcCampaignRunResultStore results;
    private final JdbcReportLifecycleStore reports;
    private final TransactionTemplate transactions;

    public CampaignTerminalRunResultCleanup(JdbcCampaignRunResultStore results,
                                            JdbcReportLifecycleStore reports,
                                            JdbcTemplate jdbc,
                                            TransactionTemplate transactions) {
        this.results = Objects.requireNonNull(results, "RUN_RESULT_STORE_REQUIRED");
        this.reports = Objects.requireNonNull(reports, "REPORT_LIFECYCLE_STORE_REQUIRED");
        Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || !results.sharesDataSource(jdbc)
                || !reports.sharesDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Terminal result cleanup requires one shared writable REQUIRED DataSource transaction");
    }

    /**
     * Cleans one explicit revision. A null expected reference is valid only when the binding has
     * no report reference; a non-null value must match exactly. Repeating the call is harmless.
     */
    public CleanupOutcome cleanup(Caller caller, String runId, int revision, ReportRef expectedReportRef) {
        Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
        return transactions.execute(status -> {
            Optional<JdbcCampaignRunResultStore.TerminalBinding> locked =
                    results.lockTerminalBinding(caller, runId, revision);
            if (locked.isEmpty()) return CleanupOutcome.noBinding();

            JdbcCampaignRunResultStore.TerminalBinding binding = locked.get();
            if (!Objects.equals(expectedReportRef, binding.reportRef()))
                throw new IllegalStateException("RUN_RESULT_REPORT_REF_MISMATCH");
            if (binding.reportRef() == null)
                return CleanupOutcome.withoutReport(binding.runStatus());

            ReportLifecycleStore.Key key = new ReportLifecycleStore.Key(
                    binding.reportRef().reportId(), binding.reportRef().revision());
            ReportLifecycleStore.ReferenceRelease release = reports.releaseReferenceIfPresent(
                    key, JdbcCampaignRunResultBindingCoordinator.referenceId(binding.runId(), binding.revision()));
            return new CleanupOutcome(true, binding.runStatus(), binding.reportRef(), release.reportPresent(),
                    release.referenceRemoved(), release.rowVersion());
        });
    }

    /** Outcome is intentionally metadata only; no report body or capability is returned. */
    public record CleanupOutcome(boolean bindingPresent, RunStatus runStatus, ReportRef reportRef,
                                 boolean reportPresent, boolean referenceRemoved, long reportRowVersion) {
        public CleanupOutcome {
            if (!bindingPresent) {
                if (runStatus != null || reportRef != null || reportPresent || referenceRemoved || reportRowVersion != 0)
                    throw new IllegalArgumentException("RUN_RESULT_CLEANUP_OUTCOME_INVALID");
            } else {
                if (runStatus == null || (reportRef == null && (reportPresent || referenceRemoved || reportRowVersion != 0))
                        || reportRowVersion < 0 || (!reportPresent && reportRowVersion != 0))
                    throw new IllegalArgumentException("RUN_RESULT_CLEANUP_OUTCOME_INVALID");
            }
        }

        static CleanupOutcome noBinding() {
            return new CleanupOutcome(false, null, null, false, false, 0);
        }

        static CleanupOutcome withoutReport(RunStatus status) {
            return new CleanupOutcome(true, status, null, false, false, 0);
        }
    }
}
