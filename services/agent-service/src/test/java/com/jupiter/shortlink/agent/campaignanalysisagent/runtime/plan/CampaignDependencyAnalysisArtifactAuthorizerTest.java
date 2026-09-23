package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.InvocationSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.local.LocalCalculationRegistry.OutputBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CampaignDependencyAnalysisArtifactAuthorizerTest {
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant EXPIRY = CLOCK.instant().plusSeconds(3600);

    @Test
    void pairedLocalOutputsNeedPublishedBindingAndExactCurrentProducer() throws Exception {
        var fixture = new Fixture();
        var selected = fixture.localPair();
        var evidence = fixture.artifacts.get("evidence");
        // LOCAL publication has no child.artifactId; both separately bound outputs are readable.
        assertThat(fixture.children.get(selected.childId()).artifactId()).isNull();
        assertThat(fixture.gate.mayRead(OWNER, selected)).isTrue();
        assertThat(fixture.gate.mayRead(OWNER, evidence)).isTrue();
        verify(fixture.runs, never()).localOutputs(any(), anyString(), any());

        // The owner still owns the group, but moving one source link changes its membership version.
        fixture.currentGroupVersion = "b".repeat(64);
        assertThat(fixture.gate.mayRead(OWNER, selected)).isFalse();
        assertThat(fixture.gate.mayRead(OWNER, evidence)).isFalse();
        fixture.currentGroupVersion = "a".repeat(64);

        fixture.published.remove("selectedEntities");
        assertThat(fixture.gate.mayRead(OWNER, selected)).isFalse();
        fixture.published.put("selectedEntities", selected.ref());
        var forged = new ArtifactMetadata(selected.ref(), OWNER, selected.runId(), selected.planId(),
                selected.revision(), selected.actionId(), selected.childId(), selected.executorVersion(),
                selected.qualityJson(), "{\"forged\":true}");
        assertThat(fixture.gate.mayRead(OWNER, forged)).isFalse();
        assertThat(fixture.gate.mayRead(new Caller("1001", "bob", 7), selected)).isFalse();

        // An identically typed output attached to the dimension action cannot impersonate selection.
        fixture.actions.put(selected.actionId(), fixture.action(selected.actionId(),
                fixture.step(CampaignDependencyAnalysisPlanFactory.DIMENSION)));
        assertThat(fixture.gate.mayRead(OWNER, selected)).isFalse();
    }

    @Test
    void collectionPagesAndFinalOutputRemainLiveAuthorizedAcrossRecovery() throws Exception {
        var fixture = new Fixture();
        var intermediate = fixture.scope(false);
        var complete = fixture.scope(true);
        fixture.collectionState = CampaignScopeStore.State.COLLECTING;
        assertThat(fixture.gate.mayRead(OWNER, intermediate)).isTrue();
        fixture.collectionState = CampaignScopeStore.State.PUBLISHED;
        assertThat(fixture.gate.mayRead(OWNER, complete)).isTrue();

        fixture.storedVersion = "invalid-version";
        assertThat(fixture.gate.mayRead(OWNER, intermediate)).isFalse();
        fixture.storedVersion = null;
        assertThat(fixture.gate.mayRead(OWNER, complete)).isFalse();
        fixture.storedVersion = "a".repeat(64);
        fixture.collectionState = CampaignScopeStore.State.INVALID;
        assertThat(fixture.gate.mayRead(OWNER, complete)).isFalse();
        fixture.collectionState = CampaignScopeStore.State.PUBLISHED;

        fixture.authorized = false;
        assertThat(fixture.gate.mayRead(OWNER, complete)).isFalse();
        fixture.authorized = true;
        fixture.afterAuthority = () -> fixture.current = new RunRecord(fixture.definition,
                RunStatus.CANCELLED, 2, "cancelled");
        assertThat(fixture.gate.mayRead(OWNER, intermediate)).isFalse();
        fixture.current = new RunRecord(fixture.definition, RunStatus.ACTIVE, 1, "writer");
        fixture.afterAuthority = () -> {
            RunDefinition revised = new RunDefinition(OWNER, fixture.definition.sessionId(),
                    fixture.definition.runId(), fixture.definition.planId(), 2, fixture.definition.definitionJson());
            fixture.current = new RunRecord(revised, RunStatus.ACTIVE, 2, "new-writer");
        };
        assertThat(fixture.gate.mayRead(OWNER, complete)).isFalse();
    }

    private static final class Fixture {
        final CampaignRunStore runs = mock(CampaignRunStore.class);
        final CampaignScopeStore scopes = mock(CampaignScopeStore.class);
        final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        final CampaignDependencyAnalysisArtifactAuthorizer gate;
        final RunDefinition definition;
        final FrozenCampaignRun frozen;
        final Map<String, ArtifactMetadata> artifacts = new HashMap<>();
        final Map<String, ChildRecord> children = new HashMap<>();
        final Map<String, ActionSpec> actions = new HashMap<>();
        final Map<String, ArtifactRef> published = new HashMap<>();
        RunRecord current;
        boolean authorized = true;
        String storedVersion = "a".repeat(64);
        String currentGroupVersion = "a".repeat(64);
        CampaignScopeStore.State collectionState = CampaignScopeStore.State.PUBLISHED;
        Runnable afterAuthority = () -> {};

        Fixture() throws Exception {
            var plans = new CampaignDependencyAnalysisPlanFactory(
                    new ClassPathResource("campaign-skills").getFile().toPath(), CLOCK);
            var prepared = plans.prepare(OWNER, "session", "request", new CampaignDependencyAnalysisPlanFactory.Request(
                    "group-a", "2026-09-01", "2026-09-01", "2026-09-02", "2026-09-02",
                    "PV", List.of("province", "device"), List.of()), EXPIRY);
            definition = prepared.definition(); frozen = prepared.frozen();
            current = new RunRecord(definition, RunStatus.ACTIVE, 1, "writer");
            gate = new CampaignDependencyAnalysisArtifactAuthorizer(runs, scopes,
                    new CampaignDependencyAnalysisAuthorizer(authority, plans));
            var scopeDefinition = FrozenScopeCollection.resolve(definition)
                    .get(CampaignDependencyAnalysisPlanFactory.COLLECT).definition();
            when(scopes.load(any(), anyString())).thenAnswer(call -> new CampaignScopeStore.Collection(
                    scopeDefinition, collectionState, storedVersion, null, 1, 1,
                    "scope-artifact-" + CampaignRunStore.sha256(scopeDefinition.collectionId()), null));
            when(runs.loadRun(any(), anyString())).thenAnswer(call -> Optional.of(current));
            when(runs.child(any(), anyString())).thenAnswer(call -> Optional.ofNullable(children.get(call.getArgument(1))));
            when(runs.inspectAction(any(), anyString())).thenAnswer(call -> Optional.ofNullable(actions.get(call.getArgument(1))));
            when(runs.inspectArtifact(any(), anyString(), any())).thenAnswer(call -> {
                ArtifactMetadata stored = artifacts.get(call.getArgument(1));
                ArtifactAuthorizer inner = call.getArgument(2);
                if (stored == null || !inner.mayRead(call.getArgument(0), stored)) throw new SecurityException("denied");
                return stored;
            });
            when(runs.isLocalOutputBound(any(), anyString(), any())).thenAnswer(call ->
                    published.containsValue(call.getArgument(2)));
            when(authority.verifyCurrentPrincipal(any())).thenAnswer(call -> {
                if (!authorized) throw new SecurityException("revoked");
                return call.getArgument(0);
            });
            when(authority.resolveGroupMembersPage(any(), anyString(), isNull(), isNull())).thenAnswer(call -> {
                afterAuthority.run();
                AgentPrincipal owner = call.getArgument(0);
                return new GroupMembersPage(GroupMembersPage.SCHEMA, owner.tenantId(), owner.username(), owner.authVersion(),
                        call.getArgument(1), currentGroupVersion, null, List.of(1L), null);
            });
        }

        ArtifactMetadata localPair() {
            String actionId = "selection-action", childId = "selection-child";
            var selected = metadata("selected", DeclineSelectionPublisher.SELECTED_TYPE,
                    DeclineSelectionPublisher.SELECTED_SCHEMA, "scope-frozen", "periods", actionId, childId, "2", "{}");
            var evidence = metadata("evidence", DeclineSelectionPublisher.EVIDENCE_TYPE,
                    DeclineSelectionPublisher.EVIDENCE_SCHEMA, "scope-frozen", "periods", actionId, childId, "2", "{}");
            Map<String, OutputBinding> outputs = Map.of("selectedEntities", binding(selected), "selectionEvidence", binding(evidence));
            var head = metadata("selection-head", DeclineSelectionPage.CHAIN_TYPE, DeclineSelectionPage.CHAIN_SCHEMA,
                    "scope-frozen", "periods", "selection-page-action", "selection-page-child", "2", "{}");
            var invocation = new InvocationSpec("decline-selection-final", "1", "a".repeat(64), "{}",
                    Map.of("scope", scope(true), "head", head), outputs, EXPIRY);
            var child = new ChildSpec(childId, actionId, ChildMode.LOCAL, "local-request", null, invocation);
            children.put(childId, ready(child, null));
            actions.put(actionId, action(actionId, step(CampaignDependencyAnalysisPlanFactory.SELECT)));
            published.put("selectedEntities", selected.ref()); published.put("selectionEvidence", evidence.ref());
            return selected;
        }

        ArtifactMetadata scope(boolean terminal) {
            var bound = FrozenScopeCollection.resolve(definition).get(CampaignDependencyAnalysisPlanFactory.COLLECT);
            String collection = bound.definition().collectionId();
            String id = terminal ? "scope-artifact-" + CampaignRunStore.sha256(collection)
                    : "scope-page-ref-" + CampaignRunStore.sha256(collection + ":0");
            String actionId = bound.definition().action().actionId();
            String childId = terminal ? "scope-final-child" : "scope-page-child";
            var artifact = metadata(id, terminal ? "ScopeArtifact" : "ScopePageRef",
                    terminal ? "campaign-scope/v1" : "campaign-scope-page-ref/v1",
                    terminal ? "scope-frozen" : "scope-collection-" + CampaignRunStore.sha256(collection),
                    "scope-enumeration", actionId, childId, "1", "{\"collectionId\":\"" + collection + "\"}");
            var child = new ChildSpec(childId, actionId, ChildMode.SYNC, "scope-request-" + terminal,
                    new WireRequest("POST", AgentAuthorityClient.GROUP_MEMBERS_PATH, "{\"gid\":\"group-a\"}"));
            children.put(childId, ready(child, id)); actions.put(actionId, bound.definition().action());
            return artifact;
        }

        ArtifactMetadata metadata(String id, String type, String schema, String scope, String periods,
                String actionId, String childId, String version, String provenance) {
            var metadata = new ArtifactMetadata(new ArtifactRef(id, type, schema, "a".repeat(64), scope, periods, EXPIRY),
                    OWNER, definition.runId(), definition.planId(), 1, actionId, childId, version, "{}", provenance);
            artifacts.put(id, metadata); return metadata;
        }

        PlanSpec.Step step(String id) { return frozen.plan().steps().stream().filter(value -> id.equals(value.stepId())).findFirst().orElseThrow(); }
        ActionSpec action(String id, PlanSpec.Step step) {
            return new ActionSpec(id, step.stepId(), step.executor().kind().name(), step.executor().name(),
                    step.executor().version(), FrozenCampaignRun.encode(step));
        }
        static ChildRecord ready(ChildSpec child, String artifactId) {
            return new ChildRecord(child, ChildState.READY, null, artifactId, "attempt", 1, DispatchPurpose.FRESH, false, null);
        }
        static OutputBinding binding(ArtifactMetadata metadata) {
            ArtifactRef ref = metadata.ref();
            return new OutputBinding(ref.artifactId(), ref.type(), ref.schemaVersion(), ref.scopeRef(), ref.periodsRef());
        }
    }
}
