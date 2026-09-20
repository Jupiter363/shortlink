package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportReadProjection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignDurableRunReadCoordinator;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Converts one JDBC durable snapshot into the existing typed response contract.
 *
 * <p>The bridge invokes the coordinator exactly once.  Progress and report projection happen in
 * the coordinator's transaction, and the binding facts are compared with the derived E68
 * projection before E76 adapts it.  It never calls the E71 read service after the transaction or
 * falls back to a graph response.</p>
 */
public final class CampaignJdbcDurableRunResponseBridge {
    private final JdbcCampaignDurableRunReadCoordinator reads;
    private final ProgressProjector progress;
    private final ReportSnapshotProjector reportPayloads;
    private final ResultProjector results;
    private final CampaignDurableRunResponseAdapter responses;

    public CampaignJdbcDurableRunResponseBridge(JdbcCampaignDurableRunReadCoordinator reads,
                                                ProgressProjector progress,
                                                ReportSnapshotProjector reportPayloads,
                                                ResultProjector results) {
        this(reads, progress, reportPayloads, results, new CampaignDurableRunResponseAdapter());
    }

    public CampaignJdbcDurableRunResponseBridge(JdbcCampaignDurableRunReadCoordinator reads,
                                                ProgressProjector progress,
                                                ReportSnapshotProjector reportPayloads,
                                                ResultProjector results,
                                                CampaignDurableRunResponseAdapter responses) {
        this.reads = Objects.requireNonNull(reads, "RUN_READ_COORDINATOR_REQUIRED");
        this.progress = Objects.requireNonNull(progress, "RUN_PROGRESS_PROJECTOR_REQUIRED");
        this.reportPayloads = Objects.requireNonNull(reportPayloads, "RUN_REPORT_PROJECTOR_REQUIRED");
        this.results = Objects.requireNonNull(results, "RUN_RESULT_PROJECTOR_REQUIRED");
        this.responses = Objects.requireNonNull(responses, "RUN_RESULT_RESPONSE_ADAPTER_REQUIRED");
    }

    public CampaignJdbcDurableRunResponseBridge(JdbcCampaignDurableRunReadCoordinator reads,
                                                ProgressProjector progress,
                                                CampaignReportReadProjection reportPayloads,
                                                CampaignRunReportReadProjection results) {
        this(reads, progress, reportPayloads::projectAuthorized, results::projectPreloaded);
    }

    /** Convenience constructor for the existing progress service's snapshot projection seam. */
    public CampaignJdbcDurableRunResponseBridge(JdbcCampaignDurableRunReadCoordinator reads,
                                                CampaignProgressService progress,
                                                CampaignReportReadProjection reportPayloads,
                                                CampaignRunReportReadProjection results) {
        this(reads, (caller, snapshot) -> progress.read(caller, snapshot), reportPayloads::projectAuthorized,
                results::projectPreloaded);
    }

    public Outcome read(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_BRIDGE_REQUEST_REQUIRED");
        JdbcCampaignDurableRunReadCoordinator.ProjectedOutcome<AgentRunResult> outcome = reads.readProjected(
                request.readRequest(),
                reportPayloads,
                (snapshot, report) -> compose(request, snapshot, report));
        if (outcome.status() == JdbcCampaignDurableRunReadCoordinator.ProjectedOutcome.Status.NO_BINDING)
            return Outcome.noBinding();
        return Outcome.bound(outcome.value().orElseThrow());
    }

    private AgentRunResult compose(Request request,
                                   CampaignDurableRunReadSnapshot snapshot,
                                   Optional<CampaignReportReadProjection.Snapshot> report) {
        CampaignProgressView current = progress.project(request.readRequest().caller(), snapshot.progress());
        CampaignRunResultProjection.Projection projection = results.project(current, report);
        CampaignRunResultStore.Binding binding = snapshot.binding();
        if (!binding.runId().equals(projection.runId())
                || !binding.planId().equals(projection.planId())
                || binding.revision() != projection.revision()
                || binding.executionStatus() != projection.executionStatus()
                || !binding.nextAction().equals(projection.nextAction())
                || !binding.limitations().equals(projection.limitations()))
            throw new IllegalStateException("RUN_RESULT_BINDING_FACT_MISMATCH");
        var expectedReport = binding.reportRef();
        var actualReport = projection.report() == null ? null : projection.report().reportRef();
        if (!Objects.equals(expectedReport, actualReport))
            throw new IllegalStateException("RUN_RESULT_BINDING_REPORT_MISMATCH");
        if (!request.expectedPlanId().equals(projection.planId()))
            throw new IllegalStateException("RUN_RESULT_PLAN_IDENTITY_MISMATCH");
        return responses.adapt(new CampaignDurableRunResponseAdapter.Request(
                request.base(), new CampaignDurableRunResponseAdapter.Identity(
                        request.readRequest().runId(), projection.planId(), request.readRequest().revision()),
                Optional.of(projection))).orElseThrow(
                () -> new IllegalStateException("RUN_RESULT_RESPONSE_NOT_CREATED"));
    }

    @FunctionalInterface
    public interface ProgressProjector {
        CampaignProgressView project(Caller caller, CampaignResultProgressReader.Snapshot snapshot);
    }

    @FunctionalInterface
    public interface ReportSnapshotProjector
            extends JdbcCampaignDurableRunReadCoordinator.ReportProjector<CampaignReportReadProjection.Snapshot> { }

    @FunctionalInterface
    public interface ResultProjector {
        CampaignRunResultProjection.Projection project(
                CampaignProgressView progress, Optional<CampaignReportReadProjection.Snapshot> report);
    }

    public record Request(AgentRunResult base,
                          JdbcCampaignDurableRunReadCoordinator.Request readRequest,
                          String expectedPlanId) {
        public Request {
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            Objects.requireNonNull(readRequest, "RUN_READ_REQUEST_REQUIRED");
            if (expectedPlanId == null || expectedPlanId.isBlank() || expectedPlanId.length() > 96)
                throw new IllegalArgumentException("RUN_RESULT_PLAN_ID_INVALID");
        }
    }

    public record Outcome(Status status, Optional<AgentRunResult> response) {
        public Outcome {
            Objects.requireNonNull(status, "RUN_RESULT_OUTCOME_STATUS_REQUIRED");
            response = response == null ? Optional.empty() : response;
            if (status == Status.BOUND_RESPONSE && response.isEmpty())
                throw new IllegalArgumentException("RUN_RESULT_BOUND_RESPONSE_REQUIRED");
            if (status == Status.NO_BINDING && response.isPresent())
                throw new IllegalArgumentException("RUN_RESULT_NO_BINDING_RESPONSE_FORBIDDEN");
        }

        static Outcome noBinding() { return new Outcome(Status.NO_BINDING, Optional.empty()); }
        static Outcome bound(AgentRunResult response) { return new Outcome(Status.BOUND_RESPONSE, Optional.of(response)); }

        public enum Status { BOUND_RESPONSE, NO_BINDING }
    }
}
