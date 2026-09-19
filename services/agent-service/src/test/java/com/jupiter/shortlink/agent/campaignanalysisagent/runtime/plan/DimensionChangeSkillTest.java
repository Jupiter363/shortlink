package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionSkillFixture.*;
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

/** Actual native two-step graph, dynamic STEP_OUTPUT bindings and durable reception/release. */
@Timeout(60)
class DimensionChangeSkillTest {
    @Test
    void nativeSelectionThenDimensionBindsRealOutputsAcrossEightRecoveryPassesWithoutRepeatingJobs() throws Exception {
        var f = new DimensionSkillFixture("DECLINES", 501);
        assertTrue(f.steps.steps(f.token).isEmpty());
        var upstreamPending = f.dimension(f.token);
        assertTrue(upstreamPending.authorized(), "An unresolved future STEP_OUTPUT is not a forged missing artifact");
        assertTrue(upstreamPending.resultTargets().isEmpty());
        upstreamPending.prepareRecovery();
        assertEquals(0, f.gateway.calls()); assertTrue(f.children().isEmpty());
        assertEquals(0, f.artifacts("SelectedScopeArtifact"));

        var frozen = FrozenCampaignRun.read(f.token.definition());
        var downstream = frozen.plan().steps().stream().filter(step -> DIMENSION.equals(step.stepId())).findFirst().orElseThrow();
        for (String port : List.of("selectedEntities", "selectionEvidence")) {
            var binding = downstream.inputBindings().get(port);
            assertEquals(PlanBinding.Source.STEP_OUTPUT, binding.source());
            assertEquals(SELECT, binding.stepId()); assertEquals(port, binding.output()); assertNull(binding.artifactId());
        }
        var first = f.runtime(f.token).graph().advance();
        assertEquals(1, first.advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.WAITING, f.step(SELECT).status());
        assertNotEquals(CampaignStepStore.StepStatus.SUCCEEDED, f.step(DIMENSION).status());
        assertEquals(2, f.gateway.submits); assertEquals(0, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        Map<String, ChildSpec> originalRequests = new LinkedHashMap<>();
        f.children().forEach(child -> originalRequests.put(child.spec().childId(), child.spec()));

        assertEquals(8, f.finish(), "Four pairs of asynchronous jobs each first report RUNNING, then SUCCEEDED");
        assertEquals(4, f.gateway.submitted("LINK_METRICS"));
        assertEquals(4, f.gateway.submitted("DIMENSION_BREAKDOWN"));
        assertEquals(8, f.gateway.submits); assertEquals(8, f.gateway.accepted.size());
        assertEquals(8, f.gateway.pageReads); assertEquals(8, f.gateway.releases);
        assertEquals(0, f.gateway.recoveries);
        assertTrue(f.gateway.polls.values().stream().allMatch(count -> count == 3),
                "Two receiver polls and one source-status check before the confirmed release");
        for (var child : f.children()) {
            if (originalRequests.containsKey(child.spec().childId())) assertEquals(originalRequests.get(child.spec().childId()), child.spec());
            assertEquals(ChildState.READY, child.state());
            if (child.spec().mode() == ChildMode.LOCAL) assertEquals(1, child.attemptVersion(), "Completed deterministic local children are reused");
        }

        var sourceOutputs = f.step(SELECT).outputs();
        assertEquals(Set.of("selectedEntities", "selectionEvidence"), sourceOutputs.keySet());
        var selections = new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs);
        var selected = selections.readSelectedByLinkId(OWNER, sourceOutputs.get("selectedEntities"), null, 500, f.artifactAuth());
        assertEquals(LongStream.rangeClosed(1, 500).boxed().toList(), selected.rows().stream().map(CampaignLinkComparability.Result::linkId).toList());
        var selectedLast = selections.readSelectedByLinkId(OWNER, sourceOutputs.get("selectedEntities"), selected.nextCursor(), 500, f.artifactAuth());
        assertEquals(List.of(501L), selectedLast.rows().stream().map(CampaignLinkComparability.Result::linkId).toList());
        assertNull(selectedLast.nextCursor());

        List<FrozenQueryScope> dimensionScopes = f.gateway.accepted.values().stream()
                .filter(request -> "DIMENSION_BREAKDOWN".equals(request.get("queryKind")))
                .map(request -> FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"))).toList();
        assertEquals(List.of(1, 1, 500, 500), dimensionScopes.stream().map(scope -> scope.linkIds().size()).sorted().toList());
        assertEquals(LongStream.rangeClosed(1, 501).boxed().toList(), dimensionScopes.stream().flatMap(scope -> scope.linkIds().stream()).distinct().sorted().toList());
        assertTrue(dimensionScopes.stream().allMatch(scope -> !f.scopeRef.equals(scope.parentScopeRef())
                && scope.parentMemberCount() == 501 && VERSION.equals(scope.enumerationVersion())));
        assertEquals(1, dimensionScopes.stream().map(FrozenQueryScope::parentScopeRef).distinct().count());
        assertEquals(1, f.artifacts("SelectedScopeArtifact"));
        assertEquals(1, f.artifacts("DimensionChangeArtifact"));
        assertEquals(4, f.artifacts("DimensionChangePageArtifact"));
        assertEquals(Set.of("dimensionChanges"), f.step(DIMENSION).outputs().keySet());
        Artifact output = f.runs.readArtifact(OWNER, f.step(DIMENSION).outputs().get("dimensionChanges"), f.artifactAuth());
        JsonNode manifest = JSON.readTree(output.payloadJson());
        assertEquals(501, manifest.path("memberCount").asLong()); assertEquals(2, manifest.path("coveredCohorts").asInt());
        assertEquals(4, manifest.path("pageCount").asInt()); assertEquals(2, manifest.path("comparisonRows").asInt());
        assertEquals("OBSERVED", manifest.path("evidenceDisposition").asText());
        assertEquals("SHARD_COHORT", manifest.path("analysisUnit").asText());
        assertFalse(manifest.path("groupScopeComplete").asBoolean());
        assertEquals(Instant.ofEpochMilli(EXPIRY), output.metadata().ref().expiresAt());
        assertFalse(manifest.has("rows"));
        int pages = 0, observedRows = 0;
        String id = manifest.path("headArtifactId").asText(), hash = manifest.path("headPayloadHash").asText();
        Set<String> visited = new HashSet<>();
        while (id != null) {
            assertTrue(visited.add(id));
            Artifact page = f.runs.readArtifact(OWNER, id, f.artifactAuth());
            assertEquals(hash, page.metadata().ref().payloadHash());
            JsonNode body = JSON.readTree(page.payloadJson());
            assertEquals("OBSERVED_ONLY", body.path("interpretation").asText());
            assertEquals("UNKNOWN", body.path("quality").path("baseline").path("collectionQuality").path("status").asText());
            assertEquals("UNKNOWN", body.path("quality").path("target").path("collectionQuality").path("status").asText());
            assertFalse(body.path("quality").path("baseline").path("groupScopeComplete").asBoolean());
            observedRows += body.path("rows").size(); pages++;
            id = body.path("previousArtifactId").isNull() ? null : body.path("previousArtifactId").asText();
            hash = body.path("previousPayloadHash").isNull() ? null : body.path("previousPayloadHash").asText();
        }
        assertEquals(4, pages); assertEquals(2, observedRows);
        int calls = f.gateway.calls();
        var repeated = f.coordinator().resume(f.current(), PRINCIPAL);
        assertEquals(0, repeated.scan().advancedSteps()); assertEquals(calls, f.gateway.calls());
        assertEquals(output, f.runs.readArtifact(OWNER, output.metadata().ref().artifactId(), f.artifactAuth()));
        f.exited();
    }

    @Test
    void emptySelectionKeepsItsReasonAndSkipsDimensionJobsWhileRevokedReceptionCannotPublish() throws Exception {
        for (String mode : List.of("NO_DECLINES", "INCOMPATIBLE")) {
            var f = new DimensionSkillFixture(mode, 2);
            f.runtime(f.token).graph().advance();
            assertEquals(2, f.finish());
            assertEquals(2, f.gateway.submitted("LINK_METRICS")); assertEquals(0, f.gateway.submitted("DIMENSION_BREAKDOWN"));
            assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads); assertEquals(2, f.gateway.releases);
            assertEquals(0, f.gateway.recoveries); assertEquals(0, f.artifacts("DimensionChangePageArtifact"));
            Artifact output = f.runs.readArtifact(OWNER, f.step(DIMENSION).outputs().get("dimensionChanges"), f.artifactAuth());
            JsonNode manifest = JSON.readTree(output.payloadJson());
            assertEquals(0, manifest.path("memberCount").asLong()); assertEquals(0, manifest.path("coveredCohorts").asLong());
            assertEquals(0, manifest.path("pageCount").asLong()); assertTrue(manifest.path("headArtifactId").isNull());
            assertEquals("NO_DECLINES".equals(mode) ? "NOT_APPLICABLE" : "INSUFFICIENT_EVIDENCE", manifest.path("evidenceDisposition").asText());
            assertTrue(manifest.path("selectionComplete").asBoolean(),
                    "Every candidate was classified; incompatible evidence changes the disposition, not scan coverage");
            assertEquals("NO_DECLINES".equals(mode) ? "NO_DECLINES" : "INSUFFICIENT_EVIDENCE", manifest.path("emptyReason").asText());
            assertEquals(Instant.ofEpochMilli(EXPIRY), output.metadata().ref().expiresAt());
            assertTrue(f.children().stream().filter(child -> child.spec().mode() == ChildMode.LOCAL)
                    .allMatch(child -> child.state() == ChildState.READY && child.attemptVersion() == 1));
            f.exited();
        }

        var revoked = new DimensionSkillFixture("DECLINES", 2);
        revoked.runtime(revoked.token).graph().advance();
        revoked.gateway.revokeDuringDimensionPage = true;
        CampaignRecoveryCoordinator.ResumeResult stopped = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            var result = revoked.coordinator().resume(revoked.current(), PRINCIPAL);
            if (result.outcome() == CampaignRecoveryCoordinator.Outcome.STOPPED) { stopped = result; break; }
        }
        assertNotNull(stopped); assertFalse(revoked.allowed.get());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, revoked.step(SELECT).status());
        assertNotEquals(CampaignStepStore.StepStatus.SUCCEEDED, revoked.step(DIMENSION).status());
        assertEquals(2, revoked.gateway.submitted("DIMENSION_BREAKDOWN"));
        assertEquals(0, revoked.artifacts("DimensionChangePageArtifact")); assertEquals(0, revoked.artifacts("DimensionChangeArtifact"));
        assertEquals(2, revoked.gateway.releases, "Only the authorized upstream query results were released");
        assertThrows(SecurityException.class, () -> revoked.runs.readArtifact(OWNER,
                revoked.step(SELECT).outputs().get("selectedEntities"), revoked.artifactAuth()));
        int calls = revoked.gateway.calls();
        assertEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, revoked.coordinator().resume(revoked.current(), PRINCIPAL).outcome());
        assertEquals(calls, revoked.gateway.calls());
        revoked.exited();

        var interrupted = new DimensionSkillFixture("DECLINES", 2);
        interrupted.base.jdbc.execute("ALTER TABLE campaign_artifact ADD CONSTRAINT reject_dimension_page CHECK (artifact_id NOT LIKE 'dimension-%')");
        interrupted.runtime(interrupted.token).graph().advance();
        for (int attempt = 0; attempt < 6 && interrupted.step(DIMENSION).status() != CampaignStepStore.StepStatus.BLOCKED; attempt++) {
            var result = interrupted.coordinator().resume(interrupted.current(), PRINCIPAL);
            assertNotEquals(CampaignRecoveryCoordinator.Outcome.STOPPED, result.outcome(), result.reason());
        }
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, interrupted.step(SELECT).status());
        assertEquals(CampaignStepStore.StepStatus.BLOCKED, interrupted.step(DIMENSION).status());
        assertEquals("STEP_RESULT_UNKNOWN", interrupted.step(DIMENSION).reason());
        ChildRecord unknown = interrupted.children().stream()
                .filter(child -> child.spec().mode() == ChildMode.LOCAL
                        && "dimension-change-page".equals(child.spec().localInvocation().contractName()))
                .findFirst().orElseThrow();
        assertEquals(ChildState.UNRESOLVED, unknown.state());
        assertEquals(UnresolvedReason.LOCAL_RESULT_UNKNOWN, unknown.reason());
        assertEquals(1, unknown.attemptVersion());
        assertEquals(0, interrupted.artifacts("DimensionChangePageArtifact"));
        assertEquals(0, interrupted.artifacts("DimensionChangeArtifact"));
        assertEquals(4, interrupted.gateway.submits);
        assertEquals(4, interrupted.gateway.pageReads);
        assertEquals(4, interrupted.gateway.releases);
        interrupted.exited();

        interrupted.base.jdbc.execute("ALTER TABLE campaign_artifact DROP CONSTRAINT reject_dimension_page");
        interrupted.finish();
        ChildRecord recovered = interrupted.children().stream()
                .filter(child -> child.spec().childId().equals(unknown.spec().childId())).findFirst().orElseThrow();
        assertEquals(unknown.spec(), recovered.spec(), "Recovery rebuilds approval for the original frozen LOCAL invocation");
        assertEquals(ChildState.READY, recovered.state());
        assertEquals(2, recovered.attemptVersion());
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, interrupted.step(DIMENSION).status());
        assertEquals(2, interrupted.artifacts("DimensionChangePageArtifact"));
        assertEquals(1, interrupted.artifacts("DimensionChangeArtifact"));
        assertEquals(4, interrupted.gateway.submits, "Local replay cannot resubmit any of the four known statistics jobs");
        assertEquals(4, interrupted.gateway.pageReads, "Durable query results are reused after local publication fails");
        assertEquals(4, interrupted.gateway.releases);
        assertEquals(0, interrupted.gateway.recoveries);
        interrupted.exited();
    }
}
