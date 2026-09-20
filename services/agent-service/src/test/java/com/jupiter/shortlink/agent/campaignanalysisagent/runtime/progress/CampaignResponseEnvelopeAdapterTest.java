package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignResponseEnvelopeAdapterTest {
    private static final Caller CALLER = new Caller("tenant-1", "subject-1", 4);
    private static final AgentRunResult BASE = new AgentRunResult(
            "session-1", "trace-1", "legacy-answer", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private static final CampaignResponseCapabilityGate GATE = new CampaignResponseCapabilityGate();

    @Test
    void legacyCarriesOriginalBaseResponse() {
        CampaignResponseEnvelopeAdapter.Outcome outcome = envelope((request) -> {
            throw new AssertionError("legacy path must not invoke durable reader");
        }).resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING, "", true), BASE,
                Optional.of(reference()), Optional.empty()));

        assertThat(outcome.status()).isEqualTo(CampaignResponseEnvelopeAdapter.Status.LEGACY_PATH);
        assertThat(outcome.wireCode()).isEqualTo(CampaignResponseEnvelopeAdapter.WireCode.LEGACY_PATH);
        assertThat(outcome.response()).contains(BASE);
    }

    @Test
    void upgradeAndNoBindingCarryNoResponse() {
        CampaignResponseEnvelopeAdapter upgradeAdapter = envelope(request -> {
            throw new AssertionError("upgrade path must not invoke durable reader");
        });
        CampaignResponseEnvelopeAdapter.Outcome upgrade = upgradeAdapter.resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING,
                        CampaignResponseCapabilityGate.PROTOCOL_V2, true), BASE,
                Optional.of(reference()), Optional.empty()));
        assertThat(upgrade.status()).isEqualTo(CampaignResponseEnvelopeAdapter.Status.CLIENT_UPGRADE_REQUIRED);
        assertThat(upgrade.wireCode()).isEqualTo(CampaignResponseEnvelopeAdapter.WireCode.CLIENT_UPGRADE_REQUIRED);
        assertThat(upgrade.response()).isEmpty();

        CampaignResponseEnvelopeAdapter.Outcome noBinding = envelope(request ->
                new CampaignJdbcDurableRunResponseBridge.Outcome(
                        CampaignJdbcDurableRunResponseBridge.Outcome.Status.NO_BINDING, Optional.empty()))
                .resolve(durableRequest());
        assertThat(noBinding.status()).isEqualTo(CampaignResponseEnvelopeAdapter.Status.NO_BINDING);
        assertThat(noBinding.wireCode()).isEqualTo(CampaignResponseEnvelopeAdapter.WireCode.NO_BINDING);
        assertThat(noBinding.response()).isEmpty();
    }

    @Test
    void durableResponseCarriesOnlySelectedDurableResponse() {
        CampaignResponseEnvelopeAdapter.Outcome outcome = envelope(request ->
                new CampaignJdbcDurableRunResponseBridge.Outcome(
                        CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE, Optional.of(BASE)))
                .resolve(durableRequest());

        assertThat(outcome.status()).isEqualTo(CampaignResponseEnvelopeAdapter.Status.DURABLE_RESPONSE);
        assertThat(outcome.wireCode()).isEqualTo(CampaignResponseEnvelopeAdapter.WireCode.DURABLE_RESPONSE);
        assertThat(outcome.response()).contains(BASE);
    }

    @Test
    void readerExceptionsPropagateWithoutLegacyFallback() {
        CampaignResponseEnvelopeAdapter adapter = envelope(request -> {
            throw new SecurityException("REPORT_ACCESS_DENIED");
        });
        assertThatThrownBy(() -> adapter.resolve(durableRequest()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_DENIED");
    }

    @Test
    void outcomesKeepStableCodesAndRequestsDoNotShareBaseState() {
        assertThat(CampaignResponseEnvelopeAdapter.WireCode.values())
                .extracting(Enum::name)
                .containsExactly("LEGACY_PATH", "CLIENT_UPGRADE_REQUIRED", "NO_BINDING", "DURABLE_RESPONSE");
        AgentRunResult other = new AgentRunResult(
                "session-2", "trace-2", "other", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        CampaignResponseEnvelopeAdapter.Outcome first = envelope(request -> {
            throw new AssertionError();
        }).resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", false), BASE,
                Optional.empty(), Optional.empty()));
        CampaignResponseEnvelopeAdapter.Outcome second = envelope(request -> {
            throw new AssertionError();
        }).resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", false), other,
                Optional.empty(), Optional.empty()));
        assertThat(first.response()).contains(BASE);
        assertThat(second.response()).contains(other);
    }

    private static CampaignResponseEnvelopeAdapter envelope(
            CampaignResponseRouteAdapter.DurableResponseReader reader) {
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, reader);
        CampaignDurableResponseTransportAdapter transport = new CampaignDurableResponseTransportAdapter(
                route, request -> Optional.of(new CampaignRunHandle(CALLER, "session-1", "run-1", "plan-1", 1,
                        RunStatus.ACTIVE)));
        return new CampaignResponseEnvelopeAdapter(transport);
    }

    private static CampaignDurableResponseTransportAdapter.Request durableRequest() {
        return new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2), BASE,
                Optional.of(reference()), Optional.empty());
    }

    private static CampaignRunHandleResolver.Request reference() {
        return new CampaignRunHandleResolver.Request(CALLER, "session-1", "run-1", 1, "plan-1");
    }

    private static CampaignResponseCapabilityGate.Request capability(
            CampaignResponseCapabilityGate.RunKind kind, String protocol, boolean enabled,
            String... capabilities) {
        return new CampaignResponseCapabilityGate.Request(kind, protocol, Set.of(capabilities), enabled);
    }
}
