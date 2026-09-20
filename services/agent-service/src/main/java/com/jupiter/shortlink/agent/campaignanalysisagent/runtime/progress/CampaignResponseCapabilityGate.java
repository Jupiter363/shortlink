package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import java.util.Objects;
import java.util.Set;

/** Pure protocol gate for selecting the campaign response path. */
public final class CampaignResponseCapabilityGate {
    public static final String PROTOCOL_V2 = "campaign-response/v2";
    public static final String CAPABILITY_V2 = PROTOCOL_V2;

    public Decision decide(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_REQUEST_REQUIRED");
        boolean clientSupportsV2 = request.clientCapabilities().contains(CAPABILITY_V2);
        boolean serverRunIsV2 = PROTOCOL_V2.equals(request.serverRunProtocol());

        if (request.runKind() == RunKind.EXISTING && serverRunIsV2)
            return clientSupportsV2 ? Decision.DURABLE_V2 : Decision.CLIENT_UPGRADE_REQUIRED;
        if (request.runKind() == RunKind.EXISTING)
            return Decision.LEGACY_PATH;
        return request.serverEnabled() && clientSupportsV2
                ? Decision.DURABLE_V2 : Decision.LEGACY_PATH;
    }

    public enum RunKind { NEW, EXISTING }

    public enum Decision { LEGACY_PATH, DURABLE_V2, CLIENT_UPGRADE_REQUIRED }

    public record Request(RunKind runKind, String serverRunProtocol,
                          Set<String> clientCapabilities, boolean serverEnabled) {
        public Request {
            Objects.requireNonNull(runKind, "CAMPAIGN_RESPONSE_RUN_KIND_REQUIRED");
            if (serverRunProtocol == null)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_REQUIRED");
            if (!serverRunProtocol.isEmpty() && serverRunProtocol.isBlank())
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_INVALID");
            if (!serverRunProtocol.isEmpty() && !PROTOCOL_V2.equals(serverRunProtocol))
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_PROTOCOL_UNKNOWN");
            if (clientCapabilities == null)
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITIES_REQUIRED");
            if (clientCapabilities.stream().anyMatch(value -> value == null || value.isBlank()))
                throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITY_INVALID");
            clientCapabilities = Set.copyOf(clientCapabilities);
        }
    }
}
