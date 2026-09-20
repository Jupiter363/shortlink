package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportBindingVerifier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trusted composition that retains a report reference and writes the E70 result binding in one
 * REQUIRED transaction.  It is deliberately opt-in: callers still choose the owner/capability
 * and must register no transport or scheduler entry point here.
 */
public final class JdbcCampaignRunResultBindingCoordinator {
    private final JdbcCampaignRunResultStore results;
    private final CampaignRunReportBindingVerifier reports;
    private final TransactionTemplate transactions;

    public JdbcCampaignRunResultBindingCoordinator(JdbcCampaignRunResultStore results,
                                                   JdbcReportLifecycleStore reportLifecycle,
                                                   JdbcTemplate jdbc, TransactionTemplate transactions,
                                                   String owner, String capability) {
        this.results = Objects.requireNonNull(results, "RUN_RESULT_STORE_REQUIRED");
        Objects.requireNonNull(reportLifecycle, "REPORT_LIFECYCLE_STORE_REQUIRED");
        Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || !results.sharesDataSource(jdbc)
                || !reportLifecycle.sharesDataSource(jdbc)
                || !results.usesTransactionTemplate(transactions)
                || !reportLifecycle.usesTransactionTemplate(transactions)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Result binding requires one shared writable REQUIRED DataSource transaction");
        this.reports = new CampaignRunReportBindingVerifier(reportLifecycle, owner, capability);
    }

    /** Retains the fixed report reference before binding; any later failure rolls back both writes. */
    public Binding bind(RunToken token, BindingDraft draft) {
        Objects.requireNonNull(token, "RUN_RESULT_TOKEN_REQUIRED");
        Objects.requireNonNull(draft, "RUN_RESULT_DRAFT_REQUIRED");
        return transactions.execute(status -> {
            results.requireCurrentToken(token);
            // A retried publication must not retain the same deterministic reference twice. The
            // run lock serializes this check with a first bind, so an exact replay can return the
            // durable row before touching the report lifecycle.
            Binding existing = results.readCurrentBindingLocked(token).orElse(null);
            if (existing != null) {
                if (!results.matchesBinding(existing, token, draft))
                    throw new IllegalStateException("RUN_RESULT_BINDING_CONFLICT");
                // An exact replay still has to prove that the current request may read/bind the
                // report.  Idempotency must not turn a revoked or expired credential into a
                // durable report reference oracle.
                if (existing.reportRef() != null
                        && !reports.mayBind(token.definition(), existing.reportRef()))
                    throw new SecurityException("RUN_RESULT_REPORT_NOT_AUTHORIZED");
                return existing;
            }
            if (draft.reportRef() != null)
                reports.retain(draft.reportRef(), referenceId(token));
            return results.bind(token, draft);
        });
    }

    /** Releases the deterministic report reference after the binding is no longer retained. */
    public long release(RunToken token, ReportRef reportRef) {
        Objects.requireNonNull(token, "RUN_RESULT_TOKEN_REQUIRED");
        Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED");
        return transactions.execute(status -> {
            results.requireCurrentToken(token);
            Binding binding = results.readCurrentBindingLocked(token)
                    .orElseThrow(() -> new IllegalStateException("RUN_RESULT_BINDING_NOT_FOUND"));
            if (!reportRef.equals(binding.reportRef()))
                throw new IllegalStateException("RUN_RESULT_REPORT_REF_MISMATCH");
            return reports.release(reportRef, referenceId(token));
        });
    }

    static String referenceId(RunToken token) {
        String runId = token.definition().runId();
        return referenceId(runId, token.definition().revision());
    }

    static String referenceId(String runId, int revision) {
        return "run-result-binding:" + runId + ":" + revision;
    }
}
