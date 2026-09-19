package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.*;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.util.*;

/** Actual received StatisticsJobPages and selected-scope LOCAL artifacts, with synthetic wire data. */
final class DimensionEvidenceFixture implements AutoCloseable {
    static final List<String> DIMENSIONS = List.of("province", "device");
    static final List<Map<String, Object>> FILTERS = List.of();
    static final PlanSpec.Step SELECT = step("select", List.of());
    static final PlanSpec.Step SCOPE = step("selected-scope", List.of("select"));
    static final PlanSpec.Step DIMENSION = step("dimension-evidence", List.of("selected-scope"));
    final Fixture base = new Fixture();
    final CampaignStepStore steps = new JdbcCampaignStepStore(base.jdbc, base.transactions, CLOCK);
    final CampaignSelectedScope selectedScopes;
    final String selectedScopeId;
    final FrozenQueryScope scope;
    final FrozenQueryScope originalScope;
    final CampaignDimensionEvidence verifier;
    final StepPermit permit;
    final CampaignStepExecution context;

    DimensionEvidenceFixture() throws Exception {
        String original = base.publishScope(3);
        originalScope = base.scopes.shard(OWNER, original, 0, ALLOW);
        Slot[] sources = new Slot[2];
        for (int period = 0; period < 2; period++)
            sources[period] = base.publish("selection-source-" + period, originalScope, period, "VALID",
                    (id, side) -> id == 1 ? 0 : side == 0 ? id + 251 : id + 250, meta -> {});
        steps.initialize(base.token, List.of(spec(SELECT, Set.of("selectedEntities", "selectionEvidence")),
                spec(SCOPE, Set.of("selectedScope")), spec(DIMENSION, Set.of("dimensionPage", "dimensionChanges"))));
        var index = new JdbcCampaignDeclineSelectionStore(base.jdbc, base.transactions, CLOCK, base.runs);
        var selection = new DeclineSelectionPublisher(base.runs,
                new CampaignObservedLinkComparison(new CampaignParentCoverage(base.runs, base.scopes, base.results)), index, ALLOW);
        var selectedPermit = steps.beginStep(base.token, SELECT.stepId());
        CampaignDeclineSelectionStore.Receipt selected;
        try (var call = context(SELECT, selectedPermit)) {
            var definition = new DeclineSelectionPage.Definition("dimension-source-selection", original,
                    originalScope.parentScopeRef(), "baseline-target", CampaignLinkComparability.Metric.PV, 1, 3);
            var page = selection.publishShard(call, base.token, definition, PERIODS, (period, shard) -> sources[period], 0, null);
            selected = selection.finish(call, base.token, page.chainArtifactId());
            steps.settle(selectedPermit, StepStatus.SUCCEEDED,
                    Map.of("selectedEntities", selected.selectedArtifactId(), "selectionEvidence", selected.evidenceArtifactId()), null, ALLOW);
        } finally { steps.callbackExited(selectedPermit); }
        selectedScopes = new CampaignSelectedScope(base.runs, index, ALLOW);
        var scopePermit = steps.beginStep(base.token, SCOPE.stepId());
        try (var call = context(SCOPE, scopePermit)) {
            var artifact = selectedScopes.publish(call, base.token, selected.selectedArtifactId(), selected.evidenceArtifactId(), PERIODS);
            selectedScopeId = artifact.artifactId();
            steps.settle(scopePermit, StepStatus.SUCCEEDED, Map.of("selectedScope", selectedScopeId), null, ALLOW);
        } finally { steps.callbackExited(scopePermit); }
        scope = selectedScopes.shard(OWNER, selectedScopeId, 0);
        assertEquals(List.of(2L, 3L), scope.linkIds());
        verifier = new CampaignDimensionEvidence(base.runs, base.results);
        permit = steps.beginStep(base.token, DIMENSION.stepId());
        context = context(DIMENSION, permit);
    }

