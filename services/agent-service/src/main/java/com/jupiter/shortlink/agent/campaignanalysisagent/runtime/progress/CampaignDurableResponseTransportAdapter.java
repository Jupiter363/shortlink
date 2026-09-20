package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a server-owned run handle before handing a response request to the E85 route.
 *
 * <p>This is a typed transport seam, not an HTTP adapter.  Capability selection happens first so
 * legacy and upgrade responses never touch the durable resolver.  A durable request carries only
 * the exact identity read from the authoritative resolver; session, trace and message text are
 * never used to infer a run.  The resolver is request-independent infrastructure, while all
 * caller and run facts remain in the request-local reference until the resolver returns a
 * server-owned handle.</p>
 */
public final class CampaignDurableResponseTransportAdapter {
    private final CampaignResponseRouteAdapter route;
    private final CampaignRunHandleResolver handles;
    private final CampaignResponseProtocolMetadataResolver protocols;

    public CampaignDurableResponseTransportAdapter(CampaignResponseRouteAdapter route,
                                                   CampaignRunHandleResolver handles) {
        this(route, handles, null);
    }

    /** Authority-aware composition; protocol facts must come from a server-owned resolver. */
    public CampaignDurableResponseTransportAdapter(CampaignResponseRouteAdapter route,
                                                   CampaignRunHandleResolver handles,
                                                   CampaignResponseProtocolMetadataResolver protocols) {
        this.route = Objects.requireNonNull(route, "CAMPAIGN_RESPONSE_ROUTE_REQUIRED");
        this.handles = Objects.requireNonNull(handles, "CAMPAIGN_RESPONSE_HANDLE_RESOLVER_REQUIRED");
        this.protocols = protocols;
    }

    /** Resolves only after the E79 gate selects the durable path; no fallback is attempted. */
    public CampaignResponseRouteAdapter.Outcome resolve(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_TRANSPORT_REQUEST_REQUIRED");
        CampaignResponseCapabilityGate.Decision decision = route.decide(request.capability());
        if (decision != CampaignResponseCapabilityGate.Decision.DURABLE_V2)
            return route.route(new CampaignResponseRouteAdapter.Request(request.capability()));

        CampaignRunHandleResolver.Request reference = request.run().orElseThrow(
                () -> new IllegalArgumentException("CAMPAIGN_DURABLE_RUN_REFERENCE_REQUIRED"));
        Optional<CampaignRunHandle> resolved = handles.resolve(reference);
        if (resolved == null || resolved.isEmpty())
            throw new IllegalStateException("CAMPAIGN_RUN_HANDLE_NOT_FOUND");
        CampaignRunHandle handle = resolved.get();
        verify(reference, handle);

        CampaignJdbcDurableRunResponseBridgeFactory.Request durable =
                new CampaignJdbcDurableRunResponseBridgeFactory.Request(
                        request.base(), handle.caller(), handle.runId(), handle.revision(),
                        request.report(), handle.planId());
        return route.route(new CampaignResponseRouteAdapter.Request(
                request.capability(), Optional.of(durable)));
    }

    /**
     * Authority-aware response path.  The caller supplies only client capabilities and a lookup
     * reference; run kind, server protocol and enablement are read from the trusted metadata
     * resolver.  Missing metadata is an error, never an implicit legacy decision.
     */
    public CampaignResponseRouteAdapter.Outcome resolve(AuthorityRequest request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_AUTHORITY_REQUEST_REQUIRED");
        if (protocols == null)
            throw new IllegalStateException("CAMPAIGN_RESPONSE_PROTOCOL_RESOLVER_REQUIRED");
        Optional<CampaignResponseProtocolMetadata> metadata = protocols.resolve(request.protocol());
        if (metadata == null || metadata.isEmpty())
            throw new IllegalStateException("CAMPAIGN_RESPONSE_PROTOCOL_METADATA_UNAVAILABLE");
        if (metadata.get().runKind() == CampaignResponseCapabilityGate.RunKind.EXISTING
                && metadata.get().identity().isEmpty())
            throw new IllegalStateException("CAMPAIGN_RESPONSE_EXISTING_IDENTITY_REQUIRED");
        CampaignResponseCapabilityGate.Decision decision = route.decide(metadata.get(),
                request.clientCapabilities());
        if (decision != CampaignResponseCapabilityGate.Decision.DURABLE_V2)
            return route.route(decision, Optional.empty());

        CampaignResponseProtocolMetadataResolver.Request protocol = request.protocol();
        CampaignRunHandleResolver.Request reference = protocol.handleRequest();
        Optional<CampaignRunHandle> resolved = handles.resolve(reference);
        if (resolved == null || resolved.isEmpty())
            throw new IllegalStateException("CAMPAIGN_RUN_HANDLE_NOT_FOUND");
        CampaignRunHandle handle = resolved.get();
        verify(reference, handle);
        if (metadata.get().identity().isPresent() && !metadata.get().matches(handle))
            throw new SecurityException("CAMPAIGN_RESPONSE_METADATA_IDENTITY_MISMATCH");

        CampaignJdbcDurableRunResponseBridgeFactory.Request durable =
                new CampaignJdbcDurableRunResponseBridgeFactory.Request(
                        request.base(), handle.caller(), handle.runId(), handle.revision(),
                        Optional.empty(), handle.planId());
        return route.route(decision, Optional.of(durable));
    }

