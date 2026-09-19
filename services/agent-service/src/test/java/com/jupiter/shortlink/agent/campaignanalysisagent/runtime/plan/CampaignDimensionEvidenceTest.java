package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DimensionEvidenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Slot;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Evidence and LOCAL publication only; no claim that a multi-dimensional Skill has been wired. */
class CampaignDimensionEvidenceTest {
    @Test
    void actualTwoPeriodJointPagesPreserveStatesIndependentSummaryUvAndFullObservedBucketUnion() throws Exception {
        try (var f = new DimensionEvidenceFixture()) {
            Slot baseline = f.publish("baseline", 0, "VALID"), target = f.publish("target", 1, "VALID");
            var verified = f.verifier.verify(OWNER, baseline, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW);
            assertEquals(2, verified.pageCount()); assertEquals(501, verified.totalRows());
            List<JsonNode> sourceRows = new ArrayList<>(f.verifier.readRows(OWNER, verified, 0, ALLOW));
            sourceRows.addAll(f.verifier.readRows(OWNER, verified, 1, ALLOW));
            assertEquals(501, sourceRows.size());
            JsonNode raw = JSON.readTree(f.base.results.readPage(OWNER, baseline.artifactId(), 0, ALLOW));
            assertEquals(2, raw.path("metrics").path("requested").path("uv").asLong());
            assertEquals(501, sourceRows.stream().mapToLong(row -> row.path("uv").asLong()).sum());
            assertEquals("UNKNOWN", raw.path("meta").path("collectionQuality").path("status").asText());

            var publisher = new DimensionChangePublisher(f.base.runs, f.selectedScopes, f.verifier, ALLOW);
            var definition = new DimensionChangePublisher.Definition("dimension-changes", f.selectedScopeId,
                    "baseline-target", DIMENSIONS, FILTERS);
            List<ArtifactRef> pages = new ArrayList<>();
            List<JsonNode> union = new ArrayList<>();
            String previous = null;
            for (int side = 0; side < 2; side++) for (int page = 0; page < 2; page++) {
                ArtifactRef ref = publisher.publishPage(f.context, f.base.token, definition, baseline, target, 0, side, page, previous);
                pages.add(ref);
                Artifact artifact = f.base.runs.readArtifact(OWNER, ref.artifactId(), ALLOW);
                JsonNode payload = JSON.readTree(artifact.payloadJson());
                assertEquals("DimensionChangePageArtifact", ref.type());
                assertEquals("campaign.dimension-change-page/v1", ref.schemaVersion());
                assertEquals("SHARD_COHORT", payload.path("analysisUnit").asText());
                assertEquals("OBSERVED_ONLY", payload.path("interpretation").asText());
                assertEquals(507, payload.path("cohortSummary").path("baseline").path("pv").asLong());
                assertEquals(505, payload.path("cohortSummary").path("target").path("pv").asLong());
                assertEquals(2, payload.path("cohortSummary").path("baseline").path("uv").asLong());
                assertEquals(2, payload.path("cohortSummary").path("target").path("uv").asLong());
                assertEquals("UNKNOWN", payload.path("quality").path("baseline").path("collectionQuality").path("status").asText());
                assertEquals("UNKNOWN", payload.path("quality").path("target").path("collectionQuality").path("status").asText());
                assertEquals("FILTERED_FULL_WINDOW", payload.path("quality").path("baseline").path("dimensionQualityScope").asText());
                assertEquals(PERIODS.get(1).endExclusive(), payload.path("quality").path("target").path("requestedEnd").asLong());
                JsonNode comparison = payload.path("comparability").path("PV");
                assertEquals("VERIFIED", comparison.path("comparability").asText());
                assertTrue(comparison.path("reasonCodes").toString().contains("BASELINE_COLLECTION_COMPLETENESS_UNVERIFIED"));
                assertTrue(comparison.path("reasonCodes").toString().contains("TARGET_COLLECTION_COMPLETENESS_UNVERIFIED"));
                assertTrue(payload.path("rows").isArray());
                if (side == 1 && page == 0) assertEquals(0, payload.path("rows").size(), "Shared buckets were emitted only once from baseline pages");
                payload.path("rows").forEach(union::add);
                previous = ref.artifactId();
            }
            assertEquals(502, union.size());
            assertEquals(500, union.stream().filter(row -> "BOTH".equals(row.path("presence").asText())).count());
            assertEquals(1, union.stream().filter(row -> "BASELINE_ONLY".equals(row.path("presence").asText())).count());
            assertEquals(1, union.stream().filter(row -> "TARGET_ONLY".equals(row.path("presence").asText())).count());
            JsonNode unknown = union.stream().filter(row -> "UNKNOWN".equals(row.path("dimensions").path("province").path("state").asText())).findFirst().orElseThrow();
            assertTrue(unknown.path("dimensions").path("province").path("value").isNull());
            assertEquals("UNKNOWN", unknown.path("dimensions").path("device").path("state").asText());
            JsonNode notApplicable = union.stream().filter(row -> "NOT_APPLICABLE".equals(row.path("dimensions").path("province").path("state").asText())).findFirst().orElseThrow();
            assertTrue(notApplicable.path("dimensions").path("province").path("value").isNull());
            assertEquals("KNOWN", notApplicable.path("dimensions").path("device").path("state").asText());
            JsonNode baselineOnly = union.stream().filter(row -> "BASELINE_ONLY".equals(row.path("presence").asText())).findFirst().orElseThrow();
            assertEquals(-4, baselineOnly.path("pvDelta").asLong());
            assertEquals(0, baselineOnly.path("target").path("pv").asLong());
            JsonNode targetOnly = union.stream().filter(row -> "TARGET_ONLY".equals(row.path("presence").asText())).findFirst().orElseThrow();
            assertEquals(2, targetOnly.path("pvDelta").asLong());
            assertTrue(targetOnly.path("pvRelativeChange").isNull(), "A zero baseline does not invent a percentage");
            assertEquals(2.0 / 505, targetOnly.path("pvShareChange").asDouble(), 1e-15);

            ArtifactRef result = publisher.finish(f.context, f.base.token, definition, previous);
            Artifact original = f.base.runs.readArtifact(OWNER, result.artifactId(), ALLOW);
            JsonNode manifest = JSON.readTree(original.payloadJson());
            assertEquals("DimensionChangeArtifact", result.type());
            assertEquals("campaign.dimension-change/v1", result.schemaVersion());
            assertEquals(4, manifest.path("pageCount").asInt());
            assertEquals(502, manifest.path("comparisonRows").asLong());
            assertEquals(2, manifest.path("memberCount").asLong());
            assertEquals(1, manifest.path("coveredCohorts").asLong());
            assertEquals("SHARD_COHORT", manifest.path("analysisUnit").asText());
            assertEquals("OBSERVED", manifest.path("evidenceDisposition").asText());
            assertNull(manifest.findValue("rows")); assertTrue(original.payloadJson().length() < 8192);
            assertEquals(Instant.ofEpochMilli(EXPIRY), result.expiresAt());
            assertEquals(result, publisher.finish(f.context, f.base.token, definition, previous));
            Set<String> actionIds = new HashSet<>(f.base.jdbc.queryForList("SELECT action_id FROM campaign_action_ledger WHERE run_id=? AND step_id=?",
                    String.class, f.base.token.definition().runId(), DIMENSION.stepId()));
            var local = f.base.runs.children(f.base.token).stream().filter(child -> actionIds.contains(child.spec().actionId())).toList();
            assertEquals(5, local.size());
            assertTrue(local.stream().allMatch(child -> child.spec().mode() == ChildMode.LOCAL && child.state() == ChildState.READY && child.attemptVersion() == 1));

            Slot empty = f.publish("empty-baseline", 0, "EMPTY");
            var emptyQuery = f.verifier.verify(OWNER, empty, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW);
            assertEquals(0, emptyQuery.pageCount()); assertEquals(0, emptyQuery.totalRows());
            assertTrue(f.verifier.readRows(OWNER, emptyQuery, 0, ALLOW).isEmpty(), "A zero-row remote query still has a real readable page zero");
            var emptyDefinition = new DimensionChangePublisher.Definition("empty-baseline-changes", f.selectedScopeId,
                    "baseline-target", DIMENSIONS, FILTERS);
            ArtifactRef emptyPage = publisher.publishPage(f.context, f.base.token, emptyDefinition, empty, target, 0, 0, 0, null);
            assertEquals(0, JSON.readTree(f.base.runs.readArtifact(OWNER, emptyPage.artifactId(), ALLOW).payloadJson()).path("rows").size());
            String emptyHead = emptyPage.artifactId();
            long targetRows = 0;
            for (int page = 0; page < 2; page++) {
                var ref = publisher.publishPage(f.context, f.base.token, emptyDefinition, empty, target, 0, 1, page, emptyHead);
                JsonNode payload = JSON.readTree(f.base.runs.readArtifact(OWNER, ref.artifactId(), ALLOW).payloadJson());
                for (JsonNode row : payload.path("rows")) {
                    targetRows++;
                    assertEquals("TARGET_ONLY", row.path("presence").asText());
                    assertEquals(0, row.path("baseline").path("pv").asLong());
                    assertEquals(0, row.path("baseline").path("uv").asLong());
                    assertEquals(row.path("target").path("pv").asLong(), row.path("pvDelta").asLong());
                    assertTrue(row.path("pvRelativeChange").isNull());
                }
                emptyHead = ref.artifactId();
            }
            assertEquals(501, targetRows);
            var zeroResult = publisher.finish(f.context, f.base.token, emptyDefinition, emptyHead);
            JsonNode zeroManifest = JSON.readTree(f.base.runs.readArtifact(OWNER, zeroResult.artifactId(), ALLOW).payloadJson());
            assertEquals(3, zeroManifest.path("pageCount").asInt());
            assertEquals(501, zeroManifest.path("comparisonRows").asLong());
            assertEquals("OBSERVED", zeroManifest.path("evidenceDisposition").asText());
        }
    }

