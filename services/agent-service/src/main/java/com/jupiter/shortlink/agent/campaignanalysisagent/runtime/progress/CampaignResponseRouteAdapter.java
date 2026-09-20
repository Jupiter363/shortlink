package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral selector for campaign responses.
 *
 * <p>The capability gate owns protocol selection.  Only a {@code DURABLE_V2} decision may invoke
 * the JDBC durable response factory, and that request must already contain a server-owned run
 * handle.  This class does not resolve identities, invoke Graph, or retain request credentials.
 * A legacy caller may render its original graph result after receiving {@link Status#LEGACY_PATH};
 * durable failures never carry that result as a fallback.</p>
 */
public final class CampaignResponseRouteAdapter {
    private final CampaignResponseCapabilityGate gate;
    private final DurableResponseReader durableReader;

    /** Production composition; the factory remains stateless and request data stays in Request. */
    public CampaignResponseRouteAdapter(
            CampaignResponseCapabilityGate gate,
            CampaignJdbcDurableRunResponseBridgeFactory factory) {
        this(gate, Objects.requireNonNull(factory, "RUN_RESULT_BRIDGE_FACTORY_REQUIRED")::read);
    }

    /** Deterministic composition seam for transport and persistence tests. */
    public CampaignResponseRouteAdapter(CampaignResponseCapabilityGate gate,
                                        DurableResponseReader durableReader) {
        this.gate = Objects.requireNonNull(gate, "CAMPAIGN_RESPONSE_GATE_REQUIRED");
        this.durableReader = Objects.requireNonNull(durableReader, "RUN_RESULT_DURABLE_READER_REQUIRED");
    }

    /** Selects exactly one response path. Reader failures intentionally propagate without fallback. */
    public Outcome route(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_ROUTE_REQUEST_REQUIRED");
        CampaignResponseCapabilityGate.Decision decision = decide(request.capability());
        return switch (decision) {
            case LEGACY_PATH -> Outcome.legacyPath();
            case CLIENT_UPGRADE_REQUIRED -> Outcome.clientUpgradeRequired();
            case DURABLE_V2 -> durable(request.durable());
        };
    }

    /** Exposes the pure protocol decision so a transport can resolve a durable handle lazily. */
    public CampaignResponseCapabilityGate.Decision decide(
            CampaignResponseCapabilityGate.Request capability) {
        return gate.decide(Objects.requireNonNull(capability,
                "CAMPAIGN_RESPONSE_CAPABILITY_REQUEST_REQUIRED"));
    }

    private Outcome durable(Optional<CampaignJdbcDurableRunResponseBridgeFactory.Request> request) {
        CampaignJdbcDurableRunResponseBridgeFactory.Request durableRequest = request.orElseThrow(
                () -> new IllegalArgumentException("CAMPAIGN_DURABLE_REQUEST_REQUIRED"));
        CampaignJdbcDurableRunResponseBridge.Outcome response = durableReader.read(durableRequest);
        Objects.requireNonNull(response, "RUN_RESULT_DURABLE_OUTCOME_REQUIRED");
        return switch (response.status()) {
            case NO_BINDING -> Outcome.noBinding();
            case BOUND_RESPONSE -> Outcome.durableResponse(response.response().orElseThrow(
                    () -> new IllegalStateException("RUN_RESULT_BOUND_RESPONSE_REQUIRED")));
        };
    }

    @FunctionalInterface
    public interface DurableResponseReader {
        CampaignJdbcDurableRunResponseBridge.Outcome read(
                CampaignJdbcDurableRunResponseBridgeFactory.Request request);
    }

    /** Gate input plus an optional, already authorized server-owned durable request. */
    public record Request(
            CampaignResponseCapabilityGate.Request capability,
            Optional<CampaignJdbcDurableRunResponseBridgeFactory.Request> durable) {
        public Request {
            Objects.requireNonNull(capability, "CAMPAIGN_RESPONSE_CAPABILITY_REQUEST_REQUIRED");
            durable = durable == null ? Optional.empty() : durable;
        }

        public Request(CampaignResponseCapabilityGate.Request capability) {
            this(capability, Optional.empty());
        }
    }

    /**
     * A route result deliberately carries a response only after a durable binding was read.
     * Legacy callers can use their own graph result when the status is LEGACY_PATH.
     */
    public record Outcome(Status status, Optional<AgentRunResult> response) {
        public Outcome {
            Objects.requireNonNull(status, "CAMPAIGN_RESPONSE_ROUTE_STATUS_REQUIRED");
            response = response == null ? Optional.empty() : response;
            if (status == Status.DURABLE_RESPONSE && response.isEmpty())
                throw new IllegalArgumentException("CAMPAIGN_DURABLE_RESPONSE_REQUIRED");
            if (status != Status.DURABLE_RESPONSE && response.isPresent())
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_FALLBACK_FORBIDDEN");
        }

        static Outcome legacyPath() { return new Outcome(Status.LEGACY_PATH, Optional.empty()); }

        static Outcome clientUpgradeRequired() {
            return new Outcome(Status.CLIENT_UPGRADE_REQUIRED, Optional.empty());
        }

        static Outcome noBinding() { return new Outcome(Status.NO_BINDING, Optional.empty()); }

        static Outcome durableResponse(AgentRunResult response) {
            return new Outcome(Status.DURABLE_RESPONSE, Optional.of(response));
        }

        public enum Status {
            LEGACY_PATH,
            CLIENT_UPGRADE_REQUIRED,
            NO_BINDING,
            DURABLE_RESPONSE
        }
    }
}
