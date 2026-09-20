package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure protocol gate for selecting the campaign response path. */
public final class CampaignResponseCapabilityGate {
    public static final String PROTOCOL_V2 = "campaign-response/v2";
    public static final String CAPABILITY_V2 = PROTOCOL_V2;

    /**
     * Compatibility gate for the pre-authority typed seam.  New transport code must use the
     * metadata overload so run kind, protocol and server enablement come from a trusted provider.
     */
    @Deprecated
    public Decision decide(Request request) {
        Objects.requireNonNull(request, "CAMPAIGN_RESPONSE_REQUEST_REQUIRED");
        return decide(new CampaignResponseProtocolMetadata(request.runKind(), request.serverRunProtocol(),
                request.serverEnabled(), Optional.empty()), request.clientCapabilities());
    }

    /** Selects a path from server-owned metadata and an untrusted client capability advertisement. */
    public Decision decide(CampaignResponseProtocolMetadata metadata, Set<String> clientCapabilities) {
        Objects.requireNonNull(metadata, "CAMPAIGN_RESPONSE_METADATA_REQUIRED");
        Set<String> capabilities = validateCapabilities(clientCapabilities);
        boolean clientSupportsV2 = capabilities.contains(CAPABILITY_V2);
        boolean serverRunIsV2 = PROTOCOL_V2.equals(metadata.serverRunProtocol());

        if (metadata.runKind() == RunKind.EXISTING && serverRunIsV2)
            return clientSupportsV2 ? Decision.DURABLE_V2 : Decision.CLIENT_UPGRADE_REQUIRED;
        if (metadata.runKind() == RunKind.EXISTING)
            return Decision.LEGACY_PATH;
        return metadata.serverEnabled() && clientSupportsV2
                ? Decision.DURABLE_V2 : Decision.LEGACY_PATH;
    }

    private static Set<String> validateCapabilities(Set<String> capabilities) {
        if (capabilities == null)
            throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITIES_REQUIRED");
        if (capabilities.stream().anyMatch(value -> value == null || value.isBlank()))
            throw new IllegalArgumentException("CAMPAIGN_RESPONSE_CAPABILITY_INVALID");
        return Set.copyOf(capabilities);
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
            clientCapabilities = validateCapabilities(clientCapabilities);
        }
    }
}
