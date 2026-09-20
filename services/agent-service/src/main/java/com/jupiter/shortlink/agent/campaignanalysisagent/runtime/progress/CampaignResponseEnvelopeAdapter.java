package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

/** Adds one stable transport envelope around the transport-neutral durable response selector. */
public final class CampaignResponseEnvelopeAdapter {
    private final CampaignDurableResponseTransportAdapter transport;

    public CampaignResponseEnvelopeAdapter(CampaignDurableResponseTransportAdapter transport) {
        this.transport = Objects.requireNonNull(transport, "CAMPAIGN_RESPONSE_TRANSPORT_REQUIRED");
    }

    /** Selects one response contract; durable failures never receive the legacy base response. */
    public Outcome resolve(CampaignDurableResponseTransportAdapter.Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_ENVELOPE_REQUEST_REQUIRED");
        CampaignResponseRouteAdapter.Outcome selected = transport.resolve(request);
        Objects.requireNonNull(selected, "CAMPAIGN_RESPONSE_ROUTE_OUTCOME_REQUIRED");
        return switch (selected.status()) {
            case LEGACY_PATH -> Outcome.legacy(request.base());
            case CLIENT_UPGRADE_REQUIRED -> Outcome.empty(Status.CLIENT_UPGRADE_REQUIRED,
                    WireCode.CLIENT_UPGRADE_REQUIRED);
            case NO_BINDING -> Outcome.empty(Status.NO_BINDING, WireCode.NO_BINDING);
            case DURABLE_RESPONSE -> Outcome.durable(selected.response().orElseThrow(
                    () -> new IllegalStateException("CAMPAIGN_DURABLE_RESPONSE_REQUIRED")));
        };
    }

    /** Authority-aware overload; it never accepts caller-supplied server protocol facts. */
    public Outcome resolve(CampaignDurableResponseTransportAdapter.AuthorityRequest request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_ENVELOPE_REQUEST_REQUIRED");
        CampaignResponseRouteAdapter.Outcome selected = transport.resolve(request);
        Objects.requireNonNull(selected, "CAMPAIGN_RESPONSE_ROUTE_OUTCOME_REQUIRED");
        return switch (selected.status()) {
            case LEGACY_PATH -> Outcome.legacy(request.base());
            case CLIENT_UPGRADE_REQUIRED -> Outcome.empty(Status.CLIENT_UPGRADE_REQUIRED,
                    WireCode.CLIENT_UPGRADE_REQUIRED);
            case NO_BINDING -> Outcome.empty(Status.NO_BINDING, WireCode.NO_BINDING);
            case DURABLE_RESPONSE -> Outcome.durable(selected.response().orElseThrow(
                    () -> new IllegalStateException("CAMPAIGN_DURABLE_RESPONSE_REQUIRED")));
        };
    }

    /** Authority path using trusted metadata and an opaque principal-bound grant. */
    public Outcome resolve(CampaignDurableResponseTransportAdapter.GrantAuthorityRequest request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_ENVELOPE_GRANT_AUTHORITY_REQUEST_REQUIRED");
        CampaignResponseRouteAdapter.Outcome selected = transport.resolve(request);
        Objects.requireNonNull(selected, "CAMPAIGN_RESPONSE_ROUTE_OUTCOME_REQUIRED");
        return switch (selected.status()) {
            case LEGACY_PATH -> Outcome.legacy(request.base());
            case CLIENT_UPGRADE_REQUIRED -> Outcome.empty(Status.CLIENT_UPGRADE_REQUIRED,
                    WireCode.CLIENT_UPGRADE_REQUIRED);
            case NO_BINDING -> Outcome.empty(Status.NO_BINDING, WireCode.NO_BINDING);
            case DURABLE_RESPONSE -> Outcome.durable(selected.response().orElseThrow(
                    () -> new IllegalStateException("CAMPAIGN_DURABLE_RESPONSE_REQUIRED")));
        };
    }

    public enum Status {
        LEGACY_PATH,
        CLIENT_UPGRADE_REQUIRED,
        NO_BINDING,
        DURABLE_RESPONSE
    }

    public enum WireCode {
        LEGACY_PATH,
        CLIENT_UPGRADE_REQUIRED,
        NO_BINDING,
        DURABLE_RESPONSE
    }

    public record Outcome(Status status, WireCode wireCode, Optional<AgentRunResult> response) {
        public Outcome {
            Objects.requireNonNull(status, "CAMPAIGN_RESPONSE_STATUS_REQUIRED");
            Objects.requireNonNull(wireCode, "CAMPAIGN_RESPONSE_WIRE_CODE_REQUIRED");
            Objects.requireNonNull(response, "CAMPAIGN_RESPONSE_VALUE_REQUIRED");
            if (status.name().equals(wireCode.name()) == false)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_WIRE_CODE_MISMATCH");
            if ((status == Status.LEGACY_PATH || status == Status.DURABLE_RESPONSE) && response.isEmpty())
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_VALUE_REQUIRED");
            if ((status == Status.CLIENT_UPGRADE_REQUIRED || status == Status.NO_BINDING)
                    && response.isPresent())
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_FALLBACK_FORBIDDEN");
        }

        private static Outcome legacy(AgentRunResult response) {
            return new Outcome(Status.LEGACY_PATH, WireCode.LEGACY_PATH, Optional.of(
                    Objects.requireNonNull(response, "AGENT_BASE_RESULT_REQUIRED")));
        }

        private static Outcome durable(AgentRunResult response) {
            return new Outcome(Status.DURABLE_RESPONSE, WireCode.DURABLE_RESPONSE, Optional.of(
                    Objects.requireNonNull(response, "CAMPAIGN_DURABLE_RESPONSE_REQUIRED")));
        }

        private static Outcome empty(Status status, WireCode code) {
            return new Outcome(status, code, Optional.empty());
        }
    }
}
