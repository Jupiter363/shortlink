package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignResponseCapabilityGateTest {
    private final CampaignResponseCapabilityGate gate = new CampaignResponseCapabilityGate();

    @Test
    void newRunUsesDurableOnlyWhenEnabledAndClientDeclaresV2() {
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                CampaignResponseCapabilityGate.CAPABILITY_V2))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.DURABLE_V2);
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.NEW, "", false,
                CampaignResponseCapabilityGate.CAPABILITY_V2))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.LEGACY_PATH);
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.NEW, "", true))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.LEGACY_PATH);
    }

    @Test
    void existingV2RunRequiresClientUpgradeAndCannotFallback() {
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, false))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.CLIENT_UPGRADE_REQUIRED);
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, true,
                CampaignResponseCapabilityGate.CAPABILITY_V2))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.DURABLE_V2);
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, false,
                CampaignResponseCapabilityGate.CAPABILITY_V2, "future-capability"))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.DURABLE_V2);
    }

    @Test
    void existingLegacyRunDoesNotUpgradeMerelyBecauseClientDeclaresV2() {
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.EXISTING, "", true,
                CampaignResponseCapabilityGate.CAPABILITY_V2))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.LEGACY_PATH);
    }

    @Test
    void unknownCapabilitiesAreNotV2AndInputSetIsCopied() {
        Set<String> capabilities = new HashSet<>(Set.of("future-capability"));
        CampaignResponseCapabilityGate.Request request = new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, "", capabilities, true);
        capabilities.add(CampaignResponseCapabilityGate.CAPABILITY_V2);
        assertThat(gate.decide(request)).isEqualTo(CampaignResponseCapabilityGate.Decision.LEGACY_PATH);
        assertThatThrownBy(() -> request.clientCapabilities().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(decide(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                "CAMPAIGN-RESPONSE/V2"))
                .isEqualTo(CampaignResponseCapabilityGate.Decision.LEGACY_PATH);
    }

    @Test
    void rejectsInvalidRequestsAndUnknownRunProtocols() {
        assertThatThrownBy(() -> gate.decide(null))
                .hasMessage("CAMPAIGN_RESPONSE_REQUEST_REQUIRED");
        assertThatThrownBy(() -> new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, null, Set.of(), true))
                .hasMessage("CAMPAIGN_RESPONSE_PROTOCOL_REQUIRED");
        assertThatThrownBy(() -> new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, "campaign-response/v3", Set.of(), true))
                .hasMessage("CAMPAIGN_RESPONSE_PROTOCOL_UNKNOWN");
        assertThatThrownBy(() -> new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, " ", Set.of(), true))
                .hasMessage("CAMPAIGN_RESPONSE_PROTOCOL_INVALID");
        assertThatThrownBy(() -> new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, "", null, true))
                .hasMessage("CAMPAIGN_RESPONSE_CAPABILITIES_REQUIRED");
        assertThatThrownBy(() -> new CampaignResponseCapabilityGate.Request(
                CampaignResponseCapabilityGate.RunKind.NEW, "", Set.of(" "), true))
                .hasMessage("CAMPAIGN_RESPONSE_CAPABILITY_INVALID");
    }

    private CampaignResponseCapabilityGate.Decision decide(
            CampaignResponseCapabilityGate.RunKind kind, String protocol,
            boolean enabled, String... capabilities) {
        return gate.decide(new CampaignResponseCapabilityGate.Request(
                kind, protocol, Set.of(capabilities), enabled));
    }
}
