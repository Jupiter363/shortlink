package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Period;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Slot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.Definition;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Derived membership only: actual JDBC source evidence and LOCAL publication, no dimension query executor. */
class CampaignSelectedScopeTest {
    private static final PlanSpec.Step SOURCE = step("source-selection", List.of());
    private static final PlanSpec.Step DERIVE = step("derive-selected-scope", List.of(SOURCE.stepId()));

    @Test
    void selected501MembersHaveTheirOwnHashAndIdOrdered500PlusOneShardsDespiteReverseDeltaOrder() throws Exception {
        try (Scenario s = new Scenario(502, "DECLINES", false)) {
            var sourceOrder = s.index.readSelectedPage(OWNER, s.pair.selectedArtifactId(), null, 500, s.authorizer());
            assertEquals(502, sourceOrder.rows().get(0).linkId());
            assertEquals(3, sourceOrder.rows().get(499).linkId());
            assertEquals(2, s.index.readSelectedPage(OWNER, s.pair.selectedArtifactId(), sourceOrder.nextCursor(), 500, s.authorizer()).rows().get(0).linkId());
            var linkOrder = s.index.readSelectedByLinkId(OWNER, s.pair.selectedArtifactId(), null, 500, s.authorizer());
            var evidenceOrder = s.index.readEvidencePage(OWNER, s.pair.evidenceArtifactId(), null, 500, s.authorizer());
            assertEquals(LongStream.rangeClosed(2, 501).boxed().toList(), linkOrder.rows().stream().map(CampaignLinkComparability.Result::linkId).toList());
            assertEquals(502, s.index.readSelectedByLinkId(OWNER, s.pair.selectedArtifactId(), linkOrder.nextCursor(), 500, s.authorizer()).rows().get(0).linkId());
            assertNotNull(sourceOrder.nextCursor()); assertNotNull(linkOrder.nextCursor()); assertNotNull(evidenceOrder.nextCursor());
            assertThrows(IllegalStateException.class, () -> s.index.readSelectedByLinkId(OWNER, s.pair.selectedArtifactId(), sourceOrder.nextCursor(), 500, s.authorizer()));
            assertThrows(IllegalStateException.class, () -> s.index.readEvidencePage(OWNER, s.pair.evidenceArtifactId(), sourceOrder.nextCursor(), 500, s.authorizer()));
            assertThrows(IllegalStateException.class, () -> s.index.readSelectedPage(OWNER, s.pair.selectedArtifactId(), linkOrder.nextCursor(), 500, s.authorizer()));
            assertThrows(IllegalStateException.class, () -> s.index.readEvidencePage(OWNER, s.pair.evidenceArtifactId(), linkOrder.nextCursor(), 500, s.authorizer()));
            assertThrows(IllegalStateException.class, () -> s.index.readSelectedPage(OWNER, s.pair.selectedArtifactId(), evidenceOrder.nextCursor(), 500, s.authorizer()));
            assertThrows(IllegalStateException.class, () -> s.index.readSelectedByLinkId(OWNER, s.pair.selectedArtifactId(), evidenceOrder.nextCursor(), 500, s.authorizer()));

            ArtifactRef scope = s.derived.publish(s.context, s.f.token, s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), PERIODS);
            Artifact published = s.f.runs.readArtifact(OWNER, scope.artifactId(), s.authorizer());
            JsonNode manifest = JSON.readTree(published.payloadJson());
            List<Long> selectedIds = LongStream.rangeClosed(2, 502).boxed().toList();
            String selectedHash = FrozenQueryScope.memberHash(selectedIds);
            assertEquals(CampaignSelectedScope.TYPE, scope.type());
            assertEquals(CampaignSelectedScope.SCHEMA, scope.schemaVersion());
            assertEquals(selectedHash, manifest.path("memberHash").asText());
            assertNotEquals(FrozenQueryScope.memberHash(LongStream.rangeClosed(1, 502).boxed().toList()), selectedHash);
            assertEquals(501, manifest.path("memberCount").asLong());
            assertEquals(2, manifest.path("shardCount").asInt());
            assertEquals(VERSION, manifest.path("enumerationVersion").asText());
            assertEquals("SOURCE_GROUP_MEMBERSHIP", manifest.path("enumerationVersionKind").asText());
            assertEquals(s.sourceScopeId, manifest.path("sourceScopeArtifactId").asText());
            assertEquals(s.f.runs.inspectArtifact(OWNER, s.sourceScopeId, s.authorizer()).ref().payloadHash(), manifest.path("sourceScopePayloadHash").asText());
            assertEquals(s.sourceScopeRef, manifest.path("sourceScopeRef").asText());
            assertNotEquals(s.sourceScopeRef, scope.scopeRef());
            assertTrue(manifest.path("groupScopeComplete").isBoolean());
            assertFalse(manifest.path("groupScopeComplete").asBoolean());
            assertTrue(manifest.path("selectionComplete").asBoolean());
            assertTrue(manifest.path("emptyReason").isNull());
            assertEquals(JSON.valueToTree(PERIODS), manifest.path("periods"));
            assertEquals(Instant.ofEpochMilli(EXPIRY), scope.expiresAt());
            assertNull(manifest.findValue("linkIds")); // Membership stays behind the verified source-backed index.
            assertNull(manifest.findValue("rows"));
            assertTrue(published.payloadJson().length() < 8192, "The manifest does not embed all selected members");

            var first = s.derived.shard(OWNER, scope.artifactId(), 0);
            var second = s.derived.shard(OWNER, scope.artifactId(), 1);
            assertEquals(selectedIds.subList(0, 500), first.linkIds());
            assertEquals(List.of(502L), second.linkIds());
            for (var shard : List.of(first, second)) {
                assertEquals("FROZEN_SET", shard.scopeKind());
                assertEquals(selectedHash, shard.parentMemberHash());
                assertEquals(501, shard.parentMemberCount());
                assertEquals(scope.scopeRef(), shard.parentScopeRef());
                assertEquals(VERSION, shard.enumerationVersion());
                assertEquals(FrozenQueryScope.memberHash(shard.linkIds()), shard.shardMemberHash());
                assertEquals(false, shard.proof("b".repeat(64)).get("parentComplete"));
            }
            var childBefore = s.derivedChildren().get(0);
            assertEquals(ChildMode.LOCAL, childBefore.spec().mode());
            assertEquals(1, childBefore.attemptVersion());
            assertNull(childBefore.spec().wire());
            assertEquals(Set.of("selectedScope"), s.f.runs.localOutputs(s.f.token, childBefore.spec().childId(), s.authorizer()).keySet());
            CampaignRunStore reopened = new JdbcCampaignRunStore(s.f.jdbc, s.f.transactions, CLOCK);
            var derived = new CampaignSelectedScope(reopened,
                    new JdbcCampaignDeclineSelectionStore(s.f.jdbc, s.f.transactions, CLOCK, reopened), s.authorizer());
            assertEquals(scope, derived.publish(s.context, s.f.token, s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), PERIODS));
            assertEquals(first, derived.shard(OWNER, scope.artifactId(), 0));
            assertEquals(second, derived.shard(OWNER, scope.artifactId(), 1));
            assertEquals(childBefore.attemptVersion(), s.derivedChildren().get(0).attemptVersion());
            assertEquals(published, reopened.readArtifact(OWNER, scope.artifactId(), s.authorizer()));
        }
    }

    @Test
    void sourcePeriodsPairAuthorityExpiryAndEmptyReasonsRemainExplicitWithoutCreatingQueryChildren() throws Exception {
        try (Scenario s = new Scenario(3, "DECLINES", true)) {
            assertEquals("SELECTION_PAIR_MISMATCH", assertThrows(IllegalStateException.class, () -> s.derived.prepare(s.f.token, DERIVE.stepId(),
                    s.pair.selectedArtifactId(), s.other.evidenceArtifactId(), PERIODS)).getMessage());
            List<Period> wrongDates = List.of(new Period("baseline", "2026-08-31", "2026-08-31", "Asia/Shanghai"), PERIODS.get(1));
            assertThrows(IllegalArgumentException.class, () -> s.derived.prepare(s.f.token, DERIVE.stepId(),
                    s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), wrongDates));
            assertTrue(s.derivedChildren().isEmpty());
            s.allowed.set(false);
            assertThrows(SecurityException.class, () -> s.derived.publish(s.context, s.f.token,
                    s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), PERIODS));
            assertTrue(s.derivedChildren().isEmpty());
            s.allowed.set(true);
            ArtifactRef scope = s.derived.publish(s.context, s.f.token, s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), PERIODS);
            s.allowed.set(false);
            assertThrows(SecurityException.class, () -> s.derived.shard(OWNER, scope.artifactId(), 0));
            s.allowed.set(true);
            Clock expired = Clock.fixed(Instant.ofEpochMilli(EXPIRY + 1), ZoneOffset.UTC);
            CampaignRunStore lateRuns = new JdbcCampaignRunStore(s.f.jdbc, s.f.transactions, expired);
            var late = new CampaignSelectedScope(lateRuns,
                    new JdbcCampaignDeclineSelectionStore(s.f.jdbc, s.f.transactions, expired, lateRuns), s.authorizer());
            assertThrows(RuntimeException.class, () -> late.shard(OWNER, scope.artifactId(), 0));
            assertEquals(1, s.derivedChildren().size());
            assertTrue(s.derivedChildren().stream().allMatch(child -> child.spec().mode() == ChildMode.LOCAL && child.spec().wire() == null));
        }
        for (String mode : List.of("NO_DECLINES", "GAP")) {
            try (Scenario s = new Scenario(3, mode, false)) {
                ArtifactRef scope = s.derived.publish(s.context, s.f.token, s.pair.selectedArtifactId(), s.pair.evidenceArtifactId(), PERIODS);
                JsonNode manifest = JSON.readTree(s.f.runs.readArtifact(OWNER, scope.artifactId(), s.authorizer()).payloadJson());
                assertTrue(manifest.path("memberCount").isIntegralNumber());
                assertTrue(manifest.path("shardCount").isIntegralNumber());
                assertEquals(0, manifest.path("memberCount").asLong());
                assertEquals(0, manifest.path("shardCount").asInt());
                assertEquals("NO_DECLINES".equals(mode) ? "NO_DECLINES" : "INSUFFICIENT_EVIDENCE", manifest.path("emptyReason").asText());
                assertEquals("NO_DECLINES".equals(mode), manifest.path("selectionComplete").asBoolean());
                assertEquals(FrozenQueryScope.memberHash(List.of()), manifest.path("memberHash").asText());
                assertFalse(manifest.path("groupScopeComplete").asBoolean());
                assertThrows(IllegalArgumentException.class, () -> s.derived.shard(OWNER, scope.artifactId(), 0));
                assertEquals(1, s.derivedChildren().size());
                assertTrue(s.derivedChildren().stream().allMatch(child -> child.spec().mode() == ChildMode.LOCAL && child.spec().wire() == null));
            }
        }
    }

    private static PlanSpec.Step step(String id, List<String> dependencies) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED,
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, id, "1"), null, dependencies, Map.of(), Map.of(), "local/v1");
    }

    private static final class Scenario implements AutoCloseable {
        final Fixture f = new Fixture();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final CampaignStepStore steps;
        final CampaignDeclineSelectionStore index;
        final String sourceScopeId, sourceScopeRef;
        final Slot[][] slots;
        final Receipt pair, other;
        final StepPermit permit;
        final CampaignStepExecution context;
        final CampaignSelectedScope derived;

        Scenario(int count, String mode, boolean secondPair) throws Exception {
            sourceScopeId = f.publishScope(count);
            sourceScopeRef = f.runs.inspectArtifact(OWNER, sourceScopeId, ALLOW).ref().scopeRef();
            int shards = (count + 499) / 500;
            slots = new Slot[2][shards];
            for (int shard = 0; shard < shards; shard++) {
                var scope = f.scopes.shard(OWNER, sourceScopeId, shard, ALLOW);
                for (int period = 0; period < 2; period++)
                    slots[period][shard] = f.publish("selected-input-" + period + "-" + shard, scope, period, "VALID",
                            (id, time) -> "NO_DECLINES".equals(mode) ? 2 : id == 1 ? 0 : time == 0 ? id : 0,
                            meta -> {});
            }
            steps = new JdbcCampaignStepStore(f.jdbc, f.transactions, CLOCK);
            steps.initialize(f.token, List.of(
                    new StepSpec(SOURCE.stepId(), FrozenCampaignRun.encode(SOURCE), List.of(), Set.of("selectedEntities", "selectionEvidence"), Set.of("selectedEntities", "selectionEvidence")),
                    new StepSpec(DERIVE.stepId(), FrozenCampaignRun.encode(DERIVE), List.of(SOURCE.stepId()), Set.of("selectedScope"), Set.of("selectedScope"))));
            index = new JdbcCampaignDeclineSelectionStore(f.jdbc, f.transactions, CLOCK, f.runs);
            var coverage = new CampaignParentCoverage(f.runs, f.scopes, f.results);
            var publisher = new DeclineSelectionPublisher(f.runs, new CampaignObservedLinkComparison(coverage), index, authorizer());
            var sourcePermit = steps.beginStep(f.token, SOURCE.stepId());
            try (var sourceContext = new CampaignStepExecution(SOURCE, List.of(), null, sourcePermit, f.runs, steps, allowed::get)) {
                pair = selection(publisher, sourceContext, "selection-a", count, shards, mode);
                other = secondPair ? selection(publisher, sourceContext, "selection-b", count, shards, mode) : null;
                steps.settle(sourcePermit, StepStatus.SUCCEEDED,
                        Map.of("selectedEntities", pair.selectedArtifactId(), "selectionEvidence", pair.evidenceArtifactId()), null, authorizer());
            } finally { steps.callbackExited(sourcePermit); }
            permit = steps.beginStep(f.token, DERIVE.stepId());
            context = new CampaignStepExecution(DERIVE, List.of(), null, permit, f.runs, steps, allowed::get);
            derived = new CampaignSelectedScope(f.runs, index, authorizer());
        }

        private Receipt selection(DeclineSelectionPublisher publisher, CampaignStepExecution context, String collection,
                int count, int shards, String mode) throws Exception {
            var definition = new Definition(collection, sourceScopeId, sourceScopeRef, "baseline-target", CampaignLinkComparability.Metric.PV, shards, count);
            Receipt receipt = null;
            for (int shard = 0; shard < Math.max(1, shards); shard++)
                receipt = publisher.publishShard(context, f.token, definition, PERIODS,
                        (period, index) -> "GAP".equals(mode) && period == 0 ? null : slots[period][index], shard,
                        receipt == null ? null : receipt.chainArtifactId());
            return publisher.finish(context, f.token, Objects.requireNonNull(receipt).chainArtifactId());
        }

        ArtifactAuthorizer authorizer() { return (caller, artifact) -> allowed.get() && OWNER.equals(caller) && OWNER.equals(artifact.owner()); }
        List<ChildRecord> derivedChildren() {
            Set<String> actions = new HashSet<>(f.jdbc.queryForList("SELECT action_id FROM campaign_action_ledger WHERE run_id=? AND step_id=?",
                    String.class, f.token.definition().runId(), DERIVE.stepId()));
            return f.runs.children(f.token).stream().filter(child -> actions.contains(child.spec().actionId())).toList();
        }
        @Override public void close() { context.close(); steps.callbackExited(permit); }
    }
}
