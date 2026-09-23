package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Capability;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Criterion;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Parameters;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Policy;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.ReceiptSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsPlanFactory;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.StatisticsJobFixedExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CampaignStatisticsDurableProjectorTest {
    private static final Caller OWNER = new Caller("1001", "alice", 7);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long EXPIRES = day("2026-10-01");
    private static final ArtifactAuthorizer ALLOW = (current, artifact) -> OWNER.equals(current);

    @Test
    void ranksCompletePublishedEvidenceAndRejectsChangedPageOrCaller() throws Exception {
        var prepared = factory().ranking(OWNER, "session-1", "rank-1", "alpha",
                "2026-09-01", "2026-09-02", "pv", 2);
        Fixture fixture = new Fixture(prepared.definition());
        fixture.add("collect-1", 2, List.of(
                Map.of("linkId", 1L, "fullShortUrl", "short.test/a", "pv", 10, "uv", 8, "uip", 7),
                Map.of("linkId", 2L, "fullShortUrl", "short.test/b", "pv", 5, "uv", 4, "uip", 3)), 15);
        var projector = new CampaignStatisticsDurableProjector(fixture.runs, fixture.results);
        var ranked = projector.rank(OWNER, fixture.token, "collect-1", "alpha",
                "2026-09-01", "2026-09-02", "pv", 2, ALLOW);
        assertThat(ranked).containsEntry("status", "READY");
        assertThat((List<?>) ranked.get("rows")).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) ranked.get("rows")).get(0)).get("linkId")).isEqualTo(1);
        verify(fixture.results).readPage(OWNER, fixture.artifacts.get("collect-1"), 0, ALLOW);

        when(fixture.results.readPage(OWNER, fixture.artifacts.get("collect-1"), 0, ALLOW))
                .thenReturn(fixture.pages.get("collect-1").replace("short.test/a", "short.test/z"));
        assertThatThrownBy(() -> projector.rank(OWNER, fixture.token, "collect-1", "alpha",
                "2026-09-01", "2026-09-02", "pv", 2, ALLOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("STATISTICS_DURABLE_EVIDENCE_INVALID");
        assertThatThrownBy(() -> projector.rank(new Caller("1001", "mallory", 7), fixture.token,
                "collect-1", "alpha", "2026-09-01", "2026-09-02", "pv", 2, ALLOW))
                .isInstanceOf(SecurityException.class).hasMessage("LEDGER_SUBJECT_MISMATCH");
        when(fixture.runs.loadRun(OWNER, fixture.token.definition().runId())).thenReturn(Optional.of(
                new RunRecord(fixture.token.definition(), RunStatus.CANCELLED, 1, "advance-1")));
        assertThatThrownBy(() -> projector.rank(OWNER, fixture.token, "collect-1", "alpha",
                "2026-09-01", "2026-09-02", "pv", 2, ALLOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("STATISTICS_DURABLE_EVIDENCE_INVALID");
    }

    @Test
    void comparisonUsesEveryBoundStepAndRejectsChangedBaselinePlan() throws Exception {
        var scopes = List.<Map<String, Object>>of(Map.of("gid", "alpha"), Map.of("gid", "beta"));
        var periods = List.<Map<String, Object>>of(Map.of("startDate", "2026-09-01", "endDate", "2026-09-02"));
        var prepared = factory().comparison(OWNER, "session-1", "compare-1", scopes, periods);
        Fixture fixture = new Fixture(prepared.definition());
        fixture.add("collect-1", 0, List.of(), 15);
        fixture.add("collect-2", 0, List.of(), 5);
        var projector = new CampaignStatisticsDurableProjector(fixture.runs, fixture.results);
        var plan = CampaignStatisticsQueryPlan.create(scopes, periods);
        Map<String, String> steps = Map.of(plan.queries().get(0).key(), "collect-1",
                plan.queries().get(1).key(), "collect-2");
        var compared = projector.compare(OWNER, fixture.token, plan, steps, ALLOW,
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZONE));
        assertThat(compared).containsEntry("status", "READY");
        assertThat((List<?>) compared.get("comparisons")).hasSize(3);
        var changed = CampaignStatisticsQueryPlan.create(List.of(scopes.get(1), scopes.get(0)), periods);
        assertThatThrownBy(() -> projector.compare(OWNER, fixture.token, changed, steps, ALLOW, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessage("STATISTICS_DURABLE_EVIDENCE_INVALID");
    }

    @Test
    void comparisonReadsBothPublishedPagesAndRejectsTamperedSecondPage() throws Exception {
        var scopes = List.<Map<String, Object>>of(Map.of("gid", "alpha"), Map.of("gid", "beta"));
        var periods = List.<Map<String, Object>>of(Map.of("startDate", "2026-09-01", "endDate", "2026-09-02"));
        var prepared = factory().comparison(OWNER, "session-1", "compare-pages", scopes, periods);
        Fixture fixture = new Fixture(prepared.definition());
        List<Map<String, Object>> items = IntStream.range(0, 501)
                .mapToObj(index -> Map.<String, Object>of("linkId", 1L, "seq", index)).toList();
        fixture.add("collect-1", 501, items, 15);
        fixture.add("collect-2", 0, List.of(), 5);
        var plan = CampaignStatisticsQueryPlan.create(scopes, periods);
        Map<String, String> steps = Map.of(plan.queries().get(0).key(), "collect-1",
                plan.queries().get(1).key(), "collect-2");
        var projector = new CampaignStatisticsDurableProjector(fixture.runs, fixture.results);
        var compared = projector.compare(OWNER, fixture.token, plan, steps, ALLOW,
                Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZONE));
        assertThat(compared).containsEntry("status", "READY");
        String artifactId = fixture.artifacts.get("collect-1");
        verify(fixture.results).readPage(OWNER, artifactId, 0, ALLOW);
        verify(fixture.results).readPage(OWNER, artifactId, 1, ALLOW);

        String second = fixture.secondPages.get("collect-1");
        String tampered = second.replace("\"seq\":500", "\"seq\":999");
        assertThat(tampered).isNotEqualTo(second);
        when(fixture.results.readPage(OWNER, artifactId, 1, ALLOW)).thenReturn(tampered);
        assertThatThrownBy(() -> projector.compare(OWNER, fixture.token, plan, steps, ALLOW, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessage("STATISTICS_DURABLE_EVIDENCE_INVALID");
    }

    private static CampaignStatisticsPlanFactory factory() {
        CapabilityCatalog catalog = new CapabilityCatalog() {
            @Override public String version() { return "statistics-test/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
                return StatisticsJobFixedExecutor.REF.equals(executor)
                        ? Optional.of(StatisticsJobFixedExecutor.capability()) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return "statistics-delivery".equals(ref) && "1".equals(version)
                        ? Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY,
                        Parameters.none(), Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE))) : Optional.empty();
            }
        };
        return new CampaignStatisticsPlanFactory(catalog, "statistics-delivery", "1");
    }

    private static final class Fixture {
        final CampaignRunStore runs = mock(CampaignRunStore.class);
        final CampaignStatisticsResultStore results = mock(CampaignStatisticsResultStore.class);
        final RunToken token;
        final Map<String, String> artifacts = new LinkedHashMap<>();
        final Map<String, String> pages = new LinkedHashMap<>();
        final Map<String, String> secondPages = new LinkedHashMap<>();

        Fixture(RunDefinition definition) {
            token = new RunToken(definition, 1, "advance-1");
            when(runs.loadRun(OWNER, definition.runId())).thenReturn(Optional.of(
                    new RunRecord(definition, RunStatus.ACTIVE, 1, "advance-1")));
        }

        void add(String stepId, int totalRows, List<Map<String, Object>> items, long pv) throws Exception {
            var bound = FrozenStatisticsJobQuery.resolve(token.definition(), StatisticsJobFixedExecutor.REF).get(stepId);
            String job = "job-" + stepId;
            String artifactId = bound.target().artifactId();
            artifacts.put(stepId, artifactId);
            ChildRecord child = new ChildRecord(bound.child(), ChildState.READY, job, artifactId,
                    "attempt-1", 1, DispatchPurpose.FRESH, false, null);
            when(runs.child(token, bound.child().childId())).thenReturn(Optional.of(child));
            String kind = bound.request().get("queryKind").toString();
            long start = day(bound.request().get("startDate").toString());
            long end = day(LocalDate.parse(bound.request().get("endDate").toString()).plusDays(1).toString());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("queryKind", kind); snapshot.put("gid", bound.request().get("gid"));
            snapshot.put("linkIds", "LINK_METRICS".equals(kind) ? List.of(1L, 2L) : List.of(1L));
            snapshot.put("snapshotId", job); snapshot.put("recoveryEpoch", "epoch-1");
            snapshot.put("metricVersion", "click-v1");
            snapshot.put("sourceCut", Map.of("manifestSelectionHash", "hash-1"));
            snapshot.put("manifestVersion", Map.of("selectionHash", "hash-1"));
            snapshot.put("requestedStart", start); snapshot.put("requestedEnd", end);
            snapshot.put("effectiveEnd", end); snapshot.put("businessTimezone", "Asia/Shanghai");
            snapshot.put("snapshotExpiresAt", EXPIRES); snapshot.put("groupScopeComplete", true);
            snapshot.put("totalRows", totalRows); snapshot.put("availability", "AVAILABLE");
            snapshot.put("completeness", "COMPLETE"); snapshot.put("freshness", "FRESH");
            snapshot.put("collectionQuality", Map.of("status", "COMPLETE"));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("window", "requested"); summary.put("startInclusive", start); summary.put("endExclusive", end);
            summary.put("pv", pv); summary.put("uv", pv); summary.put("uip", pv);
            Map<String, Object> metrics = Map.of("requested", summary);
            int pageCount = (totalRows + 499) / 500;
            int requiredPages = Math.max(1, pageCount);
            List<String> pageBodies = new ArrayList<>();
            for (int index = 0; index < requiredPages; index++) {
                Map<String, Object> pageMeta = new LinkedHashMap<>(snapshot);
                pageMeta.put("pageIndex", index);
                pageMeta.put("nextPageIndex", index + 1 < requiredPages ? index + 1 : null);
                List<Map<String, Object>> currentItems = items.subList(index * 500,
                        Math.min(items.size(), (index + 1) * 500));
                pageBodies.add(JSON.writeValueAsString(Map.of("meta", pageMeta, "metrics", metrics,
                        "items", currentItems)));
            }
            pages.put(stepId, pageBodies.get(0));
            if (pageBodies.size() > 1) secondPages.put(stepId, pageBodies.get(1));
            ReceiptSpec spec = new ReceiptSpec(job, bound.child().wire().hash(), artifactId,
                    bound.scopeRef(), bound.periodsRef(), totalRows, pageCount, EXPIRES);
            String specHash = CampaignRunStore.sha256(JSON.writeValueAsString(spec));
            String chain = CampaignRunStore.sha256(CampaignStatisticsResultStore.SCHEMA_VERSION + ":" + specHash);
            for (int index = 0; index < pageBodies.size(); index++) {
                int rowCount = Math.min(500, Math.max(0, totalRows - index * 500));
                chain = CampaignRunStore.sha256(chain + ":" + index + ":"
                        + CampaignRunStore.sha256(pageBodies.get(index)) + ":" + rowCount);
            }
            Receipt receipt = new Receipt(spec, requiredPages, requiredPages, totalRows,
                    JSON.writeValueAsString(snapshot), JSON.writeValueAsString(metrics), chain, true);
            when(results.receipt(token, bound.child().childId())).thenReturn(Optional.of(receipt));
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("jobId", job); manifest.put("artifactId", artifactId);
            manifest.put("pageCount", spec.pageCount()); manifest.put("receivedPageCount", requiredPages);
            manifest.put("totalRows", totalRows); manifest.put("chainHash", chain);
            manifest.put("resultComplete", true); manifest.put("meta", snapshot); manifest.put("metrics", metrics);
            String body = JSON.writeValueAsString(manifest);
            var ref = new ArtifactRef(artifactId, CampaignStatisticsResultStore.ARTIFACT_TYPE,
                    CampaignStatisticsResultStore.SCHEMA_VERSION, CampaignRunStore.sha256(body),
                    bound.scopeRef(), bound.periodsRef(), Instant.ofEpochMilli(EXPIRES));
            var metadata = new ArtifactMetadata(ref, OWNER, token.definition().runId(), token.definition().planId(),
                    token.definition().revision(), bound.child().actionId(), bound.child().childId(),
                    StatisticsJobFixedExecutor.REF.version(), JSON.writeValueAsString(snapshot),
                    JSON.writeValueAsString(Map.of("jobId", job, "requestHash", spec.requestHash(),
                            "scopeMode", "CURRENT_QUERY")));
            when(runs.readArtifact(OWNER, artifactId, ALLOW)).thenReturn(new Artifact(metadata, body));
            when(runs.inspectArtifact(OWNER, artifactId, ALLOW)).thenReturn(metadata);
            for (int index = 0; index < pageBodies.size(); index++)
                when(results.readPage(OWNER, artifactId, index, ALLOW)).thenReturn(pageBodies.get(index));
        }
    }

    private static long day(String day) { return LocalDate.parse(day).atStartOfDay(ZONE).toInstant().toEpochMilli(); }
}
