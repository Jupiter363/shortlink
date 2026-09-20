package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignResponseRouteAdapterTest {
    private static final AgentRunResult BASE = new AgentRunResult(
            "session-1", "trace-1", "legacy graph", List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());
    private static final CampaignResponseCapabilityGate GATE = new CampaignResponseCapabilityGate();
    private static final Caller CALLER = new Caller("tenant-1", "subject-1", 3);

    @Test
    void legacyPathDoesNotInvokeDurableReader() {
        AtomicInteger calls = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            calls.incrementAndGet();
            throw new AssertionError("legacy route must not read durable state");
        });

        CampaignResponseRouteAdapter.Outcome outcome = route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING, "", true),
                Optional.of(durableRequest())));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.LEGACY_PATH);
        assertThat(outcome.response()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void existingV2WithoutClientCapabilityRequiresUpgradeAndNeverFallsBack() {
        AtomicInteger calls = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            calls.incrementAndGet();
            throw new AssertionError("upgrade response must not read durable state");
        });

        CampaignResponseRouteAdapter.Outcome outcome = route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING,
                        CampaignResponseCapabilityGate.PROTOCOL_V2, true),
                Optional.of(durableRequest())));

        assertThat(outcome.status()).isEqualTo(
                CampaignResponseRouteAdapter.Outcome.Status.CLIENT_UPGRADE_REQUIRED);
        assertThat(outcome.response()).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void durableV2RequiresServerOwnedRequest() {
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE,
                request -> { throw new AssertionError("request should be rejected first"); });

        assertThatThrownBy(() -> route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_DURABLE_REQUEST_REQUIRED");
    }

    @Test
    void noBindingIsExplicitAndDoesNotReturnGraphBase() {
        AtomicInteger calls = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            calls.incrementAndGet();
            return new CampaignJdbcDurableRunResponseBridge.Outcome(
                    CampaignJdbcDurableRunResponseBridge.Outcome.Status.NO_BINDING, Optional.empty());
        });

        CampaignResponseRouteAdapter.Outcome outcome = route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2),
                Optional.of(durableRequest())));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.NO_BINDING);
        assertThat(outcome.response()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void durableBindingReturnsOnlySanitizedDurableResponse() {
        AtomicInteger calls = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            calls.incrementAndGet();
            return new CampaignJdbcDurableRunResponseBridge.Outcome(
                    CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE,
                    Optional.of(BASE));
        });

        CampaignResponseRouteAdapter.Outcome outcome = route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2),
                Optional.of(durableRequest())));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.DURABLE_RESPONSE);
        assertThat(outcome.response()).containsSame(BASE);
        assertThat(calls).hasValue(1);
    }

    @Test
    void durableReaderFailureDoesNotRetryLegacyPath() {
        AtomicInteger calls = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            calls.incrementAndGet();
            throw new SecurityException("RUN_RESULT_REPORT_ACCESS_REQUIRED");
        });

        assertThatThrownBy(() -> route.route(new CampaignResponseRouteAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2),
                Optional.of(durableRequest()))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("RUN_RESULT_REPORT_ACCESS_REQUIRED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void outcomeRejectsResponseOnNonDurableStatuses() {
        assertThatThrownBy(() -> new CampaignResponseRouteAdapter.Outcome(
                CampaignResponseRouteAdapter.Outcome.Status.NO_BINDING, Optional.of(BASE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_RESPONSE_FALLBACK_FORBIDDEN");
    }

    private static CampaignResponseCapabilityGate.Request capability(
            CampaignResponseCapabilityGate.RunKind kind, String protocol, boolean enabled,
            String... capabilities) {
        return new CampaignResponseCapabilityGate.Request(kind, protocol, Set.of(capabilities), enabled);
    }

    private static CampaignJdbcDurableRunResponseBridgeFactory.Request durableRequest() {
        return new CampaignJdbcDurableRunResponseBridgeFactory.Request(
                BASE, CALLER, "run-1", 2, "plan-1");
    }
}
