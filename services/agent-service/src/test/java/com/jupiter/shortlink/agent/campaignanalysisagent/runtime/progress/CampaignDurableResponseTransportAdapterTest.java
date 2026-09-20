package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandle;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunHandleResolver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CampaignDurableResponseTransportAdapterTest {
    private static final Caller CALLER = new Caller("tenant-1", "subject-1", 4);
    private static final AgentRunResult BASE = new AgentRunResult(
            "session-1", "trace-1", "graph", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    private static final CampaignResponseCapabilityGate GATE = new CampaignResponseCapabilityGate();

    @Test
    void legacyAndUpgradePathsDoNotResolveServerHandle() {
        AtomicInteger calls = new AtomicInteger();
        CampaignDurableResponseTransportAdapter adapter = adapter(request -> {
            calls.incrementAndGet();
            throw new AssertionError("legacy/upgrade path must not resolve a durable handle");
        });

        CampaignResponseRouteAdapter.Outcome legacy = adapter.resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING, "", true), BASE,
                reference(1, "plan-1")));
        CampaignResponseRouteAdapter.Outcome upgrade = adapter.resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING,
                        CampaignResponseCapabilityGate.PROTOCOL_V2, true), BASE,
                reference(1, "plan-1")));

        assertThat(legacy.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.LEGACY_PATH);
        assertThat(upgrade.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.CLIENT_UPGRADE_REQUIRED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void durablePathRequiresRunReference() {
        CampaignDurableResponseTransportAdapter adapter = adapter(request -> {
            throw new AssertionError("missing reference must be rejected before resolver");
        });

        assertThatThrownBy(() -> adapter.resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2), BASE, Optional.empty(), Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_DURABLE_RUN_REFERENCE_REQUIRED");
    }

    @Test
    void missingOrMismatchedHandleFailsClosed() {
        CampaignDurableResponseTransportAdapter missing = adapter(request -> Optional.empty());
        CampaignDurableResponseTransportAdapter.Request transportRequest = durableRequest();
        assertThatThrownBy(() -> missing.resolve(transportRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_NOT_FOUND");

        CampaignRunHandle wrong = handle(new Caller("tenant-2", "subject-1", 4), "session-1", "run-1");
        CampaignDurableResponseTransportAdapter mismatched = adapter(resolverRequest -> Optional.of(wrong));
        assertThatThrownBy(() -> mismatched.resolve(transportRequest))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_BINDING_MISMATCH");
    }

    @Test
    void durablePathUsesExactResolvedIdentityAndReportAccess() {
        CampaignRunHandle handle = handle(CALLER, "session-1", "run-1");
        AtomicReference<CampaignJdbcDurableRunResponseBridgeFactory.Request> captured = new AtomicReference<>();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            captured.set(request);
            return new CampaignJdbcDurableRunResponseBridge.Outcome(
                    CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE, Optional.of(BASE));
        });
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(route,
                request -> Optional.of(handle));
        CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess report =
                new CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess(
                        "owner-1", "capability-1", ReportLifecycleStore.Mode.HISTORY_VIEW);

        CampaignResponseRouteAdapter.Outcome outcome = adapter.resolve(new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2), BASE,
                Optional.of(reference(3, "plan-1")), Optional.of(report)));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.DURABLE_RESPONSE);
        assertThat(outcome.response()).contains(BASE);
        CampaignJdbcDurableRunResponseBridgeFactory.Request resolved = captured.get();
        assertThat(resolved.caller()).isEqualTo(CALLER);
        assertThat(resolved.runId()).isEqualTo("run-1");
        assertThat(resolved.revision()).isEqualTo(3);
        assertThat(resolved.expectedPlanId()).isEqualTo("plan-1");
        assertThat(resolved.report()).contains(report);
    }

    @Test
    void invalidResolvedHandleAndResolverFailureDoNotFallback() {
        CampaignDurableResponseTransportAdapter invalidAdapter = adapter(
                request -> Optional.of(new CampaignRunHandle(CALLER, "session-1", "run-1", "plan-1", 0,
                        RunStatus.ACTIVE)));
        assertThatThrownBy(() -> invalidAdapter.resolve(durableRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_RUN_HANDLE_REVISION_INVALID");

        CampaignDurableResponseTransportAdapter failing = adapter(request -> {
            throw new SecurityException("CAMPAIGN_HANDLE_LOOKUP_FAILED");
        });
        assertThatThrownBy(() -> failing.resolve(durableRequest()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_HANDLE_LOOKUP_FAILED");
    }

    @Test
    void reportAccessCannotBeProvidedWithoutRunReference() {
        CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess report =
                new CampaignJdbcDurableRunResponseBridgeFactory.ReportAccess(
                        "owner-1", "capability-1", ReportLifecycleStore.Mode.EXPORT);
        assertThatThrownBy(() -> new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.EXISTING, "", true), BASE,
                Optional.empty(), Optional.of(report)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CAMPAIGN_REPORT_ACCESS_WITHOUT_RUN");
    }

    @Test
    void authorityPathRequiresMetadataAndNeverFallsBack() {
        AtomicInteger handles = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        CampaignResponseRouteAdapter route = new CampaignResponseRouteAdapter(GATE, request -> {
            reads.incrementAndGet();
            throw new AssertionError("metadata failure must not route a response");
        });
        CampaignDurableResponseTransportAdapter adapter =
                new CampaignDurableResponseTransportAdapter(route, request -> {
                    handles.incrementAndGet();
                    throw new AssertionError("metadata failure must not resolve a handle");
                });
        CampaignDurableResponseTransportAdapter.AuthorityRequest request = authorityRequest(
                new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session-1"), Set.of());

        assertThatThrownBy(() -> adapter.resolve(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMPAIGN_RESPONSE_PROTOCOL_RESOLVER_REQUIRED");
        assertThat(handles).hasValue(0);
        assertThat(reads).hasValue(0);

        CampaignDurableResponseTransportAdapter unavailable =
                new CampaignDurableResponseTransportAdapter(route, ignored -> {
                    handles.incrementAndGet();
                    throw new AssertionError("unavailable metadata must not resolve a handle");
                }, ignored -> Optional.empty());
        assertThatThrownBy(() -> unavailable.resolve(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMPAIGN_RESPONSE_PROTOCOL_METADATA_UNAVAILABLE");
        assertThat(handles).hasValue(0);
        assertThat(reads).hasValue(0);
    }

    @Test
    void authorityExistingMetadataRequiresAnExactIdentity() {
        AtomicInteger handles = new AtomicInteger();
        CampaignResponseProtocolMetadata metadata = new CampaignResponseProtocolMetadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING,
                CampaignResponseCapabilityGate.PROTOCOL_V2, true, Optional.empty());
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(GATE, request -> {
                    throw new AssertionError("identity validation must happen before routing");
                }),
                request -> {
                    handles.incrementAndGet();
                    throw new AssertionError("identity validation must happen before handle lookup");
                }, ignored -> Optional.of(metadata));

        assertThatThrownBy(() -> adapter.resolve(authorityRequest(
                new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session-1",
                        Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                                "run-1", 3, "plan-1"))), Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CAMPAIGN_RESPONSE_EXISTING_IDENTITY_REQUIRED");
        assertThat(handles).hasValue(0);
    }

    @Test
    void authorityDurablePathChecksMetadataAgainstTheResolvedHandle() {
        CampaignRunHandle handle = handle(CALLER, "session-1", "run-1");
        CampaignResponseProtocolMetadata matching = metadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING, CALLER, "session-1", "run-1", "plan-1", 3);
        AtomicReference<CampaignJdbcDurableRunResponseBridgeFactory.Request> captured = new AtomicReference<>();
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(GATE, request -> {
                    captured.set(request);
                    return new CampaignJdbcDurableRunResponseBridge.Outcome(
                            CampaignJdbcDurableRunResponseBridge.Outcome.Status.BOUND_RESPONSE, Optional.of(BASE));
                }),
                request -> Optional.of(handle),
                ignored -> Optional.of(matching));

        CampaignResponseRouteAdapter.Outcome outcome = adapter.resolve(authorityRequest(
                new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session-1",
                        Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                                "run-1", 3, "plan-1"))), Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2)));

        assertThat(outcome.status()).isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.DURABLE_RESPONSE);
        assertThat(outcome.response()).contains(BASE);
        assertThat(captured.get().caller()).isEqualTo(CALLER);
        assertThat(captured.get().runId()).isEqualTo("run-1");
        assertThat(captured.get().revision()).isEqualTo(3);
        assertThat(captured.get().expectedPlanId()).isEqualTo("plan-1");

        CampaignResponseProtocolMetadata mismatch = metadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING, CALLER, "session-1", "run-1", "plan-2", 3);
        AtomicInteger reads = new AtomicInteger();
        CampaignDurableResponseTransportAdapter rejected = new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(GATE, request -> {
                    reads.incrementAndGet();
                    throw new AssertionError("metadata mismatch must not read durable response");
                }),
                request -> Optional.of(handle),
                ignored -> Optional.of(mismatch));

        assertThatThrownBy(() -> rejected.resolve(authorityRequest(
                new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session-1",
                        Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                                "run-1", 3, "plan-1"))), Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2))))
                .isInstanceOf(SecurityException.class)
                .hasMessage("CAMPAIGN_RESPONSE_METADATA_IDENTITY_MISMATCH");
        assertThat(reads).hasValue(0);
    }

    @Test
    void authorityLegacyAndUpgradePathsDoNotResolveHandles() {
        CampaignResponseProtocolMetadata legacy = metadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING, CALLER, "session-1", "run-1", "plan-1", 3,
                "");
        CampaignResponseProtocolMetadata upgrade = metadata(
                CampaignResponseCapabilityGate.RunKind.EXISTING, CALLER, "session-1", "run-1", "plan-1", 3,
                CampaignResponseCapabilityGate.PROTOCOL_V2);
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger handleCalls = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        CampaignDurableResponseTransportAdapter adapter = new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(GATE, request -> {
                    reads.incrementAndGet();
                    throw new AssertionError("non-durable authority path must not read durable response");
                }),
                request -> {
                    handleCalls.incrementAndGet();
                    throw new AssertionError("non-durable authority path must not resolve a handle");
                }, ignored -> {
                    metadataCalls.incrementAndGet();
                    return Optional.of(metadataCalls.get() == 1 ? legacy : upgrade);
                });
        CampaignResponseProtocolMetadataResolver.Request protocol =
                new CampaignResponseProtocolMetadataResolver.Request(CALLER, "session-1",
                        Optional.of(new CampaignResponseProtocolMetadataResolver.RunReference(
                                "run-1", 3, "plan-1")));

        assertThat(adapter.resolve(authorityRequest(protocol,
                Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2))).status())
                .isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.LEGACY_PATH);
        assertThat(adapter.resolve(authorityRequest(protocol, Set.of())).status())
                .isEqualTo(CampaignResponseRouteAdapter.Outcome.Status.CLIENT_UPGRADE_REQUIRED);
        assertThat(metadataCalls).hasValue(2);
        assertThat(handleCalls).hasValue(0);
        assertThat(reads).hasValue(0);
    }

    private static CampaignDurableResponseTransportAdapter.AuthorityRequest authorityRequest(
            CampaignResponseProtocolMetadataResolver.Request protocol, Set<String> capabilities) {
        return new CampaignDurableResponseTransportAdapter.AuthorityRequest(protocol, BASE, capabilities);
    }

    private static CampaignResponseProtocolMetadata metadata(
            CampaignResponseCapabilityGate.RunKind kind, Caller caller, String sessionId,
            String runId, String planId, int revision) {
        return metadata(kind, caller, sessionId, runId, planId, revision,
                CampaignResponseCapabilityGate.PROTOCOL_V2);
    }

    private static CampaignResponseProtocolMetadata metadata(
            CampaignResponseCapabilityGate.RunKind kind, Caller caller, String sessionId,
            String runId, String planId, int revision, String protocol) {
        return new CampaignResponseProtocolMetadata(kind, protocol, true,
                Optional.of(new CampaignResponseProtocolMetadata.Identity(
                        caller, sessionId, runId, planId, revision)));
    }

    private static CampaignDurableResponseTransportAdapter adapter(
            CampaignRunHandleResolver resolver) {
        return new CampaignDurableResponseTransportAdapter(
                new CampaignResponseRouteAdapter(GATE, request ->
                        new CampaignJdbcDurableRunResponseBridge.Outcome(
                                CampaignJdbcDurableRunResponseBridge.Outcome.Status.NO_BINDING,
                                Optional.empty())), resolver);
    }

    private static CampaignDurableResponseTransportAdapter.Request durableRequest() {
        return new CampaignDurableResponseTransportAdapter.Request(
                capability(CampaignResponseCapabilityGate.RunKind.NEW, "", true,
                        CampaignResponseCapabilityGate.CAPABILITY_V2), BASE,
                reference(3, "plan-1"));
    }

    private static CampaignRunHandleResolver.Request reference(int revision, String planId) {
        return new CampaignRunHandleResolver.Request(CALLER, "session-1", "run-1", revision, planId);
    }

    private static CampaignRunHandle handle(Caller caller, String session, String run) {
        return new CampaignRunHandle(caller, session, run, "plan-1", 3, RunStatus.ACTIVE);
    }

    private static CampaignResponseCapabilityGate.Request capability(
            CampaignResponseCapabilityGate.RunKind kind, String protocol, boolean enabled,
            String... capabilities) {
        return new CampaignResponseCapabilityGate.Request(kind, protocol, Set.of(capabilities), enabled);
    }
}