    Slot publish(String key, int side, String variant) throws Exception {
        Slot slot = pending(key, side);
        var child = base.runs.child(base.token, slot.childId()).orElseThrow();
        var receipt = base.runs.beginReconciliation(base.token, slot.childId());
        try {
            Period period = PERIODS.get(side);
            boolean empty = "EMPTY".equals(variant);
            List<Map<String, Object>> rows = empty ? List.of() : rows(side);
            if ("DUPLICATE".equals(variant)) rows.get(500).put("dimensions", rows.get(0).get("dimensions"));
            if ("STATE".equals(variant)) rows.get(498).put("dimensions", Map.of("province", cell(null, "BROKEN"), "device", cell(null, "UNKNOWN")));
            long total = empty ? 0 : side == 0 ? 507 : 505;
            int pageCount = (rows.size() + 499) / 500;
            var summary = new LinkedHashMap<String, Object>();
            summary.put("pv", "SUMMARY_PV".equals(variant) ? total + 1 : total);
            summary.put("uv", empty ? 0 : 2); summary.put("uip", empty ? 0 : 2);
            summary.put("ratioDenominator", "DENOMINATOR".equals(variant) || "SUMMARY_PV".equals(variant) ? total + 1 : total);
            summary.put("window", "requested"); summary.put("startInclusive", period.startInclusive()); summary.put("endExclusive", period.endExclusive());
            summary.put("dimensionQuality", dimensionQuality(total));
            if ("SUMMARY_PV".equals(variant)) for (var row : rows) row.put("pvRatio", ((Number) row.get("pv")).doubleValue() / (total + 1));
            var protocol = new StatisticsJobResultProtocol(child);
            var status = new StatisticsJobResultProtocol.Status(child.jobId(), "SUCCEEDED", rows.size(), pageCount, EXPIRY, null);
            base.results.initialize(receipt, new CampaignStatisticsResultStore.ReceiptSpec(child.jobId(), child.spec().wire().hash(),
                    slot.artifactId(), scope.parentScopeRef(), period.periodsRef(), rows.size(), pageCount, EXPIRY));
            for (int page = 0; page < Math.max(1, pageCount); page++) {
                Map<String, Object> meta = metadata(child.jobId(), side, page, total, rows.size(), pageCount);
                if ("METRIC_VERSION".equals(variant)) meta.put("metricVersion", "click-v2");
                var data = Map.<String, Object>of("items", rows.subList(page * 500, Math.min(rows.size(), (page + 1) * 500)),
                        "metrics", Map.of("requested", summary), "meta", meta);
                base.results.append(receipt, protocol.page(status, data, page));
            }
            base.results.publish(receipt);
        } finally { base.runs.callbackExited(receipt); }
        assertEquals(ChildState.READY, base.runs.child(base.token, slot.childId()).orElseThrow().state());
        assertTrue(base.results.receipt(base.token, slot.childId()).orElseThrow().published());
        return slot;
    }

