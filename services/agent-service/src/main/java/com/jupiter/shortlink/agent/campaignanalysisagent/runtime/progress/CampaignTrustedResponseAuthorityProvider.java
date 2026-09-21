package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Binds already trusted response authority facts into one request-local opaque context.
 *
 * <p>This class deliberately resolves no principal, database row, protocol metadata, or report
 * credential. Those facts must be supplied by trusted infrastructure and are checked here before
 * the context can create the transport's grant request.</p>
 */
public final class CampaignTrustedResponseAuthorityProvider {
    public Bound bind(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_AUTHORITY_REQUEST_REQUIRED");
        CampaignResponseProtocolMetadataResolver.Request protocol = request.protocol();
        CampaignRunHandle handle = request.handle();
        if (!protocol.caller().equals(request.caller()) || !protocol.caller().equals(handle.caller()))
            throw new SecurityException("CAMPAIGN_RESPONSE_CALLER_MISMATCH");
        CampaignRunHandleResolver.Request reference = protocol.handleRequest();
        if (!reference.sessionId().equals(handle.sessionId())
                || !reference.runId().equals(handle.runId())
                || reference.revision() != handle.revision()
                || !reference.expectedPlanId().equals(handle.planId()))
            throw new SecurityException("CAMPAIGN_RESPONSE_HANDLE_BINDING_MISMATCH");
        if (request.metadata().runKind() == CampaignResponseCapabilityGate.RunKind.EXISTING
                && request.metadata().identity().isEmpty())
            throw new SecurityException("CAMPAIGN_RESPONSE_EXISTING_IDENTITY_REQUIRED");
        if (request.metadata().identity().isPresent() && !request.metadata().matches(handle))
            throw new SecurityException("CAMPAIGN_RESPONSE_METADATA_IDENTITY_MISMATCH");
        if (!request.grant().bindsTo(handle.caller(), handle))
            throw new SecurityException("REPORT_ACCESS_GRANT_BINDING_MISMATCH");
        if (request.grant().mode() != request.mode())
            throw new SecurityException("REPORT_ACCESS_MODE_MISMATCH");
        return new Bound(protocol, request.metadata(), handle, request.grant(), request.mode());
    }

    public record Request(Caller caller,
                          CampaignResponseProtocolMetadataResolver.Request protocol,
                          CampaignResponseProtocolMetadata metadata,
                          CampaignRunHandle handle,
                          CampaignReportAccessGrant grant,
                          ReportLifecycleStore.Mode mode) {
        public Request {
            Objects.requireNonNull(caller, "CAMPAIGN_RESPONSE_CALLER_REQUIRED");
            Objects.requireNonNull(protocol, "CAMPAIGN_RESPONSE_PROTOCOL_REQUEST_REQUIRED");
            Objects.requireNonNull(metadata, "CAMPAIGN_RESPONSE_METADATA_REQUIRED");
            Objects.requireNonNull(handle, "CAMPAIGN_RESPONSE_HANDLE_REQUIRED");
            Objects.requireNonNull(grant, "REPORT_ACCESS_GRANT_REQUIRED");
            Objects.requireNonNull(mode, "REPORT_ACCESS_MODE_REQUIRED");
        }
    }

    /** Immutable bound context; report credentials remain package-private and opaque. */
    public static final class Bound {
        private final CampaignResponseProtocolMetadataResolver.Request protocol;
        private final CampaignResponseProtocolMetadata metadata;
        private final CampaignRunHandle handle;
        private final CampaignReportAccessGrant grant;
        private final ReportLifecycleStore.Mode mode;

        private Bound(CampaignResponseProtocolMetadataResolver.Request protocol,
                      CampaignResponseProtocolMetadata metadata, CampaignRunHandle handle,
                      CampaignReportAccessGrant grant, ReportLifecycleStore.Mode mode) {
            this.protocol = protocol;
            this.metadata = metadata;
            this.handle = handle;
            this.grant = grant;
            this.mode = mode;
        }

        public CampaignResponseProtocolMetadataResolver.Request protocol() { return protocol; }
        public CampaignResponseProtocolMetadata metadata() { return metadata; }
        public CampaignRunHandle handle() { return handle; }
        public ReportLifecycleStore.Mode mode() { return mode; }

        CampaignDurableResponseTransportAdapter.GrantAuthorityRequest transportRequest(
                AgentRunResult base, Set<String> clientCapabilities) {
            return new CampaignDurableResponseTransportAdapter.GrantAuthorityRequest(
                    protocol, base, clientCapabilities, Optional.of(grant));
        }
    }
}