    @Test
    void onlyReadyOriginalSourcesWithExactPeriodScopeAndCompleteConsistentJointBucketsAreEvidence() throws Exception {
        try (var f = new DimensionEvidenceFixture()) {
            Slot baseline = f.publish("baseline", 0, "VALID"), target = f.publish("target", 1, "VALID");
            assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER,
                    new Slot(f.base.token, target.childId(), baseline.artifactId()), f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW));
            assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER, baseline, f.scope, PERIODS.get(1), DIMENSIONS, FILTERS, ALLOW));
            assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER, baseline, f.originalScope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW));
            assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER, baseline, f.scope, PERIODS.get(0), List.of("province", "browser"), FILTERS, ALLOW));
            assertThrows(SecurityException.class, () -> f.verifier.verify(OWNER, baseline, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, (caller, artifact) -> false));
            var verified = f.verifier.verify(OWNER, baseline, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW);
            assertThrows(SecurityException.class, () -> f.verifier.readRows(OWNER, verified, 1, (caller, artifact) -> false));
            Slot waiting = f.pending("still-waiting", 0);
            assertEquals(ChildState.WAITING, f.base.runs.child(f.base.token, waiting.childId()).orElseThrow().state());
            assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER, waiting, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW));
            for (String variant : List.of("DENOMINATOR", "SUMMARY_PV", "DUPLICATE", "STATE")) {
                Slot invalid = f.publish("invalid-" + variant.toLowerCase(Locale.ROOT), 0, variant);
                assertEquals(ChildState.READY, f.base.runs.child(f.base.token, invalid.childId()).orElseThrow().state());
                assertThrows(IllegalStateException.class, () -> f.verifier.verify(OWNER, invalid, f.scope, PERIODS.get(0), DIMENSIONS, FILTERS, ALLOW), variant);
            }
            assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_artifact WHERE artifact_type IN ('DimensionChangePageArtifact','DimensionChangeArtifact')", Integer.class));

            Slot changedMetric = f.publish("target-new-metric", 1, "METRIC_VERSION");
            var publisher = new DimensionChangePublisher(f.base.runs, f.selectedScopes, f.verifier, ALLOW);
            var definition = new DimensionChangePublisher.Definition("different-metric-changes", f.selectedScopeId,
                    "baseline-target", DIMENSIONS, FILTERS);
            var ref = publisher.publishPage(f.context, f.base.token, definition, baseline, changedMetric, 0, 0, 0, null);
            JsonNode payload = JSON.readTree(f.base.runs.readArtifact(OWNER, ref.artifactId(), ALLOW).payloadJson());
            assertEquals("INCOMPATIBLE", payload.path("comparability").path("PV").path("comparability").asText());
            assertTrue(payload.path("comparability").path("PV").path("reasonCodes").toString().contains("METRIC_VERSION_MISMATCH"));
            assertEquals("click-v2", payload.path("quality").path("target").path("metricVersion").asText());
            assertEquals(500, payload.path("rows").size());
            for (JsonNode row : payload.path("rows")) {
                assertTrue(row.path("pvRelativeChange").isNull(), "Different metric definitions cannot produce a comparison percentage");
                assertTrue(row.path("pvDelta").isIntegralNumber(), "Observed counts remain available without claiming compatibility");
            }
        }
    }
}
