package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

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

    public CampaignDurableResponseTransportAdapter(CampaignResponseRouteAdapter route,
                                                   CampaignRunHandleResolver handles) {
        this.route = Objects.requireNonNull(route, "CAMPAIGN_RESPONSE_ROUTE_REQUIRED");
        this.handles = Objects.requireNonNull(handles, "CAMPAIGN_RESPONSE_HANDLE_RESOLVER_REQUIRED");
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

    private static void verify(CampaignRunHandleResolver.Request reference, CampaignRunHandle handle) {
        if (handle == null) throw new SecurityException("CAMPAIGN_RUN_HANDLE_INVALID");
        if (!reference.caller().equals(handle.caller())
                || !reference.sessionId().equals(handle.sessionId())
                || !reference.runId().equals(handle.runId())
                || reference.revision() != handle.revision()
                || !reference.expectedPlanId().equals(handle.planId()))
            throw new SecurityException("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
    }

    /** Base graph response is retained only for a legacy caller; durable output replaces it. */
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
}
