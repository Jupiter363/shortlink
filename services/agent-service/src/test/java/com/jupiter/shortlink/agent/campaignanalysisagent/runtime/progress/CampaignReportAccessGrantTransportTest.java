package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CampaignReportAccessGrantTransportTest {
    private static final Caller CALLER = new Caller("tenant", "subject", 3);
    private static final AgentRunResult BASE = new AgentRunResult(
            "session", "trace", "base", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private static final CampaignRunHandle HANDLE = new CampaignRunHandle(
            CALLER, "session", "run", "plan", 1, RunStatus.ACTIVE);
    private static final CampaignResponseProtocolMetadataResolver.Request PROTOCOL =
            new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session",
                    Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                            "run", 1, "plan")));

    @Test
    void authorityPathRequiresGrantAndDoesNotFallback() {
        AtomicInteger reads = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(
                new CampaignResponseCapabilityGate(), request -> {
                    reads.incrementAndGet();
                    throw new AssertionError("missing grant must fail before durable reader");
                });
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                route, request -> Optional.of(HANDLE), protocols(), grantResolver());

        assertThatThrownBy(() -> adapter.resolve(new CampaignDurableResponseTransportAdapter.GrantAuthorityRequest(
                PROTOCOL, BASE, Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2), Optional.empty())))
                .isInstanceOf(SecurityException.class)
                .hasMessage("REPORT_ACCESS_GRANT_REQUIRED");
        assertThat(reads).hasValue(0);
    }

    @Test
    void grantMustBindTheResolvedHandleBeforeDurableRead() {
        CampaignReportAccessGrant grant = grant();
        CampaignRunHandle other = new CampaignRunHandle(CALLER, "session", "other-run", "plan", 1,
                RunStatus.ACTIVE);
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(new CampaignResponseCapabilityGate(), request -> {
                    throw new AssertionError("mismatch must fail before durable reader");
                }), request -> Optional.of(other), protocols());

        assertThatThrownBy(() -> adapter.resolve(new CampaignDurableResponseTransportAdapter.GrantAuthorityRequest(
                PROTOCOL, BASE, Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2), Optional.of(grant))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
    }

    @Test
    void grantConvertsToFixedServerCapabilityOnlyInsideDurableReaderRequest() {
        AtomicReference<CampaignJdbcDurableRunResponseBridgeFactory.Request> captured = new AtomicReference<>();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(
                new CampaignResponseCapabilityGate(), request -> {
                    captured.set(request);
                    return new CampaignJdbcDurableRunResponseBridge.Outcome(
                            CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE, Optional.of(BASE));
                });
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                route, request -> Optional.of(HANDLE), protocols(), grantResolver());

        CampaignResponseRouteAdapter.Outcome outcome = adapter.resolve(
                new CampaignDurableResponseTransportAdapter.GrantAuthorityRequest(
                        PROTOCOL, BASE, Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2),
                        Optional.of(CampaignReportAccessGrant.issue(
                                CALLER, HANDLE, ReportLifecycleStore.Mode.HISTORY_VIEW, "untrusted-owner"))));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.DURABLE_RESPONSE);
        assertThat(captured.get().report()).isPresent();
        assertThat(captured.get().report().orElseThrow().owner()).isEqualTo("report-owner");
        assertThat(captured.get().report().orElseThrow().capability()).isEqualTo("campaign/report/v1");
        assertThat(captured.get().report().orElseThrow().mode())
                .isEqualTo(ReportLifecycleStore.Mode.HISTORY_VIEW);
    }

    private static CampaignReportAccessGrant grant() {
        return grantResolver()
                .resolve(new CampaignReportAccessGrantResolver.Request(
                        CALLER, HANDLE, ReportLifecycleStore.Mode.HISTORY_VIEW))
                .orElseThrow();
    }

    private static CampaignReportAccessGrantResolver grantResolver() {
        return new CampaignReportAccessGrantResolver((caller, handle, mode) ->
                new CampaignReportAccessGrantResolver.Authorization("report-owner"));
    }

    private static CampaignResponseProtocolMetadataResolver protocols() {
        return ignored -> Optional.of(new CampaignResponseProtocolMetadata(
                CampaignResponseCapabilityGate.RunKind.NEW, "", true, Optional.empty()));
    }
}
