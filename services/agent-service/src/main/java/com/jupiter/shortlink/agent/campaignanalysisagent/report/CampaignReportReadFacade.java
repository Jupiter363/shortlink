package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Fixed-key, sanitized report read boundary for future transports.
 *
 * <p>The lifecycle application service and {@link CampaignReportReadProjection} remain the
 * authorization and payload validation owners. This facade only defines the route-level shape:
 * callers must name one report revision and one lifecycle mode, while the returned view contains
 * renderable report data without storage, retention, checksum, owner, or capability metadata.</p>
 */
public final class CampaignReportReadFacade {
    public static final String SCHEMA = "campaign-report-view/v1";

    private final Reader reader;

    public CampaignReportReadFacade(CampaignReportReadProjection projection) {
        this(Objects.requireNonNull(projection, "REPORT_PROJECTION_REQUIRED")::read);
    }

    CampaignReportReadFacade(Reader reader) {
        this.reader = Objects.requireNonNull(reader, "REPORT_READER_REQUIRED");
    }

    /**
     * Reads exactly the requested report key. Missing data remains empty; no latest revision or
     * history scan is introduced at this boundary.
     */
    public Optional<View> read(Request request) {
        Objects.requireNonNull(request, "REPORT_VIEW_REQUEST_REQUIRED");
        Optional<CampaignReportReadProjection.Snapshot> snapshot =
                Objects.requireNonNull(reader.read(request.applicationRequest()), "REPORT_READ_RESULT_REQUIRED");
        if (snapshot.isEmpty()) return Optional.empty();

        CampaignReportReadProjection.Snapshot value = snapshot.get();
        if (!request.reportRef().equals(value.reportRef()) || request.mode() != value.mode())
            throw new IllegalStateException("REPORT_VIEW_IDENTITY_MISMATCH");
        return Optional.of(new View(SCHEMA, value.mode(), value.reportRef(), value.runId(), value.planRevision(),
                value.draft(), value.goalAssessments()));
    }

    @FunctionalInterface
    interface Reader {
        Optional<CampaignReportReadProjection.Snapshot> read(CampaignReportApplicationService.ReadRequest request);
    }

    /** Server-typed fixed report key and credentials; no latest or fuzzy lookup is possible. */
    public record Request(String reportId, int revision, String owner, String capability,
                          ReportLifecycleStore.Mode mode) {
        public Request {
            ReportLifecycleStore.require(reportId, "REPORT_ID_INVALID");
            if (revision < 1) throw new IllegalArgumentException("REPORT_REVISION_INVALID");
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }

        private CampaignReportApplicationService.ReadRequest applicationRequest() {
            return new CampaignReportApplicationService.ReadRequest(reportId, revision, owner, capability, mode);
        }

        private CampaignReportPublisher.ReportRef reportRef() {
            return new CampaignReportPublisher.ReportRef(reportId, revision);
        }
    }

    /** Renderable report view. Storage and authorization metadata are intentionally absent. */
    public record View(String schemaVersion, ReportLifecycleStore.Mode mode,
                       CampaignReportPublisher.ReportRef reportRef, String runId, int planRevision,
                       ReportDraft draft, List<GoalAssessment> goalAssessments) {
        public View {
            if (!SCHEMA.equals(schemaVersion) || mode == null || reportRef == null
                    || runId == null || runId.isBlank() || planRevision < 1 || draft == null)
                throw new IllegalArgumentException("REPORT_VIEW_INVALID");
            goalAssessments = goalAssessments == null ? List.of() : List.copyOf(goalAssessments);
        }
    }
}
