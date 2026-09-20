package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignDurableRunReadCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignRunReportBindingVerifier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Request-independent infrastructure factory for the E83 JDBC durable response bridge.
 *
 * <p>The factory keeps only JDBC handles and stateless projectors.  A binding store, report
 * verifier, coordinator and bridge are created for each request, so caller and report
 * credentials can never become singleton state.  This class is deliberately transport-neutral;
 * it does not resolve a run from a session, invoke Graph, or register an HTTP/chat route.</p>
 */
public final class CampaignJdbcDurableRunResponseBridgeFactory {
    private static final CampaignRunResultStore.ReportBindingVerifier NO_REPORT_BINDING =
            (run, report) -> false;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JdbcCampaignResultProgressReader progressReader;
    private final JdbcReportLifecycleStore reports;
    private final CampaignJdbcDurableRunResponseBridge.ProgressProjector progress;
    private final CampaignJdbcDurableRunResponseBridge.ReportSnapshotProjector report;
    private final CampaignJdbcDurableRunResponseBridge.ResultProjector result;

    /**
     * Composition constructor used by trusted Spring configuration.  The supplied projections
     * must be request-independent and must not retain caller, report owner or capability state.
     */
    public CampaignJdbcDurableRunResponseBridgeFactory(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Clock clock,
            JdbcCampaignResultProgressReader progressReader,
            JdbcReportLifecycleStore reports,
            CampaignProgressService progress,
            CampaignReportReadProjection report,
            CampaignRunReportReadProjection result) {
        this(jdbc, transactions, clock, progressReader, reports,
                progressProjector(jdbc, transactions, progressReader, progress),
                report::projectAuthorized,
                result::projectPreloaded);
    }

    private static CampaignJdbcDurableRunResponseBridge.ProgressProjector progressProjector(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            JdbcCampaignResultProgressReader reader,
            CampaignProgressService progress) {
        Objects.requireNonNull(progress, "RUN_PROGRESS_SERVICE_REQUIRED");
        if (!progress.isComposedWith(jdbc, transactions, reader))
            throw new IllegalArgumentException(
                    "Durable response bridge progress service must share the JDBC snapshot transaction");
        return (caller, snapshot) -> progress.read(caller, snapshot);
    }

    /**
     * Typed projector constructor for deterministic composition tests and narrow adapters.
     * Projectors receive only the exact facts captured by the E82 coordinator.
     */
    public CampaignJdbcDurableRunResponseBridgeFactory(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Clock clock,
            JdbcCampaignResultProgressReader progressReader,
            JdbcReportLifecycleStore reports,
            CampaignJdbcDurableRunResponseBridge.ProgressProjector progress,
            CampaignJdbcDurableRunResponseBridge.ReportSnapshotProjector report,
            CampaignJdbcDurableRunResponseBridge.ResultProjector result) {
        this.jdbc = Objects.requireNonNull(jdbc, "RUN_RESULT_JDBC_REQUIRED");
        this.transactions = Objects.requireNonNull(transactions, "RUN_RESULT_TRANSACTION_REQUIRED");
        this.clock = Objects.requireNonNull(clock, "RUN_RESULT_CLOCK_REQUIRED");
        this.progressReader = Objects.requireNonNull(progressReader, "RUN_PROGRESS_READER_REQUIRED");
        this.reports = Objects.requireNonNull(reports, "REPORT_LIFECYCLE_STORE_REQUIRED");
        this.progress = Objects.requireNonNull(progress, "RUN_PROGRESS_PROJECTOR_REQUIRED");
        this.report = Objects.requireNonNull(report, "RUN_REPORT_PROJECTOR_REQUIRED");
        this.result = Objects.requireNonNull(result, "RUN_RESULT_PROJECTOR_REQUIRED");
        if (!(transactions.getTransactionManager() instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly()
                || !progressReader.sharesDataSource(jdbc)
                || !progressReader.usesTransactionTemplate(transactions)
                || !reports.sharesDataSource(jdbc)
                || !reports.usesTransactionTemplate(transactions)) {
            throw new IllegalArgumentException(
                    "Durable response bridge requires one shared writable REQUIRED DataSource transaction");
        }
    }

    /** Reads one exact server-owned run handle; no latest/history fallback is attempted. */
    public CampaignJdbcDurableRunResponseBridge.Outcome read(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_BRIDGE_REQUEST_REQUIRED");
        CampaignRunResultStore.ReportBindingVerifier verifier = request.report()
                .<CampaignRunResultStore.ReportBindingVerifier>map(access ->
                        new CampaignRunReportBindingVerifier(reports, access.owner(), access.capability()))
                .orElse(NO_REPORT_BINDING);
        JdbcCampaignRunResultStore bindings = new JdbcCampaignRunResultStore(
                jdbc, transactions, clock, verifier);
        JdbcCampaignDurableRunReadCoordinator coordinator = new JdbcCampaignDurableRunReadCoordinator(
                bindings, progressReader, reports, jdbc, transactions);
        CampaignJdbcDurableRunResponseBridge bridge = new CampaignJdbcDurableRunResponseBridge(
                coordinator, progress, report, result);
        Optional<JdbcCampaignDurableRunReadCoordinator.ReportAccess> access = request.report()
                .map(value -> new JdbcCampaignDurableRunReadCoordinator.ReportAccess(
                        value.owner(), value.capability(), value.mode()));
        return bridge.read(new CampaignJdbcDurableRunResponseBridge.Request(
                request.base(),
                new JdbcCampaignDurableRunReadCoordinator.Request(
                        request.caller(), request.runId(), request.revision(), access),
                request.expectedPlanId()));
    }

    public record Request(AgentRunResult base,
                          Caller caller,
                          String runId,
                          int revision,
                          Optional<ReportAccess> report,
                          String expectedPlanId) {
        public Request {
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
            ReportLifecycleStore.require(runId, "RUN_RESULT_RUN_ID_INVALID");
            if (revision < 1) throw new IllegalArgumentException("RUN_RESULT_REVISION_INVALID");
            report = report == null ? Optional.empty() : report;
            ReportLifecycleStore.require(expectedPlanId, "RUN_RESULT_PLAN_ID_INVALID");
        }

        public Request(AgentRunResult base, Caller caller, String runId, int revision,
                       String expectedPlanId) {
            this(base, caller, runId, revision, Optional.empty(), expectedPlanId);
        }
    }

    /** Request-owned report authorization; it is never retained by the factory. */
    public record ReportAccess(String owner, String capability, ReportLifecycleStore.Mode mode) {
        public ReportAccess {
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }
    }
}