    /** Authority path with a principal-bound grant; raw ReportAccess is never accepted here. */
    public CampaignResponseRouteAdapter.Outcome resolve(GrantAuthorityRequest request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_GRANT_AUTHORITY_REQUEST_REQUIRED");
        if (protocols == null)
            throw new IllegalStateException("CAMPAIGN_RESPONSE_PROTOCOL_RESOLVER_REQUIRED");
        Optional<CampaignResponseProtocolMetadata> metadata = protocols.resolve(request.protocol());
        if (metadata == null || metadata.isEmpty())
            throw new IllegalStateException("CAMPAIGN_RESPONSE_PROTOCOL_METADATA_UNAVAILABLE");
        if (metadata.get().runKind() == CampaignResponseCapabilityGate.RunKind.EXISTING
                && metadata.get().identity().isEmpty())
            throw new IllegalStateException("CAMPAIGN_RESPONSE_EXISTING_IDENTITY_REQUIRED");
        CampaignResponseCapabilityGate.Decision decision = route.decide(metadata.get(),
                request.clientCapabilities());
        if (decision != CampaignResponseCapabilityGate.Decision.DURABLE_V2)
            return route.route(decision, Optional.empty());
        CampaignRunHandleResolver.Request reference = request.protocol().handleRequest();
        Optional<CampaignRunHandle> resolved = handles.resolve(reference);
        if (resolved == null || resolved.isEmpty())
            throw new IllegalStateException("CAMPAIGN_RUN_HANDLE_NOT_FOUND");
        CampaignRunHandle handle = resolved.get();
        verify(reference, handle);
        if (metadata.get().identity().isPresent() && !metadata.get().matches(handle))
            throw new SecurityException("CAMPAIGN_RESPONSE_METADATA_IDENTITY_MISMATCH");
        CampaignReportAccessGrant grant = request.reportGrant().orElseThrow(
                () -> new SecurityException("REPORT_ACCESS_GRANT_REQUIRED"));
        if (!grant.bindsTo(handle.caller(), handle))
            throw new SecurityException("REPORT_ACCESS_GRANT_BINDING_MISMATCH");
        CampaignJdbcDurableRunResponseBridgeFactory.Request durable =
                new CampaignJdbcDurableRunResponseBridgeFactory.Request(
                        request.base(), handle.caller(), handle.runId(), handle.revision(),
                        Optional.of(grant.reportAccess()), handle.planId());
        return route.route(decision, Optional.of(durable));
    }

    private static void verify(CampaignRunHandleResolver.Request reference, CampaignRunHandle handle) {
        if (handle == null) throw new SecurityException("CAMPAIGN_RUN_HANDLE_INVALID");
        if (!reference.caller().equals(handle.caller())
                || !reference.sessionId().equals(handle.sessionId())
                || !reference.runId().equals(handle.runId())
                || reference.revision() != handle.revision()
                || !reference.expectedPlanId().equals(handle.planId()))
            throw new SecurityException("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
    }

    /** Base graph response is retained only for a legacy trusted compatibility caller. */
    @Deprecated
    public record Request(
            CampaignResponseCapabilityGate.Request capability,
            AgentRunResult base,
            Optional<CampaignRunHandleResolver.Request> run,
            Optional<CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess> report) {
        public Request {
            Objects.requireNonNull(capability, "CAMPAIGN_RESPONSE_CAPABILITY_REQUEST_REQUIRED");
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            run = run == null ? Optional.empty() : run;
            report = report == null ? Optional.empty() : report;
            if (run.isEmpty() && report.isPresent())
                throw new IllegalArgumentException("CAMPAIGN_REPORT_ACCESS_WITHOUT_RUN");
        }

        public Request(CampaignResponseCapabilityGate.Request capability, AgentRunResult base,
                       CampaignRunHandleResolver.Request run) {
            this(capability, base, Optional.ofNullable(run), Optional.empty());
        }
    }

    /** Request carrying no caller-controlled server protocol or run-kind fields. */
    public record AuthorityRequest(
            CampaignResponseProtocolMetadataResolver.Request protocol,
            AgentRunResult base,
            Set<String> clientCapabilities) {
        public AuthorityRequest {
            Objects.requireNonNull(protocol, "CAMPAIGN_RESPONSE_PROTOCOL_REQUEST_REQUIRED");
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            if (clientCapabilities == null)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITIES_REQUIRED");
            if (clientCapabilities.stream().anyMatch(value -> value == null || value.isBlank()))
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITY_INVALID");
            clientCapabilities = Set.copyOf(clientCapabilities);
        }
    }

    /** Trusted metadata request carrying only an opaque principal-bound report grant. */
    public record GrantAuthorityRequest(
            CampaignResponseProtocolMetadataResolver.Request protocol,
            AgentRunResult base,
            Set<String> clientCapabilities,
            Optional<CampaignReportAccessGrant> reportGrant) {
        public GrantAuthorityRequest {
            Objects.requireNonNull(protocol, "CAMPAIGN_RESPONSE_PROTOCOL_REQUEST_REQUIRED");
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            if (clientCapabilities == null)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITIES_REQUIRED");
            if (clientCapabilities.stream().anyMatch(value -> value == null || value.isBlank()))
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITY_INVALID");
            clientCapabilities = Set.copyOf(clientCapabilities);
            reportGrant = reportGrant == null ? Optional.empty() : reportGrant;
            if (protocol.run().isEmpty() && reportGrant.isPresent())
                throw new IllegalArgumentException("CAMPAIGN_REPORT_ACCESS_WITHOUT_RUN");
        }

        public GrantAuthorityRequest(CampaignResponseProtocolMetadataResolver.Request protocol,
                                     AgentRunResult base, Set<String> clientCapabilities) {
            this(protocol, base, clientCapabilities, Optional.empty());
        }
    }
}
