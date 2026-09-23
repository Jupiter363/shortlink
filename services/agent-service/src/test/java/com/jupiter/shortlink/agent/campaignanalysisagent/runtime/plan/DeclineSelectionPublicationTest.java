package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Slot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionPage.Definition;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class DeclineSelectionPublicationTest {
    private static final Set<String> OUTPUTS = Set.of("selectedEntities", "selectionEvidence");
    private static final PlanSpec.Step STEP = new PlanSpec.Step("select-declines", List.of("goal"),
            PlanSpec.ExecutionMode.FIXED, new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, "decline-selection", "1"),
            null, List.of(), Map.of(), Map.of(), "selection-pages/v1");

    @Test
    void selectedScanStreamsCanonicalPagesWithOnlyOneSourceChainVerification() throws Exception {
        try (Scenario s = new Scenario(501, (id, period) -> period == 0 ? id : 0, (period, meta) -> {})) {
            Receipt first = s.shard(0, null, false);
            Receipt second = s.shard(1, first.chainArtifactId(), false);
            Receipt done = s.publisher.finish(s.context, s.f.token, second.chainArtifactId());
            AtomicInteger sourceReads = new AtomicInteger();
            ArtifactAuthorizer counting = (caller, artifact) -> {
                if (DeclineSelectionPage.PAGE_TYPE.equals(artifact.ref().type())) sourceReads.incrementAndGet();
                return OWNER.equals(caller) && OWNER.equals(artifact.owner());
            };
            var expected = s.index.inspectPair(OWNER, done.selectedArtifactId(), done.evidenceArtifactId(), counting);
            int singleChainReads = sourceReads.getAndSet(0);
            assertTrue(singleChainReads > 0);
            List<Integer> sizes = new ArrayList<>();
            long[] previous = {0};
            var actual = s.index.scanSelectedByLinkId(OWNER, done.selectedArtifactId(), done.evidenceArtifactId(),
                    137, counting, page -> {
                        assertFalse(page.isEmpty());
                        assertTrue(page.size() <= 137);
                        sizes.add(page.size());
                        for (Result row : page) {
                            assertEquals(++previous[0], row.linkId());
                            assertEquals(CampaignLinkComparability.Comparability.VERIFIED, row.comparability());
                            assertTrue(row.delta().signum() < 0);
                        }
                    });
            assertEquals(expected, actual);
            assertEquals(501, previous[0]);
            assertEquals(List.of(137, 137, 137, 90), sizes);
            assertEquals(singleChainReads, sourceReads.get(), "Each callback page must not replay the source chain");

            // A failure in a later source page cannot turn the already-visited prefix into success.
            s.f.jdbc.update("UPDATE campaign_decline_row SET delta_value=-999 WHERE collection_id=? AND link_id=501",
                    s.definition.collectionId());
            AtomicInteger partial = new AtomicInteger();
            assertThrows(IllegalStateException.class, () -> s.index.scanSelectedByLinkId(OWNER,
                    done.selectedArtifactId(), done.evidenceArtifactId(), 137, ALLOW, page -> partial.incrementAndGet()));
            assertTrue(partial.get() > 0, "The trusted visitor must discard all scratch state after a failed scan");
        }
    }

    @Test
    void selectedScanRejectsPageTimeRevocationVisitorFailureAndCorruptIndex() throws Exception {
        try (Scenario s = new Scenario(3, (id, period) -> period == 0 ? id : 0, (period, meta) -> {})) {
            Receipt first = s.shard(0, null, false);
            Receipt done = s.publisher.finish(s.context, s.f.token, first.chainArtifactId());
            AtomicBoolean allowed = new AtomicBoolean(true);
            AtomicInteger visited = new AtomicInteger();
            ArtifactAuthorizer revocable = (caller, artifact) -> allowed.get() && OWNER.equals(caller);
            assertThrows(SecurityException.class, () -> s.index.scanSelectedByLinkId(OWNER,
                    done.selectedArtifactId(), done.evidenceArtifactId(), 1, revocable, page -> {
                        visited.incrementAndGet();
                        allowed.set(false);
                    }));
            assertEquals(1, visited.get(), "Revocation after a callback must stop before another page is delivered");
            IllegalStateException stopped = new IllegalStateException("stop-calculation");
            assertSame(stopped, assertThrows(IllegalStateException.class, () -> s.index.scanSelectedByLinkId(OWNER,
                    done.selectedArtifactId(), done.evidenceArtifactId(), 1, ALLOW, page -> { throw stopped; })));
            s.f.jdbc.update("UPDATE campaign_decline_row SET selected=FALSE WHERE collection_id=? AND link_id=2",
                    s.definition.collectionId());
            assertThrows(IllegalStateException.class, () -> s.index.scanSelectedByLinkId(OWNER,
                    done.selectedArtifactId(), done.evidenceArtifactId(), 1, ALLOW,
                    page -> fail("A corrupted source/index page cannot reach the visitor")));
        }
    }

    @Test
    void allFiveHundredAndOneCandidatesProduceGloballySortedSelectionAndSourceCheckedResumablePages() throws Exception {
        try (Scenario s = new Scenario(501,
                (id, period) -> id == 1 ? 0 : id == 501 ? period == 0 ? 100 : 0 : period == 0 ? 2 : 1,
                (period, meta) -> {})) {
            Receipt first = s.shard(0, null, false);
            Artifact originalHead = s.f.runs.readArtifact(OWNER, first.chainArtifactId(), ALLOW);
            ChildRecord originalChild = localChildren(s.f.runs, s.f.token).get(0);
            assertEquals(1, originalChild.attemptVersion());
            assertEquals(ChildState.READY, originalChild.state());

            // Reconstruct all adapters against the same durable ledger. A READY child is reused,
            // not recalculated; this is adapter reconstruction, not an unproven process takeover.
            CampaignRunStore reopenedRuns = new JdbcCampaignRunStore(s.f.jdbc, s.f.transactions, CLOCK);
            var reopenedSteps = new JdbcCampaignStepStore(s.f.jdbc, s.f.transactions, CLOCK);
            var reopenedIndex = new JdbcCampaignDeclineSelectionStore(s.f.jdbc, s.f.transactions, CLOCK, reopenedRuns);
            var reopenedPublisher = publisher(s.f, reopenedRuns, reopenedIndex, ALLOW);
            Receipt done;
            try (var reopenedContext = new CampaignStepExecution(STEP, List.of(), null, s.permit,
                    reopenedRuns, reopenedSteps, () -> true)) {
                Receipt replay = reopenedPublisher.publishShard(reopenedContext, s.f.token, s.definition,
                        PERIODS, (period, shard) -> s.slots[period][shard], 0, null);
                assertEquals(first, replay);
                assertEquals(originalChild.attemptVersion(), reopenedRuns.child(s.f.token, originalChild.spec().childId()).orElseThrow().attemptVersion());
                Artifact replayedHead = reopenedRuns.readArtifact(OWNER, replay.chainArtifactId(), ALLOW);
                assertEquals(originalHead.metadata().ref(), replayedHead.metadata().ref());
                assertEquals(originalHead.payloadJson(), replayedHead.payloadJson());
                assertEquals(Instant.ofEpochMilli(EXPIRY), replayedHead.metadata().ref().expiresAt());
                Receipt second = reopenedPublisher.publishShard(reopenedContext, s.f.token, s.definition,
                        PERIODS, (period, shard) -> s.slots[period][shard], 1, first.chainArtifactId());
                var secondChain = DeclineSelectionPage.decodeChain(reopenedRuns.readArtifact(OWNER, second.chainArtifactId(), ALLOW).payloadJson());
                assertEquals(first.chainArtifactId(), secondChain.previousArtifactId());
                assertEquals(first.chainPayloadHash(), secondChain.previousPayloadHash());
                assertEquals(first.chainArtifactId(), localChildren(reopenedRuns, s.f.token).stream()
                        .map(child -> child.spec().localInvocation()).filter(invocation -> invocation.inputs().containsKey("previous"))
                        .findFirst().orElseThrow().inputs().get("previous").ref().artifactId());
                done = reopenedPublisher.finish(reopenedContext, s.f.token, second.chainArtifactId());
                assertTrue(done.sealed());
                assertEquals(done, reopenedPublisher.finish(reopenedContext, s.f.token, second.chainArtifactId()));
            }
            List<Result> selected = readAll(reopenedIndex, done.selectedArtifactId(), true, 137);
            assertEquals(500, selected.size());
            assertEquals(501, selected.get(0).linkId());
            assertEquals(BigInteger.valueOf(-100), selected.get(0).delta());
            assertEquals(LongStream.rangeClosed(2, 500).boxed().toList(), selected.subList(1, selected.size()).stream().map(Result::linkId).toList());
            assertEquals(selected.stream().sorted(Comparator.comparing(Result::delta).thenComparingLong(Result::linkId)).toList(), selected);
            List<Result> evidence = readAll(reopenedIndex, done.evidenceArtifactId(), false, 149);
            assertEquals(LongStream.rangeClosed(1, 501).boxed().toList(), evidence.stream().map(Result::linkId).toList());
            assertTrue(evidence.stream().allMatch(row -> row.explanation() == CampaignLinkComparability.Explanation.OBSERVED_ONLY
                    && row.reasonCodes().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED")
                    && row.reasonCodes().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED")));
            Artifact selectedManifest = reopenedRuns.readArtifact(OWNER, done.selectedArtifactId(), ALLOW);
            JsonNode manifest = JSON.readTree(selectedManifest.payloadJson());
            assertEquals(501, manifest.path("candidateCount").asLong());
            assertEquals(500, manifest.path("selectedCount").asLong());
            assertTrue(manifest.path("selectionComplete").asBoolean());
            assertTrue(manifest.path("emptyReason").isNull());
            assertEquals("UNVERIFIED", JSON.readTree(selectedManifest.metadata().qualityJson()).path("collectionCompleteness").asText());
            assertEquals(Instant.ofEpochMilli(EXPIRY), selectedManifest.metadata().ref().expiresAt());
            for (Slot[] period : s.slots) for (Slot slot : period) {
                JsonNode source = JSON.readTree(s.f.results.readPage(OWNER, slot.artifactId(), 0, ALLOW));
                assertEquals("UNKNOWN", source.path("meta").path("collectionQuality").path("status").asText());
            }
            var selectedFirst = reopenedIndex.readSelectedPage(OWNER, done.selectedArtifactId(), null, 137, ALLOW);
            assertNotNull(selectedFirst.nextCursor());
            assertThrows(RuntimeException.class, () -> reopenedIndex.readEvidencePage(OWNER, done.evidenceArtifactId(), selectedFirst.nextCursor(), 137, ALLOW));

            s.f.jdbc.update("UPDATE campaign_decline_row SET delta_value=-999 WHERE collection_id=? AND link_id=501", s.definition.collectionId());
            assertThrows(RuntimeException.class, () -> reopenedIndex.readSelectedPage(OWNER, done.selectedArtifactId(), null, 137, ALLOW));
            s.f.jdbc.update("UPDATE campaign_decline_row SET delta_value=-100 WHERE collection_id=? AND link_id=501", s.definition.collectionId());
            var evidenceFirst = reopenedIndex.readEvidencePage(OWNER, done.evidenceArtifactId(), null, 500, ALLOW);
            assertNotNull(evidenceFirst.nextCursor());
            String sourcePage = s.f.jdbc.queryForObject("SELECT page_artifact_id FROM campaign_decline_page WHERE collection_id=? AND ordinal_index=1",
                    String.class, s.definition.collectionId());
            s.f.jdbc.update("UPDATE campaign_artifact_payload SET payload_json='{}' WHERE artifact_id=?", sourcePage);
            assertThrows(RuntimeException.class, () -> reopenedIndex.readEvidencePage(OWNER, done.evidenceArtifactId(), evidenceFirst.nextCursor(), 500, ALLOW));
        }
    }

    @Test
    void emptySelectionExplainsNoDeclinesVersusInsufficientEvidenceAndNeverExposesUnsealedOrRevokedResults() throws Exception {
        for (String mode : List.of("NO_DECLINES", "INCOMPATIBLE", "GAP", "EMPTY")) {
            try (Scenario s = new Scenario("EMPTY".equals(mode) ? 0 : 3, (id, period) -> 2, (period, meta) -> {
                if ("INCOMPATIBLE".equals(mode) && period == 1)
                    meta.put("approximation", Map.of("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v2"),
                            "uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"),
                            "uip", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1")));
            })) {
                if ("EMPTY".equals(mode)) {
                    assertEquals(0, s.slots[0].length); assertEquals(0, s.slots[1].length);
                    assertTrue(s.f.runs.children(s.f.token).stream().noneMatch(child -> child.spec().mode() == ChildMode.ASYNC));
                }
                Receipt page = s.shard(0, null, "GAP".equals(mode));
                Receipt done = s.publisher.finish(s.context, s.f.token, page.chainArtifactId());
                assertTrue(done.sealed());
                assertTrue(s.index.readSelectedPage(OWNER, done.selectedArtifactId(), null, 500, ALLOW).rows().isEmpty());
                assertNull(s.index.readSelectedPage(OWNER, done.selectedArtifactId(), null, 500, ALLOW).nextCursor());
                JsonNode manifest = JSON.readTree(s.f.runs.readArtifact(OWNER, done.selectedArtifactId(), ALLOW).payloadJson());
                assertEquals(Set.of("NO_DECLINES", "EMPTY").contains(mode) ? "NO_DECLINES" : "INSUFFICIENT_EVIDENCE", manifest.path("emptyReason").asText());
                assertEquals(!"GAP".equals(mode), manifest.path("selectionComplete").asBoolean());
                var evidence = s.index.readEvidencePage(OWNER, done.evidenceArtifactId(), null, 500, ALLOW);
                assertEquals(Set.of("GAP", "EMPTY").contains(mode) ? 0 : 3, evidence.rows().size());
                if ("INCOMPATIBLE".equals(mode)) assertTrue(evidence.rows().stream()
                        .allMatch(row -> row.comparability() == CampaignLinkComparability.Comparability.INCOMPATIBLE));
                assertThrows(SecurityException.class, () -> s.index.readSelectedPage(OWNER, done.selectedArtifactId(), null, 500, (caller, artifact) -> false));
                assertThrows(SecurityException.class, () -> s.index.readEvidencePage(OWNER, done.evidenceArtifactId(), null, 500, (caller, artifact) -> false));
            }
        }
        try (Scenario s = new Scenario(3, (id, period) -> period == 0 ? 2 : 1, (period, meta) -> {})) {
            Receipt page = s.shard(0, null, false);
            s.f.jdbc.execute("ALTER TABLE campaign_artifact ADD CONSTRAINT reject_final_evidence CHECK (artifact_id NOT LIKE 'decline-evidence-%')");
            assertThrows(RuntimeException.class, () -> s.publisher.finish(s.context, s.f.token, page.chainArtifactId()));
            assertFalse(s.f.jdbc.queryForObject("SELECT sealed FROM campaign_decline_collection WHERE collection_id=?", Boolean.class, s.definition.collectionId()));
            assertEquals(0, s.f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE artifact_id LIKE 'selected-%' OR artifact_id LIKE 'decline-evidence-%'", Integer.class));
            ChildRecord failedFinal = localChildren(s.f.runs, s.f.token).stream()
                    .filter(child -> child.spec().localInvocation().contractName().equals("decline-selection-final")).findFirst().orElseThrow();
            assertNotEquals(ChildState.READY, failedFinal.state());
            String selected = failedFinal.spec().localInvocation().outputs().get("selectedEntities").artifactId();
            String evidence = failedFinal.spec().localInvocation().outputs().get("selectionEvidence").artifactId();
            assertThrows(RuntimeException.class, () -> s.index.readSelectedPage(OWNER, selected, null, 500, ALLOW));
            assertThrows(RuntimeException.class, () -> s.index.readEvidencePage(OWNER, evidence, null, 500, ALLOW));
            assertThrows(RuntimeException.class, () -> s.index.seal(s.f.token, s.definition.collectionId(), failedFinal.spec().childId(), ALLOW));
        }
    }

    private static List<ChildRecord> localChildren(CampaignRunStore runs, RunToken token) {
        return runs.children(token).stream().filter(child -> child.spec().mode() == ChildMode.LOCAL).toList();
    }

    private static List<Result> readAll(CampaignDeclineSelectionStore store, String artifact, boolean selected, int size) {
        List<Result> result = new ArrayList<>();
        String cursor = null;
        do {
            var page = selected ? store.readSelectedPage(OWNER, artifact, cursor, size, ALLOW)
                    : store.readEvidencePage(OWNER, artifact, cursor, size, ALLOW);
            assertTrue(page.rows().size() <= size);
            assertFalse(page.rows().isEmpty(), "A nonterminal cursor cannot fabricate an empty page");
            result.addAll(page.rows()); cursor = page.nextCursor();
            assertTrue(result.size() <= 501, "Cursor must advance without returning duplicate candidates");
        } while (cursor != null);
        return result;
    }

    private static DeclineSelectionPublisher publisher(Fixture f, CampaignRunStore runs,
            CampaignDeclineSelectionStore index, ArtifactAuthorizer authorizer) {
        var coverage = new CampaignParentCoverage(runs, new JdbcCampaignScopeStore(f.jdbc, f.transactions, CLOCK, runs),
                new JdbcCampaignStatisticsResultStore(f.jdbc, f.transactions, CLOCK));
        return new DeclineSelectionPublisher(runs, new CampaignObservedLinkComparison(coverage), index, authorizer);
    }

    private static final class Scenario implements AutoCloseable {
        final Fixture f = new Fixture();
        final CampaignStepStore steps;
        final StepPermit permit;
        final CampaignStepExecution context;
        final CampaignDeclineSelectionStore index;
        final DeclineSelectionPublisher publisher;
        final Definition definition;
        final Slot[][] slots;

        Scenario(int count, LongBinaryOperator pv, java.util.function.BiConsumer<Integer, Map<String, Object>> metadata) throws Exception {
            String scopeId = f.publishScope(count);
            int shards = (count + 499) / 500;
            slots = new Slot[2][shards];
            for (int shard = 0; shard < shards; shard++) {
                var scope = f.scopes.shard(OWNER, scopeId, shard, ALLOW);
                for (int period = 0; period < 2; period++) {
                    int currentPeriod = period;
                    slots[period][shard] = f.publish("input-" + period + "-" + shard, scope, period, "VALID", pv,
                            meta -> metadata.accept(currentPeriod, meta));
                }
            }
            String scopeRef = f.runs.inspectArtifact(OWNER, scopeId, ALLOW).ref().scopeRef();
            definition = new Definition("decline-collection", scopeId, scopeRef, "baseline-target", CampaignLinkComparability.Metric.PV, shards, count);
            steps = new JdbcCampaignStepStore(f.jdbc, f.transactions, CLOCK);
            steps.initialize(f.token, List.of(new StepSpec(STEP.stepId(), FrozenCampaignRun.encode(STEP), List.of(), OUTPUTS, OUTPUTS)));
            permit = steps.beginStep(f.token, STEP.stepId());
            context = new CampaignStepExecution(STEP, List.of(), null, permit, f.runs, steps, () -> true);
            index = new JdbcCampaignDeclineSelectionStore(f.jdbc, f.transactions, CLOCK, f.runs);
            publisher = publisher(f, f.runs, index, ALLOW);
        }

        Receipt shard(int index, String previous, boolean missing) throws Exception {
            return publisher.publishShard(context, f.token, definition, PERIODS,
                    (period, shard) -> missing && period == 0 ? null : slots[period][shard], index, previous);
        }

        @Override public void close() { context.close(); steps.callbackExited(permit); }
    }
}
