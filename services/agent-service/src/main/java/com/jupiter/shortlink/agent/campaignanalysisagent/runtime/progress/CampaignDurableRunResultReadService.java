package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only composition of one exact durable result binding and the authorized E69 projection.
 *
 * <p>The binding supplies the server-owned status/action facts. E69 still reads the current
 * progress and the one fixed report reference, then this seam requires both views to agree before
 * returning the sanitized E68 projection. A missing binding is an empty result; no latest
 * revision lookup or report history scan is performed.</p>
 */
public final class CampaignDurableRunResultReadService {
    private final CampaignRunResultStore bindings;
    private final CampaignRunReportReadProjection projection;

    public CampaignDurableRunResultReadService(CampaignRunResultStore bindings,
                                               CampaignRunReportReadProjection projection) {
        this.bindings = Objects.requireNonNull(bindings, "RUN_RESULT_STORE_REQUIRED");
        this.projection = Objects.requireNonNull(projection, "RUN_REPORT_PROJECTION_REQUIRED");
    }

    public Optional<CampaignRunResultProjection.Projection> read(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_READ_REQUEST_REQUIRED");
        Optional<Binding> stored = bindings.read(request.caller(), request.runId(), request.revision());
        if (stored.isEmpty()) return Optional.empty();
        Binding binding = stored.get();
        if (!request.runId().equals(binding.runId()) || request.revision() != binding.revision())
            throw new IllegalStateException("RUN_RESULT_IDENTITY_MISMATCH");

        Optional<CampaignRunReportReadProjection.ReportRead> report = Optional.empty();
        if (binding.reportRef() != null) {
            ReportAccess access = request.report().orElseThrow(
                    () -> new SecurityException("RUN_RESULT_REPORT_ACCESS_REQUIRED"));
            ReportRef ref = binding.reportRef();
            report = Optional.of(new CampaignRunReportReadProjection.ReportRead(
                    ref, access.owner(), access.capability(), access.mode()));
        }

        CampaignRunResultProjection.Projection current = projection.read(
                new CampaignRunReportReadProjection.Request(request.caller(), request.runId(), report));
        validateAgreement(binding, current);
        return Optional.of(current);
    }

    private static void validateAgreement(Binding binding, CampaignRunResultProjection.Projection current) {
        if (!CampaignRunResultProjection.SCHEMA.equals(current.schemaVersion())
                || !binding.runId().equals(current.runId())
                || !binding.planId().equals(current.planId())
                || binding.revision() != current.revision()
                || binding.executionStatus() != current.executionStatus()
                || !binding.nextAction().equals(current.nextAction())
                || !binding.limitations().equals(current.limitations()))
            throw new IllegalStateException("RUN_RESULT_BINDING_FACT_MISMATCH");
        ReportRef expected = binding.reportRef();
        ReportRef actual = current.report() == null ? null : current.report().reportRef();
        if (!Objects.equals(expected, actual))
            throw new IllegalStateException("RUN_RESULT_BINDING_REPORT_MISMATCH");
    }

    public record ReportAccess(String owner, String capability, ReportLifecycleStore.Mode mode) {
        public ReportAccess {
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }
    }

    public record Request(Caller caller, String runId, int revision, Optional<ReportAccess> report) {
        public Request {
            Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
            ReportLifecycleStore.require(runId, "RUN_RESULT_RUN_ID_INVALID");
            if (revision < 1) throw new IllegalArgumentException("RUN_RESULT_REVISION_INVALID");
            report = report == null ? Optional.empty() : report;
        }

        public Request(Caller caller, String runId, int revision) {
            this(caller, runId, revision, Optional.empty());
        }
    }
}