    Slot pending(String key, int side) throws Exception {
        Period period = PERIODS.get(side);
        String actionId = "dimension-action-" + key, childId = "dimension-child-" + key;
        base.runs.prepareAction(base.token, new ActionSpec(actionId, "statistics-source-" + key, "TOOL", "dimension_job", "1", "{}"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "dimension-request-" + key); request.put("gid", "group-a");
        request.put("startDate", period.startDate()); request.put("endDate", period.endDate());
        request.put("queryKind", "DIMENSION_BREAKDOWN"); request.put("dimensions", DIMENSIONS); request.put("filters", FILTERS);
        request.put("scope", scope.asMap());
        base.runs.prepareChild(base.token, new ChildSpec(childId, actionId, ChildMode.ASYNC, request.get("requestId").toString(),
                new WireRequest("POST", StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH, JSON.writeValueAsString(request))));
        var dispatch = base.runs.beginDispatch(base.token, childId);
        try { base.runs.recordWaiting(dispatch, "dimension-job-" + key); }
        finally { base.runs.callbackExited(dispatch); }
        return new Slot(base.token, childId, "dimension-result-" + key);
    }

    private static List<Map<String, Object>> rows(int side) {
        long total = side == 0 ? 507 : 505;
        List<Map<String, Object>> result = new ArrayList<>();
        // Raw device labels are deliberately not normalized: the actual protocol treats them as exact strings.
        for (int i = 0; i < 498; i++) result.add(row("浙江", "KNOWN", String.format(Locale.ROOT, "device-%03d", i), "KNOWN", 1, total));
        result.add(row(null, "UNKNOWN", null, "UNKNOWN", 2, total));
        result.add(row(null, "NOT_APPLICABLE", "PC", "KNOWN", 3, total));
        result.add(row(side == 0 ? "广东" : "江苏", "KNOWN", "Mobile", "KNOWN", side == 0 ? 4 : 2, total));
        return result;
    }

    private static Map<String, Object> row(String province, String provinceState, String device, String deviceState, long pv, long total) {
        var row = new LinkedHashMap<String, Object>();
        row.put("dimensions", Map.of("province", cell(province, provinceState), "device", cell(device, deviceState)));
        row.put("pv", pv); row.put("uv", 1); row.put("uip", 1); row.put("pvRatio", (double) pv / total);
        return row;
    }
    private static Map<String, Object> cell(String value, String state) {
        var result = new LinkedHashMap<String, Object>(); result.put("value", value); result.put("state", state); return result;
    }
    private static Map<String, Object> dimensionQuality(long total) {
        if (total == 0) return Map.of("province", quality(0, 0, 0, 0, "CN_PROVINCE"),
                "device", quality(0, 0, 0, 0, "DEVICE"));
        return Map.of("province", quality(total - 5, 2, total - 3, 3, "CN_PROVINCE"),
                "device", quality(total - 2, 2, total, 0, "DEVICE"));
    }
    private static Map<String, Object> quality(long known, long unknown, long eligible, long notApplicable, String semantic) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("status", eligible == 0 ? "EMPTY" : known == 0 ? "UNKNOWN" : unknown > 0 ? "PARTIAL" : "AVAILABLE");
        quality.put("knownCount", known); quality.put("unknownCount", unknown); quality.put("eligibleCount", eligible);
        quality.put("notApplicableCount", notApplicable); quality.put("coverage", eligible == 0 ? null : (double) known / eligible);
        quality.put("semantic", semantic); quality.put("reasonCounts", unknown == 0 ? Map.of() : Map.of("UNKNOWN_VALUE", unknown));
        return quality;
    }
    private Map<String, Object> metadata(String job, int side, int page, long total, int rowCount, int pageCount) {
        Period period = PERIODS.get(side);
        var meta = new LinkedHashMap<String, Object>();
        meta.put("snapshotId", job); meta.put("queryKind", "DIMENSION_BREAKDOWN"); meta.put("gid", "group-a");
        meta.put("linkIds", scope.linkIds()); meta.put("scopeProof", scope.proof("b".repeat(64))); meta.put("groupScopeComplete", false);
        meta.put("requestedStart", period.startInclusive()); meta.put("requestedEnd", period.endExclusive()); meta.put("effectiveEnd", period.endExclusive());
        meta.put("businessTimezone", "Asia/Shanghai"); meta.put("snapshotCreatedAt", CLOCK.millis()); meta.put("snapshotExpiresAt", EXPIRY);
        meta.put("metricVersion", "click-v1"); meta.put("recoveryEpoch", "epoch-1");
        String hash = CampaignRunStore.sha256(job);
        meta.put("sourceCut", Map.of("manifestSelectionHash", hash)); meta.put("manifestVersion", Map.of("selectionHash", hash));
        meta.put("dimensions", DIMENSIONS); meta.put("filters", FILTERS); meta.put("dimensionQualityScope", "FILTERED_FULL_WINDOW");
        meta.put("dimensionQuality", dimensionQuality(total)); meta.put("aggregationLevel", "DIMENSION_BREAKDOWN");
        meta.put("resultComplete", true); meta.put("truncated", false); meta.put("totalRows", rowCount);
        meta.put("pageIndex", page); meta.put("nextPageIndex", page + 1 < pageCount ? page + 1 : null);
        meta.put("availability", "AVAILABLE"); meta.put("completeness", "COMPLETE"); meta.put("freshness", "FRESH"); meta.put("provisional", false);
        meta.put("collectionQuality", Map.of("status", "UNKNOWN")); meta.put("missingMetrics", List.of("province", "device", "producerCollectionCompleteness"));
        var approximation = new LinkedHashMap<String, Object>();
        approximation.put("pv", Map.of("type", "EXACT", "algorithm", "COUNT", "version", "v1"));
        approximation.put("pvRatio", approximation.get("pv"));
        approximation.put("uv", Map.of("type", "APPROXIMATE", "algorithm", "HLL", "version", "v1"));
        approximation.put("uip", approximation.get("uv")); meta.put("approximation", approximation);
        return meta;
    }

    private CampaignStepExecution context(PlanSpec.Step step, StepPermit permit) {
        return new CampaignStepExecution(step, List.of(), null, permit, base.runs, steps, () -> true);
    }
    private static PlanSpec.Step step(String id, List<String> dependencies) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED,
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.SKILL, id, "1"), null, dependencies, Map.of(), Map.of(), "dimension-component/v1");
    }
    private static StepSpec spec(PlanSpec.Step step, Set<String> outputs) {
        return new StepSpec(step.stepId(), FrozenCampaignRun.encode(step), step.dependsOn(), outputs, outputs);
    }
    @Override public void close() { context.close(); steps.callbackExited(permit); }
}
