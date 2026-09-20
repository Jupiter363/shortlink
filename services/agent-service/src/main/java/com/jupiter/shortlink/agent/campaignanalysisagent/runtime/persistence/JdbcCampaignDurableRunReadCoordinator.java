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
        FactOutcome<Void> facts = readFacts(request, null, false);
        return facts.status() == Outcome.Status.NO_BINDING
                ? Outcome.noBinding() : Outcome.bound(facts.snapshot());
    }

    /**
     * Runs a typed projector before the shared transaction closes.  The report projector receives
     * an already-authorized, locked lifecycle row and must immediately return a sanitized value;
     * it must not retain or publish the row.  The snapshot projector then sees the raw snapshot
     * and that sanitized report value while all coordinator locks are still held.
     */
    public <R, T> ProjectedOutcome<T> readProjected(Request request,
                                                    ReportProjector<R> reportProjector,
                                                    SnapshotProjector<R, T> snapshotProjector) {
        Objects.requireNonNull(request, "RUN_READ_REQUEST_REQUIRED");
        Objects.requireNonNull(snapshotProjector, "RUN_READ_PROJECTOR_REQUIRED");
        return transactions.execute(status -> {
            FactOutcome<R> facts = readFactsInCurrentTransaction(request, reportProjector, true);
            if (facts.status() == Outcome.Status.NO_BINDING) return ProjectedOutcome.noBinding();
            T projected = Objects.requireNonNull(snapshotProjector.project(facts.snapshot(), facts.report()),
                    "RUN_READ_PROJECTED_VALUE_REQUIRED");
            return ProjectedOutcome.bound(projected);
        });
    }

    private <R> FactOutcome<R> readFacts(Request request, ReportProjector<R> reportProjector,
                                          boolean requireReportProjector) {
        return transactions.execute(status -> readFactsInCurrentTransaction(
                request, reportProjector, requireReportProjector));
    }

    private <R> FactOutcome<R> readFactsInCurrentTransaction(Request request,
                                                               ReportProjector<R> reportProjector,
                                                               boolean requireReportProjector) {
        Optional<CampaignRunResultStore.Binding> binding = bindings.readCurrentBindingLocked(
                request.caller(), request.runId(), request.revision());
        if (binding.isEmpty()) return FactOutcome.noBinding();

        CampaignRunResultStore.Binding value = binding.get();
        ReportAccess access = value.reportRef() == null
                ? null : request.report().orElseThrow(() -> new SecurityException("RUN_RESULT_REPORT_ACCESS_REQUIRED"));
        CampaignResultProgressReader.Snapshot progressSnapshot =
                progress.readInCurrentTransaction(request.caller(), request.runId(), request.revision());
        Optional<ReportIdentity> identity = Optional.empty();
        Optional<R> projectedReport = Optional.empty();
        if (value.reportRef() != null) {
            ReportLifecycleStore.Published published = reports.readLockedInCurrentTransaction(
                    new ReportLifecycleStore.Key(value.reportRef().reportId(), value.reportRef().revision()),
                    access.owner(), access.capability(), access.mode())
                    .orElseThrow(() -> new IllegalStateException("RUN_RESULT_REPORT_NOT_FOUND"));
            ReportRef ref = new ReportRef(published.key().reportId(), published.key().revision());
            identity = Optional.of(new ReportIdentity(ref, published.runId(),
                    value.planId(), published.planRevision(),
                    published.version()));
            if (reportProjector != null) {
                projectedReport = Optional.of(Objects.requireNonNull(
                        reportProjector.project(published, access.mode()),
                        "RUN_RESULT_REPORT_PROJECTED_VALUE_REQUIRED"));
            } else if (requireReportProjector) {
                throw new IllegalStateException("RUN_RESULT_REPORT_PROJECTOR_REQUIRED");
            }
        }
        return FactOutcome.bound(new CampaignDurableRunReadSnapshot(value, progressSnapshot, identity), projectedReport);
    }

    @FunctionalInterface
    public interface ReportProjector<R> {
        R project(ReportLifecycleStore.Published published, ReportLifecycleStore.Mode mode);
    }

    @FunctionalInterface
    public interface SnapshotProjector<R, T> {
        T project(CampaignDurableRunReadSnapshot snapshot, Optional<R> report);
    }

    private record FactOutcome<R>(Outcome.Status status, CampaignDurableRunReadSnapshot snapshot,
                                  Optional<R> report) {
        private FactOutcome {
            Objects.requireNonNull(status, "RUN_READ_STATUS_REQUIRED");
            report = report == null ? Optional.empty() : report;
            if (status == Outcome.Status.BOUND && snapshot == null)
                throw new IllegalArgumentException("RUN_READ_SNAPSHOT_REQUIRED");
            if (status == Outcome.Status.NO_BINDING && snapshot != null)
                throw new IllegalArgumentException("RUN_READ_NO_BINDING_SNAPSHOT_FORBIDDEN");
        }

        static <R> FactOutcome<R> noBinding() {
            return new FactOutcome<>(Outcome.Status.NO_BINDING, null, Optional.empty());
        }

        static <R> FactOutcome<R> bound(CampaignDurableRunReadSnapshot snapshot, Optional<R> report) {
            return new FactOutcome<>(Outcome.Status.BOUND, snapshot, report);
        }
    }

    public record ProjectedOutcome<T>(Status status, Optional<T> value) {
        public ProjectedOutcome {
            Objects.requireNonNull(status, "RUN_READ_STATUS_REQUIRED");
            value = value == null ? Optional.empty() : value;
            if (status == Status.BOUND && value.isEmpty())
                throw new IllegalArgumentException("RUN_READ_PROJECTED_VALUE_REQUIRED");
            if (status == Status.NO_BINDING && value.isPresent())
                throw new IllegalArgumentException("RUN_READ_NO_BINDING_VALUE_FORBIDDEN");
        }

        static <T> ProjectedOutcome<T> noBinding() {
            return new ProjectedOutcome<>(Status.NO_BINDING, Optional.empty());
        }

        static <T> ProjectedOutcome<T> bound(T value) {
            return new ProjectedOutcome<>(Status.BOUND, Optional.of(value));
        }

        public enum Status { NO_BINDING, BOUND }
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
