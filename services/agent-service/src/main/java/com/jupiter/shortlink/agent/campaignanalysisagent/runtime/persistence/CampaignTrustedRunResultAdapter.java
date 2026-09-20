package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportBindingVerifier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Request-bound trusted adapter for durable run-result binding.
 *
 * <p>The adapter deliberately does not retain report credentials in a singleton coordinator.
 * Each operation creates a short-lived verifier and coordinator from the credentials resolved for
 * that request, while all JDBC work still joins the same writable REQUIRED transaction.</p>
 */
public final class CampaignTrustedRunResultAdapter {
    private final JdbcTemplate jdbc;
    private final JdbcReportLifecycleStore reports;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CampaignTrustedRunResultAdapter(JdbcTemplate jdbc, JdbcReportLifecycleStore reports,
                                           TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "RUN_RESULT_JDBC_REQUIRED");
        this.reports = Objects.requireNonNull(reports, "REPORT_LIFECYCLE_STORE_REQUIRED");
        this.transactions = Objects.requireNonNull(transactions, "RUN_RESULT_TRANSACTION_REQUIRED");
        this.clock = Objects.requireNonNull(clock, "RUN_RESULT_CLOCK_REQUIRED");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null
                || manager.getDataSource() != jdbc.getDataSource()
                || !reports.sharesDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException(
                    "Trusted run-result adapter requires one shared writable REQUIRED DataSource transaction");
    }

    /** Binds facts using the report credentials resolved for this request. */
    public Binding bind(Caller caller, RunToken token, BindingDraft draft,
                        String reportOwner, String reportCapability) {
        RequestContext context = context(caller, token, reportOwner, reportCapability);
        return context.coordinator().bind(token, Objects.requireNonNull(draft, "RUN_RESULT_DRAFT_REQUIRED"));
    }

    /** Composition guard for the atomic publication boundary. */
    public boolean usesLifecycleStore(JdbcReportLifecycleStore lifecycle) {
        return reports == lifecycle;
    }

    /** Composition guard for the atomic publication boundary. */
    public boolean usesTransactionTemplate(TransactionTemplate template) {
        return transactions == template;
    }

    /**
     * Runs a trusted action while the exact run and any existing result binding are locked. The
     * action is deliberately supplied by a typed composition boundary, not a transport caller.
     */
    public <T> T withCurrentRunAndBinding(Caller caller, RunToken token,
                                          String reportOwner, String reportCapability,
                                          Supplier<T> action) {
        RequestContext context = context(caller, token, reportOwner, reportCapability);
        return context.results().withCurrentRunAndBinding(token, Objects.requireNonNull(action,
                "RUN_RESULT_ACTION_REQUIRED"));
    }

    /** Releases only the exact report reference recorded by the current run revision. */
    public long release(Caller caller, RunToken token, ReportRef reportRef,
                        String reportOwner, String reportCapability) {
        RequestContext context = context(caller, token, reportOwner, reportCapability);
        return context.coordinator().release(token, Objects.requireNonNull(reportRef, "REPORT_REF_REQUIRED"));
    }

    private RequestContext context(Caller caller, RunToken token, String reportOwner, String reportCapability) {
        Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
        Objects.requireNonNull(token, "RUN_RESULT_TOKEN_REQUIRED");
        if (token.definition() == null || !caller.equals(token.definition().caller()))
            throw new SecurityException("RUN_RESULT_ACCESS_DENIED");
        ReportLifecycleStore.require(reportOwner, "REPORT_OWNER_INVALID");
        ReportLifecycleStore.require(reportCapability, "REPORT_CAPABILITY_INVALID");

        CampaignRunReportBindingVerifier verifier =
                new CampaignRunReportBindingVerifier(reports, reportOwner, reportCapability);
        JdbcCampaignRunResultStore results =
                new JdbcCampaignRunResultStore(jdbc, transactions, clock, verifier);
        JdbcCampaignRunResultBindingCoordinator coordinator =
                new JdbcCampaignRunResultBindingCoordinator(
                        results, reports, jdbc, transactions, reportOwner, reportCapability);
        return new RequestContext(results, coordinator);
    }

    private record RequestContext(JdbcCampaignRunResultStore results,
                                  JdbcCampaignRunResultBindingCoordinator coordinator) {}
}
