package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunReadSnapshot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignDurableRunReadSnapshot.ReportIdentity;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResultProgressReader;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trusted JDBC-only coordinator for one exact durable run read.
 *
 * <p>The coordinator locks rows in the established run → binding → steps/receipts → report order
 * and returns only typed identity/progress facts. It is intentionally not a Spring, HTTP, Graph,
 * or chat adapter; callers still perform the normal E68/E69 projection after this fact boundary.</p>
 */
public final class JdbcCampaignDurableRunReadCoordinator {
    private final JdbcCampaignRunResultStore bindings;
    private final JdbcCampaignResultProgressReader progress;
    private final JdbcReportLifecycleStore reports;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcCampaignDurableRunReadCoordinator(JdbcCampaignRunResultStore bindings,
                                                 JdbcCampaignResultProgressReader progress,
                                                 JdbcReportLifecycleStore reports,
                                                 JdbcTemplate jdbc,
                                                 TransactionTemplate transactions) {
        this.bindings = Objects.requireNonNull(bindings, "RUN_RESULT_STORE_REQUIRED");
        this.progress = Objects.requireNonNull(progress, "RUN_PROGRESS_READER_REQUIRED");
        this.reports = Objects.requireNonNull(reports, "REPORT_STORE_REQUIRED");
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC_REQUIRED");
        this.transactions = Objects.requireNonNull(transactions, "TRANSACTION_TEMPLATE_REQUIRED");
        if (!bindings.sharesDataSource(jdbc) || !bindings.usesTransactionTemplate(transactions)
                || !progress.sharesDataSource(jdbc) || !progress.usesTransactionTemplate(transactions)
                || !reports.sharesDataSource(jdbc) || !reports.usesTransactionTemplate(transactions)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException("Durable run reads require one writable REQUIRED DataSource transaction");
    }

    public Outcome read(Request request) {
        Objects.requireNonNull(request, "RUN_READ_REQUEST_REQUIRED");
        return transactions.execute(status -> {
            Optional<CampaignRunResultStore.Binding> binding = bindings.readCurrentBindingLocked(
                    request.caller(), request.runId(), request.revision());
            if (binding.isEmpty()) return Outcome.noBinding();

            CampaignRunResultStore.Binding value = binding.get();
            ReportAccess access = value.reportRef() == null
                    ? null : request.report().orElseThrow(() -> new SecurityException("RUN_RESULT_REPORT_ACCESS_REQUIRED"));
            CampaignResultProgressReader.Snapshot progressSnapshot =
                    progress.readInCurrentTransaction(request.caller(), request.runId(), request.revision());
            Optional<ReportIdentity> report = Optional.empty();
            if (value.reportRef() != null) {
                ReportLifecycleStore.Published published = reports.readLockedInCurrentTransaction(
                        new ReportLifecycleStore.Key(value.reportRef().reportId(), value.reportRef().revision()),
                        access.owner(), access.capability(), access.mode())
                        .orElseThrow(() -> new IllegalStateException("RUN_RESULT_REPORT_NOT_FOUND"));
                ReportRef ref = new ReportRef(published.key().reportId(), published.key().revision());
                report = Optional.of(new ReportIdentity(ref, published.runId(),
                        value.planId(), published.planRevision(),
                        published.version()));
            }
            return Outcome.bound(new CampaignDurableRunReadSnapshot(value, progressSnapshot, report));
        });
    }

    public record Request(Caller caller, String runId, int revision, Optional<ReportAccess> report) {
        public Request {
            Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("RUN_RESULT_RUN_ID_INVALID");
            if (revision < 1) throw new IllegalArgumentException("RUN_RESULT_REVISION_INVALID");
            report = report == null ? Optional.empty() : report;
        }

        public Request(Caller caller, String runId, int revision) {
            this(caller, runId, revision, Optional.empty());
        }
    }

    public record ReportAccess(String owner, String capability, ReportLifecycleStore.Mode mode) {
        public ReportAccess {
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }
    }

    public record Outcome(Status status, Optional<CampaignDurableRunReadSnapshot> snapshot) {
        public Outcome {
            Objects.requireNonNull(status, "RUN_READ_STATUS_REQUIRED");
            snapshot = snapshot == null ? Optional.empty() : snapshot;
            if (status == Status.BOUND && snapshot.isEmpty())
                throw new IllegalArgumentException("RUN_READ_SNAPSHOT_REQUIRED");
            if (status == Status.NO_BINDING && snapshot.isPresent())
                throw new IllegalArgumentException("RUN_READ_NO_BINDING_SNAPSHOT_FORBIDDEN");
        }

        static Outcome noBinding() { return new Outcome(Status.NO_BINDING, Optional.empty()); }
        static Outcome bound(CampaignDurableRunReadSnapshot snapshot) {
            return new Outcome(Status.BOUND, Optional.of(snapshot));
        }

        public enum Status { NO_BINDING, BOUND }
    }
}
