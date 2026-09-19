package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DynamicScopeSkillFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.Instant;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Three real native steps. No scope artifact or scope reference exists before collection executes. */
@Timeout(60)
class DynamicScopeSkillPlanTest {
    @Test
    void nativeCollectionProducesDynamicScopeForBothV2SkillsAndAllOriginalJobsAreReadReleasedAndReused() throws Exception {
        var f = new DynamicScopeSkillFixture("VALID", 501);
        assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
        assertTrue(f.children().isEmpty()); assertTrue(f.authorityCursors.isEmpty());
        var frozen = FrozenCampaignRun.read(f.token.definition());
        assertEquals(3, frozen.plan().steps().size());
        assertFalse(frozen.inputs().inputContracts().values().stream().anyMatch(port -> "ScopeRef".equals(port.type().name())));
        assertFalse(FrozenCampaignRun.encode(frozen.inputs()).contains("\"scopeRef\""));
        assertTrue(frozen.plan().steps().stream().flatMap(step -> step.inputBindings().values().stream())
                .noneMatch(binding -> binding.source() == PlanBinding.Source.ARTIFACT));
        var select = frozen.plan().steps().get(1);
        assertEquals(SELECT_REF, select.executor());
        assertEquals(PlanBinding.output(COLLECT, "scopeArtifact"), select.inputBindings().get("scopeArtifact"));
        var dimension = frozen.plan().steps().get(2);
        assertEquals(DIMENSION_REF, dimension.executor());
        assertEquals(PlanBinding.output(SELECT, "selectedEntities"), dimension.inputBindings().get("selectedEntities"));
        assertEquals(PlanBinding.output(SELECT, "selectionEvidence"), dimension.inputBindings().get("selectionEvidence"));
        assertTrue(f.selection(f.token).authorized()); assertTrue(f.selection(f.token).resultTargets().isEmpty());
        assertTrue(f.dimension(f.token).authorized()); assertTrue(f.dimension(f.token).resultTargets().isEmpty());
        assertEquals(0, f.gateway.calls());

        assertEquals(1, f.runtime(f.token).graph().advance().advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, f.step(COLLECT).status());
        assertEquals("SCOPE_COLLECTION_PROGRESS", f.step(COLLECT).reason());
        assertEquals(Collections.singletonList(null), f.authorityCursors);
        assertEquals(0, f.gateway.calls()); assertEquals(0, f.artifacts("ScopeArtifact"));
        ChildRecord firstPage = f.children().get(0);
        assertEquals(ChildState.READY, firstPage.state()); assertEquals(ChildMode.SYNC, firstPage.spec().mode());
        assertEquals("ScopePageRef", f.runs.inspectArtifact(OWNER, firstPage.artifactId(), f.artifactAuth()).ref().type());

        var resumed = f.coordinator().resume(f.current(), PRINCIPAL);
        assertNotEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, resumed.outcome(), resumed.reason());
        assertEquals(Arrays.asList(null, 500L), f.authorityCursors);
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, f.step(COLLECT).status());
        assertEquals(CampaignStepStore.StepStatus.WAITING, f.step(SELECT).status());
        assertEquals(2, f.gateway.submitted("LINK_METRICS")); assertEquals(0, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        String scopeId = f.scopeArtifact();
        Artifact originalScope = f.runs.readArtifact(OWNER, scopeId, f.artifactAuth());
        String originalRef = originalScope.metadata().ref().scopeRef();
        var scopeBody = JSON.readTree(originalScope.payloadJson());
        assertEquals(501, scopeBody.path("memberCount").asLong()); assertEquals(2, scopeBody.path("pageCount").asInt());
        assertEquals(VERSION, scopeBody.path("enumerationVersion").asText());
        assertEquals(Instant.ofEpochMilli(EXPIRY), originalScope.metadata().ref().expiresAt());
        Map<String, ChildSpec> frozenChildren = new LinkedHashMap<>();
        f.children().forEach(child -> frozenChildren.put(child.spec().childId(), child.spec()));

        assertTrue(f.finish() > 0);
        assertEquals(2, f.authorityCursors.size());
        assertEquals(4, f.gateway.submitted("LINK_METRICS")); assertEquals(4, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        assertEquals(8, f.gateway.submits); assertEquals(8, f.gateway.accepted.size());
        assertEquals(8, f.gateway.pageReads); assertEquals(8, f.gateway.releases); assertEquals(0, f.gateway.recoveries);
        assertTrue(f.gateway.polls.values().stream().allMatch(count -> count == 3), "Each job has two reception polls and one release preflight status check");
        assertEquals(firstPage.spec(), f.runs.child(f.current(), firstPage.spec().childId()).orElseThrow().spec());
        assertEquals(originalScope, f.runs.readArtifact(OWNER, scopeId, f.artifactAuth()));
        assertEquals(2, f.children().stream().filter(child -> child.spec().mode() == ChildMode.SYNC).count());
        assertEquals(8, f.children().stream().filter(child -> child.spec().mode() == ChildMode.ASYNC).count());
        for (var child : f.children()) {
            assertEquals(ChildState.READY, child.state()); assertFalse(child.callbackActive());
            if (frozenChildren.containsKey(child.spec().childId())) assertEquals(frozenChildren.get(child.spec().childId()), child.spec());
            if (child.spec().mode() == ChildMode.LOCAL) assertEquals(1, child.attemptVersion());
        }
        for (String kind : List.of("LINK_METRICS", "DIMENSION_BREAKDOWN")) {
            List<FrozenQueryScope> scopes = f.gateway.accepted.values().stream().filter(request -> kind.equals(request.get("queryKind")))
                    .map(request -> FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"))).toList();
            assertEquals(List.of(1, 1, 500, 500), scopes.stream().map(scope -> scope.linkIds().size()).sorted().toList());
            assertEquals(LongStream.rangeClosed(1, 501).boxed().toList(), scopes.stream().flatMap(scope -> scope.linkIds().stream()).distinct().sorted().toList());
            assertTrue(scopes.stream().allMatch(scope -> scope.parentMemberCount() == 501 && VERSION.equals(scope.enumerationVersion())));
            assertTrue(scopes.stream().allMatch(scope -> "LINK_METRICS".equals(kind)
                    ? originalRef.equals(scope.parentScopeRef()) : !originalRef.equals(scope.parentScopeRef())));
        }
        assertEquals(1, f.artifacts("ScopeArtifact")); assertEquals(1, f.artifacts("SelectedScopeArtifact"));
        assertEquals(1, f.artifacts("DimensionChangeArtifact")); assertEquals(4, f.artifacts("DimensionChangePageArtifact"));
        Artifact output = f.runs.readArtifact(OWNER, f.step(DIMENSION).outputs().get("dimensionChanges"), f.artifactAuth());
        JsonNode manifest = JSON.readTree(output.payloadJson());
        assertEquals("campaign.dimension-change/v1", output.metadata().ref().schemaVersion());
        assertEquals("OBSERVED", manifest.path("evidenceDisposition").asText());
        assertEquals("SHARD_COHORT", manifest.path("analysisUnit").asText());
        assertEquals(501, manifest.path("memberCount").asLong()); assertEquals(2, manifest.path("coveredCohorts").asInt());
        assertEquals(4, manifest.path("pageCount").asInt()); assertEquals(2, manifest.path("comparisonRows").asInt());
        assertFalse(manifest.path("groupScopeComplete").asBoolean()); assertFalse(manifest.has("rows"));
        assertEquals(Instant.ofEpochMilli(EXPIRY), output.metadata().ref().expiresAt());
        int count = 0;
        String id = manifest.path("headArtifactId").asText(), hash = manifest.path("headPayloadHash").asText();
        while (id != null) {
            Artifact page = f.runs.readArtifact(OWNER, id, f.artifactAuth()); assertEquals(hash, page.metadata().ref().payloadHash());
            JsonNode payload = JSON.readTree(page.payloadJson());
            assertEquals("UNKNOWN", payload.path("quality").path("baseline").path("collectionQuality").path("status").asText());
            assertEquals("UNKNOWN", payload.path("quality").path("target").path("collectionQuality").path("status").asText());
            assertEquals("OBSERVED_ONLY", payload.path("interpretation").asText());
            assertTrue(++count <= 4, "Published page chain cannot cycle");
            id = payload.path("previousArtifactId").isNull() ? null : payload.path("previousArtifactId").asText();
            hash = payload.path("previousPayloadHash").isNull() ? null : payload.path("previousPayloadHash").asText();
        }
        assertEquals(4, count);
        int calls = f.gateway.calls();
        assertEquals(0, f.coordinator().resume(f.current(), PRINCIPAL).scan().advancedSteps());
        assertEquals(calls, f.gateway.calls()); assertEquals(2, f.authorityCursors.size());
        assertEquals(output, f.runs.readArtifact(OWNER, output.metadata().ref().artifactId(), f.artifactAuth()));
        f.exited();
    }

    @Test
    void dynamicSourceBindingsRequireDeclaredProducerPeriodAndCurrentAuthorityBeforeFurtherIo() throws Exception {
        var periods = new DynamicScopeSkillFixture("WRONG_PERIODS", 2);
        periods.runtime(periods.token).graph().advance();
        for (int pass = 0; pass < 4 && periods.step(SELECT).status() != CampaignStepStore.StepStatus.SUCCEEDED; pass++)
            periods.coordinator().resume(periods.current(), PRINCIPAL);
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, periods.step(SELECT).status());
        assertFalse(periods.dimension(periods.current()).authorized());
        assertNotEquals(CampaignStepStore.StepStatus.SUCCEEDED, periods.step(DIMENSION).status());
        assertEquals(2, periods.gateway.submitted("LINK_METRICS")); assertEquals(0, periods.gateway.submitted("DIMENSION_BREAKDOWN"));
        assertEquals(2, periods.gateway.pageReads); assertEquals(2, periods.gateway.releases);
        assertEquals(0, periods.artifacts("DimensionChangeArtifact")); periods.exited();

        var missing = new DynamicScopeSkillFixture("VALID", 2);
        var runtime = missing.runtime(missing.token);
        var collect = FrozenCampaignRun.read(missing.token.definition()).plan().steps().get(0);
        runtime.driver().advance(collect); // Real producer execution, paused before scanning the dependent node.
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, missing.step(COLLECT).status());
        assertEquals(1, missing.artifacts("ScopeArtifact")); assertEquals(0, missing.gateway.calls());
        assertEquals(1, missing.base.jdbc.update("UPDATE campaign_step_ledger SET outputs_json='{}' WHERE run_id=? AND step_id=?",
                RUN, COLLECT));
        assertThrows(IllegalArgumentException.class, () -> missing.selection(missing.current()).authorized(),
                "A published artifact is insufficient without the declared producer output");
        assertThrows(IllegalArgumentException.class, () -> missing.runtime(missing.current()));
        assertEquals(0, missing.gateway.calls()); assertEquals(1, missing.authorityCursors.size()); missing.exited();

        var revoked = new DynamicScopeSkillFixture("VALID", 2);
        var beforeRevocation = revoked.runtime(revoked.token);
        beforeRevocation.driver().advance(FrozenCampaignRun.read(revoked.token.definition()).plan().steps().get(0));
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, revoked.step(COLLECT).status());
        assertEquals(0, revoked.gateway.calls());
        revoked.allowed.set(false);
        assertThrows(SecurityException.class, () -> revoked.selection(revoked.current()).authorized());
        assertEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, revoked.coordinator().resume(revoked.current(), PRINCIPAL).outcome());
        assertEquals(0, revoked.gateway.calls()); assertEquals(1, revoked.authorityCursors.size());
        assertNotEquals(CampaignStepStore.StepStatus.SUCCEEDED, revoked.step(SELECT).status());
        assertEquals(0, revoked.artifacts("SelectedEntitiesArtifact")); assertEquals(0, revoked.artifacts("DimensionChangeArtifact"));
        revoked.exited();
    }
}
