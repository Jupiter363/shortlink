package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignTrustedResponseAuthorityProviderTest {
    private static final Caller CALLER = new Caller("tenant", "subject", 3);
    private static final CampaignRunHandle HANDLE = new CampaignRunHandle(
            CALLER, "session", "run", "plan", 2, RunStatus.ACTIVE);
    private static final CampaignResponseProtocolMetadataResolver.Request PROTOCOL =
            new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session",
                    Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                            "run", 2, "plan")));
    private static final CampaignResponseProtocolMetadata METADATA =
            new CampaignResponseProtocolMetadata(CampaignResponseCapabilityGate.RunKind.EXISTING,
                    CampaignResponseCapabilityGate.PROTOCOL_V2, true,
                    Optional.of(new CampaignResponseProtocolMetadata.Identity(
                            CALLER, "session", "run", "plan", 2)));
    private static final AgentRunResult BASE = new AgentRunResult(
            "session", "trace", "base", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    @Test
    void bindsExactTrustedIdentityAndKeepsGrantOpaque() {
        CampaignReportAccessGrant grant = grant(ReportLifecycleStore.Mode.HISTORY_VIEW);
        CampaignTrustedResponseAuthorityProvider.Bound bound = new CampaignTrustedResponseAuthorityProvider()
                .bind(new CampaignTrustedResponseAuthorityProvider.Request(
                        CALLER, PROTOCOL, METADATA, HANDLE, grant, ReportLifecycleStore.Mode.HISTORY_VIEW));

        assertThat(bound.handle()).isEqualTo(HANDLE);
        assertThat(bound.mode()).isEqualTo(ReportLifecycleStore.Mode.HISTORY_VIEW);
        CampaignDurableResponseTransportAdapter.GrantAuthorityRequest transport =
                bound.transportRequest(BASE, Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2));
        assertThat(transport.reportGrant()).containsSame(grant);
        assertThat(bound.getClass().getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("owner", "capability", "reportAccess", "grant");
    }

    @Test
    void rejectsCallerHandleMetadataAndModeMismatch() {
        CampaignReportAccessGrant grant = grant(ReportLifecycleStore.Mode.HISTORY_VIEW);
        CampaignTrustedResponseAuthorityProvider provider = new CampaignTrustedResponseAuthorityProvider();
        assertThatThrownBy(() -> provider.bind(new CampaignTrustedResponseAuthorityProvider.Request(
                new Caller("tenant", "other", 3), PROTOCOL, METADATA, HANDLE, grant,
                ReportLifecycleStore.Mode.HISTORY_VIEW))).hasMessage("CAMPAIGN_RESPONSE_CALLER_MISMATCH");
        CampaignRunHandle other = new CampaignRunHandle(CALLER, "session", "other", "plan", 2, RunStatus.ACTIVE);
        assertThatThrownBy(() -> provider.bind(new CampaignTrustedResponseAuthorityProvider.Request(
                CALLER, PROTOCOL, METADATA, other, grant, ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .hasMessage("CAMPAIGN_RESPONSE_HANDLE_BINDING_MISMATCH");
        assertThatThrownBy(() -> provider.bind(new CampaignTrustedResponseAuthorityProvider.Request(
                CALLER, PROTOCOL, METADATA, HANDLE, grant, ReportLifecycleStore.Mode.EXPORT)))
                .hasMessage("REPORT_ACCESS_MODE_MISMATCH");
    }

    @Test
    void rejectsMetadataDriftAndMissingExistingIdentity() {
        CampaignReportAccessGrant grant = grant(ReportLifecycleStore.Mode.HISTORY_VIEW);
        CampaignTrustedResponseAuthorityProvider provider = new CampaignTrustedResponseAuthorityProvider();
        CampaignResponseProtocolMetadata drift = new CampaignResponseProtocolMetadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, true,
                Optional.of(new CampaignResponseProtocolMetadata.Identity(
                        CALLER, "session", "run", "other-plan", 2)));
        assertThatThrownBy(() -> provider.bind(new CampaignTrustedResponseAuthorityProvider.Request(
                CALLER, PROTOCOL, drift, HANDLE, grant, ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .hasMessage("CAMPAIGN_RESPONSE_METADATA_IDENTITY_MISMATCH");
        CampaignResponseProtocolMetadata missing = new CampaignResponseProtocolMetadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, true, Optional.empty());
        assertThatThrownBy(() -> provider.bind(new CampaignTrustedResponseAuthorityProvider.Request(
                CALLER, PROTOCOL, missing, HANDLE, grant, ReportLifecycleStore.Mode.HISTORY_VIEW)))
                .hasMessage("CAMPAIGN_RESPONSE_EXISTING_IDENTITY_REQUIRED");
    }

    private static CampaignReportAccessGrant grant(ReportLifecycleStore.Mode mode) {
        return new CampaignReportAccessGrantResolver((caller, handle, requested) ->
                new CampaignReportAccessGrantResolver.Authorization("report-owner"))
                .resolve(new CampaignReportAccessGrantResolver.Request(CALLER, HANDLE, mode)).orElseThrow();
    }
}
