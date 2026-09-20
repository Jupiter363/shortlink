package com.jupiter.shortlink.agent.campaignanalysisagent.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted, typed application boundary for durable campaign reports.
 *
 * <p>The publisher owns evidence validation and report construction.  This boundary owns the
 * caller supplied owner/capability and retention metadata, performs authorization before a
 * publisher call, and exposes only durable operations.  It intentionally has no chat, tool, or
 * Spring adapter: a future adapter must resolve the owner and capability before constructing the
 * typed request.</p>
 */
public final class CampaignReportApplicationService {
    private final CampaignReportPublisher publisher;
    private final AccessAuthorizer authorizer;

    public CampaignReportApplicationService(CampaignReportPublisher publisher,
                                            AccessAuthorizer authorizer) {
        this.publisher = Objects.requireNonNull(publisher, "REPORT_PUBLISHER_REQUIRED");
        this.authorizer = Objects.requireNonNull(authorizer, "REPORT_AUTHORIZER_REQUIRED");
        if (!publisher.hasDurableStore()) {
            throw new IllegalStateException("REPORT_LIFECYCLE_STORE_REQUIRED");
        }
    }

    /**
     * Publishes one immutable report revision through the durable lifecycle store.
     *
     * <p>Lifecycle idempotency, draft conflicts, expiry, and evidence failures deliberately pass
     * through unchanged so callers can distinguish a replay from a rejected publication.</p>
     */
    public ReportLifecycleStore.Published publish(PublishRequest request) {
        Objects.requireNonNull(request, "REPORT_PUBLISH_REQUEST_REQUIRED");
        authorize(request.owner(), request.capability(), Operation.PUBLISH);
        return publisher.publishDurable(request.publisherRequest(), new CampaignReportPublisher.Publication(
                request.owner(), request.capability(), request.retainedUntil(), request.reuseExpiresAt()));
    }

    /**
     * Reads a durable report with the requested lifecycle mode.  HISTORY_VIEW and EXPORT are
     * passed through exactly; the lifecycle store remains the authority for retention and reuse
     * expiry as well as persisted owner/capability matching.
     */
    public Optional<ReportLifecycleStore.Published> read(ReadRequest request) {
        Objects.requireNonNull(request, "REPORT_READ_REQUEST_REQUIRED");
        authorize(request.owner(), request.capability(), operation(request.mode()));
        return publisher.read(request.reportId(), request.revision(), request.owner(), request.capability(),
                request.mode());
    }

    private void authorize(String owner, String capability, Operation operation) {
        if (!authorizer.mayAccess(owner, capability, operation)) {
            throw new SecurityException("REPORT_ACCESS_DENIED");
        }
    }

    private static Operation operation(ReportLifecycleStore.Mode mode) {
        return mode == ReportLifecycleStore.Mode.EXPORT ? Operation.EXPORT : Operation.HISTORY_VIEW;
    }

    /** Explicit operation names let an adapter apply different history/export policies. */
    public enum Operation { PUBLISH, HISTORY_VIEW, EXPORT }

    @FunctionalInterface
    public interface AccessAuthorizer {
        /** Recheck the current owner and capability immediately before durable I/O. */
        boolean mayAccess(String owner, String capability, Operation operation);
    }

    /** Server-typed publication metadata; no untyped map can grant report access or retention. */
    public record PublishRequest(CampaignReportPublisher.PublishRequest publisherRequest,
                                 String owner, String capability, Instant retainedUntil,
                                 Instant reuseExpiresAt) {
        public PublishRequest {
            Objects.requireNonNull(publisherRequest, "REPORT_PUBLISHER_REQUEST_REQUIRED");
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(retainedUntil, "REPORT_RETAINED_UNTIL_REQUIRED");
            Objects.requireNonNull(reuseExpiresAt, "REPORT_REUSE_EXPIRES_AT_REQUIRED");
        }
    }

    /** Server-typed report read request preserving the lifecycle mode distinction. */
    public record ReadRequest(String reportId, int revision, String owner, String capability,
                              ReportLifecycleStore.Mode mode) {
        public ReadRequest {
            ReportLifecycleStore.require(reportId, "REPORT_ID_INVALID");
            if (revision < 1) throw new IllegalArgumentException("REPORT_REVISION_INVALID");
            ReportLifecycleStore.require(owner, "REPORT_OWNER_INVALID");
            ReportLifecycleStore.require(capability, "REPORT_CAPABILITY_INVALID");
            Objects.requireNonNull(mode, "REPORT_MODE_REQUIRED");
        }
    }
}
