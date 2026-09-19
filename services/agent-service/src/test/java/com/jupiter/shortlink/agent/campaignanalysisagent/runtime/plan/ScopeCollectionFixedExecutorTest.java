package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignScopeCollector;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Native scope collection and typed consumption; no decline-v2 or other upstream wiring is implied. */
@Timeout(30)
class ScopeCollectionFixedExecutorTest {
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), false);
    private static final String RUN = "scope-plan-run", COLLECT = "collect-scope", CONSUME = "consume-scope";
    private static final PlanSpec.ExecutorRef READ_SCOPE = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "read_scope", "1");
    private static final TypeRef SCOPE = new TypeRef("ScopeArtifact", 1, Cardinality.ONE);

    @Test
    void nativeBoundedPassResumesOnlyLastMemberAndDeliversActualTypedScopeToDependentStep() throws Exception {
        var f = new ScopeFixture(false);
        Runtime first = f.runtime();
        var initial = first.graph().advance();
        assertEquals(1, initial.advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, f.step(COLLECT).status());
        assertEquals("SCOPE_COLLECTION_PROGRESS", f.step(COLLECT).reason());
        assertNotEquals(CampaignStepStore.StepStatus.WAITING, f.step(COLLECT).status());
        assertEquals(0, f.consumptions.get()); assertEquals(Collections.singletonList(null), f.cursors);
        var partial = first.scopes().load(f.token, f.definition().collectionId());
        assertEquals(CampaignScopeStore.State.COLLECTING, partial.state());
        assertEquals(500, partial.memberCount()); assertEquals(1, partial.pageCount()); assertEquals(500L, partial.nextCursor());
        assertNull(partial.artifactId());
        ChildRecord original = f.runs.children(f.token).get(0);
        assertEquals(ChildMode.SYNC, original.spec().mode()); assertEquals(ChildState.READY, original.state());
        assertEquals(1, original.attemptVersion());
        Artifact pageRef = f.runs.readArtifact(OWNER, original.artifactId(), f.artifactAuth());
        assertEquals("ScopePageRef", pageRef.metadata().ref().type());
        assertFalse(pageRef.payloadJson().contains("linkIds"));
        f.exited();

        Runtime reopened = f.runtime(); // Fresh adapter/stores + prepareRecovery, not a private test rearm.
        assertEquals(CampaignStepStore.StepStatus.READY, f.step(COLLECT).status());
        assertEquals(2, reopened.graph().advance().advancedSteps());
        assertEquals(Arrays.asList(null, 500L), f.cursors);
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, f.step(COLLECT).status());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, f.step(CONSUME).status());
        assertEquals(1, f.consumptions.get());
        String id = f.step(COLLECT).outputs().get("scopeArtifact");
        assertTrue(f.step(CONSUME).outputs().isEmpty(), "A read-only consumer cannot relabel an upstream artifact as its own output");
        Artifact completed = f.runs.readArtifact(OWNER, id, f.artifactAuth());
        assertEquals("ScopeArtifact", completed.metadata().ref().type());
        assertEquals("campaign-scope/v1", completed.metadata().ref().schemaVersion());
        assertEquals(completed.metadata().ref().scopeRef(), f.consumedScope.get());
        assertEquals(Instant.ofEpochMilli(EXPIRY), completed.metadata().ref().expiresAt());
        var manifest = JSON.readTree(completed.payloadJson());
        assertEquals(501, manifest.path("memberCount").asLong()); assertEquals(2, manifest.path("shardCount").asInt());
        assertEquals(VERSION, manifest.path("enumerationVersion").asText());
        assertFalse(manifest.has("linkIds"));
        ArtifactAuthorizer finalScopeOnly = (caller, metadata) -> id.equals(metadata.ref().artifactId())
                && f.artifactAuth().mayRead(caller, metadata);
        assertEquals(501, reopened.scopes().inspectPublished(OWNER, id, finalScopeOnly).memberCount());
        assertEquals(LongStream.rangeClosed(1, 500).boxed().toList(), reopened.scopes().shard(OWNER, id, 0, finalScopeOnly).linkIds());
        assertEquals(List.of(501L), reopened.scopes().shard(OWNER, id, 1, finalScopeOnly).linkIds());
        assertThrows(SecurityException.class, () -> f.runs.readArtifact(OWNER, original.artifactId(), finalScopeOnly),
                "Permission to the published scope does not grant direct access to an internal page artifact");
        assertEquals(FrozenQueryScope.memberHash(LongStream.rangeClosed(1, 501).boxed().toList()), manifest.path("memberHash").asText());
        assertEquals(original, f.runs.child(f.token, original.spec().childId()).orElseThrow());
        assertEquals(pageRef, f.runs.readArtifact(OWNER, original.artifactId(), f.artifactAuth()));
        assertEquals(2, f.runs.children(f.token).size());
        assertTrue(f.runs.children(f.token).stream().allMatch(child -> child.state() == ChildState.READY && child.attemptVersion() == 1));
        assertEquals(0, f.runtime().graph().advance().advancedSteps());
        assertEquals(2, f.cursors.size()); assertEquals(1, f.consumptions.get());
        f.exited();
    }

    @Test
    void revokedOrCorruptedContinuationCannotDispatchAndUnknownFirstPageIsNeverAutomaticallyReread() throws Exception {
        var revoked = new ScopeFixture(false);
        Runtime authorized = revoked.runtime(); authorized.graph().advance();
        revoked.allowed.set(false);
        assertFalse(authorized.adapter().authorized());
        assertThrows(SecurityException.class, authorized.adapter()::prepareRecovery);
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, revoked.step(COLLECT).status());
        assertEquals(1, revoked.cursors.size()); assertEquals(0, revoked.consumptions.get());
        assertNull(authorized.scopes().load(revoked.token, revoked.definition().collectionId()).artifactId());
        revoked.exited();

        var corrupt = new ScopeFixture(false);
        corrupt.runtime().graph().advance();
        assertEquals(1, corrupt.base.jdbc.update("UPDATE campaign_scope_page SET payload_json='{}' WHERE collection_id=? AND page_index=0",
                corrupt.definition().collectionId()));
        assertThrows(IllegalStateException.class, corrupt::runtime);
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, corrupt.step(COLLECT).status());
        assertEquals(1, corrupt.cursors.size()); assertEquals(0, corrupt.consumptions.get());
        assertEquals(1, corrupt.runs.children(corrupt.token).size());
        corrupt.exited();

        var unknown = new ScopeFixture(true);
        unknown.runtime().graph().advance();
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, unknown.step(COLLECT).status());
        ChildRecord first = unknown.runs.children(unknown.token).get(0);
        assertEquals(ChildState.UNRESOLVED, first.state()); assertEquals(1, first.attemptVersion());
        assertNull(first.artifactId()); assertEquals(1, unknown.cursors.size());
        assertEquals(0, unknown.runtime().graph().advance().advancedSteps());
        assertEquals(first, unknown.runs.child(unknown.token, first.spec().childId()).orElseThrow());
        assertEquals(1, unknown.cursors.size(), "No ownership version was received, so the first page cannot be retried automatically");
        assertEquals(0, unknown.consumptions.get());
        unknown.exited();

        var empty = new ScopeFixture(false, 0);
        Runtime emptyRuntime = empty.runtime();
        assertEquals(2, emptyRuntime.graph().advance().advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, empty.step(COLLECT).status());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, empty.step(CONSUME).status());
        String emptyId = empty.step(COLLECT).outputs().get("scopeArtifact");
        var summary = emptyRuntime.scopes().inspectPublished(OWNER, emptyId, empty.artifactAuth());
        assertEquals(0, summary.memberCount()); assertEquals(0, summary.shardCount());
        assertEquals(summary.scopeRef(), empty.consumedScope.get());
        assertEquals(1, empty.cursors.size()); assertEquals(1, empty.consumptions.get());
        assertThrows(IllegalArgumentException.class, () -> emptyRuntime.scopes().shard(OWNER, emptyId, 0, empty.artifactAuth()));
        empty.exited();
    }

    private record Runtime(ScopeCollectionFixedExecutor adapter, CampaignScopeStore scopes, NativePlanGraph graph) {}

    private static final class ScopeFixture {
        final Fixture base = new Fixture();
        final CampaignRunStore runs = base.runs;
        final CampaignStepStore steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicInteger consumptions = new AtomicInteger();
        final AtomicReference<String> consumedScope = new AtomicReference<>();
        final List<Long> cursors = new ArrayList<>();
        final boolean unknownFirst;
        final int memberCount;
        final RunToken token;

        ScopeFixture(boolean unknownFirst) { this(unknownFirst, 501); }
        ScopeFixture(boolean unknownFirst, int memberCount) {
            this.unknownFirst = unknownFirst; this.memberCount = memberCount;
            token = steps.acquireRun(runs.createRun(frozen().definition(OWNER, "scope-plan-session")));
        }
        CampaignStepStore.StepRecord step(String id) { return steps.step(token, id).orElseThrow(); }
        CampaignScopeStore.Definition definition() { return FrozenScopeCollection.resolve(token.definition()).get(COLLECT).definition(); }
        ArtifactAuthorizer artifactAuth() {
            return (caller, metadata) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(metadata.owner()) && RUN.equals(metadata.runId());
        }
        Runtime runtime() throws Exception {
            CampaignRunStore reopenedRuns = new JdbcCampaignRunStore(base.jdbc, base.transactions, CLOCK);
            CampaignStepStore reopenedSteps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
            CampaignScopeStore reopenedScopes = new JdbcCampaignScopeStore(base.jdbc, base.transactions, CLOCK, reopenedRuns);
            var collector = new CampaignScopeCollector(reopenedRuns, reopenedScopes, (principal, gid, after, version) -> {
                cursors.add(after);
                assertEquals(PRINCIPAL, principal); assertEquals("group-a", gid);
                assertEquals(after == null ? null : VERSION, version);
                var child = reopenedRuns.children(token).stream().filter(ChildRecord::callbackActive).findFirst().orElseThrow();
                assertEquals(ChildState.DISPATCHING, child.state()); assertEquals(ChildMode.SYNC, child.spec().mode());
                if (unknownFirst) throw new IllegalStateException("REMOTE_UNAVAILABLE");
                long start = after == null ? 1 : after + 1, end = Math.min(memberCount, start + 499);
                return new GroupMembersPage(GroupMembersPage.SCHEMA, OWNER.tenantId(), OWNER.subject(), OWNER.authVersion(), gid,
                        VERSION, after, LongStream.rangeClosed(start, end).boxed().toList(), end < memberCount ? end : null);
            }, 1);
            var adapter = new ScopeCollectionFixedExecutor(token, PRINCIPAL, reopenedRuns, reopenedSteps, reopenedScopes, collector,
                    (current, gid) -> allowed.get() && PRINCIPAL.equals(current) && "group-a".equals(gid), artifactAuth());
            adapter.prepareRecovery();
            var policy = new StepBindings.StepPolicy() {
                public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                    var scope = inputs.artifact("upstream"); assertNotNull(scope);
                    assertEquals("ScopeArtifact", scope.metadata().ref().type());
                    assertEquals(scope.metadata().ref().scopeRef(), scope.payload().path("scopeRef").asText());
                }
                public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {
                    assertTrue(outputs.isEmpty());
                }
            };
            var consumer = new PersistentPlanDriver.FixedExecutor(READ_SCOPE, policy, context -> {
                context.requireCurrent();
                var artifact = context.inputs().artifact("upstream");
                assertEquals(memberCount, artifact.payload().path("memberCount").asLong());
                assertEquals(memberCount, reopenedScopes.inspectPublished(OWNER, artifact.metadata().ref().artifactId(), artifactAuth()).memberCount());
                if (memberCount == 501)
                    assertEquals(List.of(501L), reopenedScopes.shard(OWNER, artifact.metadata().ref().artifactId(), 1, artifactAuth()).linkIds());
                consumedScope.set(artifact.metadata().ref().scopeRef()); consumptions.incrementAndGet();
                return PersistentPlanDriver.Result.succeeded(Map.of());
            });
            var driver = new PersistentPlanDriver(token, reopenedRuns, reopenedSteps, catalog(),
                    new ArtifactContractRegistry(List.of(ScopeCollectionFixedExecutor.artifactContract())),
                    List.of(adapter.registration(), consumer), (caller, inputs) -> OWNER.equals(caller) && adapter.authorized(),
                    artifactAuth(), (caller, type, value) -> { throw new AssertionError("This plan has no unfrozen ScopeRef/PeriodsRef input"); });
            return new Runtime(adapter, reopenedScopes, driver.compile(new MemorySaver()));
        }
        void exited() {
            assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            assertEquals(0, base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class));
        }
    }

    private static FrozenCampaignRun frozen() {
        var collect = new PlanSpec.Step(COLLECT, List.of("goal"), PlanSpec.ExecutionMode.FIXED, FrozenScopeCollection.REF, null, List.of(),
                Map.of("definition", PlanBinding.input("definition")), Map.of(), "campaign-scope/v1");
        var consume = new PlanSpec.Step(CONSUME, List.of("goal"), PlanSpec.ExecutionMode.FIXED, READ_SCOPE, null, List.of(COLLECT),
                Map.of("upstream", PlanBinding.output(COLLECT, "scopeArtifact")), Map.of(), "scope-consumer/v1");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "scope-plan", 1, RUN, "scope-inputs",
                List.of(new PlanSpec.Goal("goal", "Freeze the authorized group membership", true, "Deliver the complete immutable scope")), List.of(collect, consume));
        var inputs = new FrozenInputSet("scope-inputs", RUN, Map.of("definition", new Port(new TypeRef("ScopeCollectionDefinition", 1, Cardinality.ONE), true)),
                Map.of("definition", Map.of("schemaVersion", "scope-collection-definition/v1", "gid", "group-a", "expiresAt", Instant.ofEpochMilli(EXPIRY).toString())));
        var assessment = new PlanningAssessment("scope-plan", 1, "scope-catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput(COLLECT, "scopeArtifact")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }
    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            public String version() { return "scope-catalog/v1"; }
            public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                if (FrozenScopeCollection.REF.equals(ref)) return Optional.of(ScopeCollectionFixedExecutor.capability());
                return READ_SCOPE.equals(ref) ? Optional.of(new Capability(READ_SCOPE, new Signature(Map.of("upstream", new Port(SCOPE, true)),
                        "scope-consumer/v1", Map.of(), Parameters.none()), false)) : Optional.empty();
            }
            public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            public Optional<Criterion> criterion(String ref, String version) { return Optional.of(new Criterion(ref, version,
                    PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(SCOPE))); }
        };
    }
}
