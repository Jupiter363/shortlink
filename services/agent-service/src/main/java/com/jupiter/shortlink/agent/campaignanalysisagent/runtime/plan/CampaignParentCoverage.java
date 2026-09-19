package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultProtocol;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Checks two-period parent coverage from durable evidence; never sums UV/UIP or executes tools. */
public final class CampaignParentCoverage {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> SCOPE_FIELDS = Set.of("schemaVersion", "collectionId", "gid", "scopeRef", "memberHash",
            "enumerationVersion", "memberCount", "pageCount", "shardCount");
    private static final List<String> COUNTS = List.of("pv", "uv", "uip", "denied");
    private final CampaignRunStore runs;
    private final CampaignScopeStore scopes;
    private final CampaignStatisticsResultStore results;

    public CampaignParentCoverage(CampaignRunStore runs, CampaignScopeStore scopes, CampaignStatisticsResultStore results) {
        this.runs = Objects.requireNonNull(runs); this.scopes = Objects.requireNonNull(scopes); this.results = Objects.requireNonNull(results);
    }

    public record Period(String periodsRef, String startDate, String endDate, String timeZone) {
        public Period {
            if (periodsRef == null || periodsRef.isBlank() || periodsRef.length() > 256 || !"Asia/Shanghai".equals(timeZone))
                throw new IllegalArgumentException("COVERAGE_PERIOD_INVALID");
            LocalDate start = date(startDate), end = date(endDate);
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (start.isBefore(LocalDate.of(1970, 1, 1)) || days < 1 || days > 180)
                throw new IllegalArgumentException("COVERAGE_PERIOD_INVALID");
        }
        public long startInclusive() { return date(startDate).atStartOfDay(ZoneId.of(timeZone)).toInstant().toEpochMilli(); }
        public long endExclusive() { return date(endDate).plusDays(1).atStartOfDay(ZoneId.of(timeZone)).toInstant().toEpochMilli(); }
    }
    public record Slot(RunToken runToken, String childId, String artifactId) {}
    @FunctionalInterface public interface SlotResolver { Slot resolve(int periodIndex, int shardIndex); }
    public record Gap(int periodIndex, int shardIndex, String code) {}
    public record PeriodCoverage(int periodIndex, boolean complete, int completedSlots, long completedMembers,
                                 BigInteger observedSubtotalPv) {}
    /** completedMembers counts member observations across both periods, not completed comparison pairs. */
    public record Summary(boolean coverageComplete, int expectedSlots, int completedSlots, long expectedMembers,
                          long completedMembers, int gapCount, List<PeriodCoverage> periods) {
        public Summary { periods = List.copyOf(periods); }
    }
    /** At most one shard. Accessors return copies so a consumer cannot mutate another consumer's evidence. */
    public record VerifiedSlot(int periodIndex, int shardIndex, String artifactId, String requestHash,
                               JsonNode snapshot, JsonNode metrics, List<JsonNode> rows) {
        public VerifiedSlot {
            snapshot = snapshot.deepCopy(); metrics = metrics.deepCopy(); rows = copyRows(rows);
        }
        @Override public JsonNode snapshot() { return snapshot.deepCopy(); }
        @Override public JsonNode metrics() { return metrics.deepCopy(); }
        @Override public List<JsonNode> rows() { return copyRows(rows); }
    }

    public Summary check(Caller current, String scopeArtifactId, List<Period> periods, SlotResolver slots,
                         ArtifactAuthorizer authorizer, Consumer<Gap> gaps) {
        return check(current, scopeArtifactId, periods, slots, authorizer, gaps, ignored -> {});
    }

    public Summary check(Caller current, String scopeArtifactId, List<Period> supplied, SlotResolver slots,
                         ArtifactAuthorizer authorizer, Consumer<Gap> gaps, Consumer<VerifiedSlot> sink) {
        return checkRange(current, scopeArtifactId, supplied, slots, authorizer, gaps, sink, null);
    }

    /** One deterministic shard; summary counts describe only this shard, never the full parent. */
    public Summary checkShard(Caller current, String scopeArtifactId, List<Period> supplied, SlotResolver slots,
                              ArtifactAuthorizer authorizer, Consumer<Gap> gaps, Consumer<VerifiedSlot> sink, int shardIndex) {
        if (shardIndex < 0) throw new IllegalArgumentException("COVERAGE_SHARD_INVALID");
        return checkRange(current, scopeArtifactId, supplied, slots, authorizer, gaps, sink, shardIndex);
    }

    private Summary checkRange(Caller current, String scopeArtifactId, List<Period> supplied, SlotResolver slots,
                               ArtifactAuthorizer authorizer, Consumer<Gap> gaps, Consumer<VerifiedSlot> sink, Integer selectedShard) {
        Objects.requireNonNull(slots); Objects.requireNonNull(gaps); Objects.requireNonNull(sink);
        List<Period> periods = List.copyOf(supplied);
        if (periods.size() != 2) throw new IllegalArgumentException("COVERAGE_REQUIRES_TWO_PERIODS");
        Artifact scopeArtifact = runs.readArtifact(current, scopeArtifactId, authorizer);
        require("ScopeArtifact".equals(scopeArtifact.metadata().ref().type())
                && "campaign-scope/v1".equals(scopeArtifact.metadata().ref().schemaVersion()), "COVERAGE_SCOPE_INVALID");
        JsonNode parent = tree(scopeArtifact.payloadJson());
        Set<String> fields = new HashSet<>(); parent.fieldNames().forEachRemaining(fields::add);
        require(fields.equals(SCOPE_FIELDS) && "campaign-scope/v1".equals(text(parent, "schemaVersion"))
                && scopeArtifact.metadata().ref().scopeRef().equals(text(parent, "scopeRef")), "COVERAGE_SCOPE_INVALID");
        long members = count(parent.get("memberCount"));
        int shardCount = Math.toIntExact(count(parent.get("shardCount")));
        require(shardCount == members / 500 + (members % 500 == 0 ? 0 : 1)
                && count(parent.get("pageCount")) == Math.max(1, shardCount), "COVERAGE_SCOPE_INVALID");
        if (selectedShard != null && selectedShard >= Math.max(1, shardCount))
            throw new IllegalArgumentException("COVERAGE_SHARD_INVALID");
        int startShard = selectedShard == null ? 0 : selectedShard;
        int endShard = selectedShard == null ? shardCount : Math.min(shardCount, startShard + 1);
        int checkedShards = endShard - startShard;
        long checkedMembers = selectedShard == null ? members : Math.min(500, Math.max(0, members - startShard * 500L));
        int expectedSlots = Math.multiplyExact(checkedShards, 2);
        long expectedMembers = Math.multiplyExact(checkedMembers, 2);
        int[] completed = new int[2]; long[] observations = new long[2];
        BigInteger[] pv = {BigInteger.ZERO, BigInteger.ZERO};
        int gapCount = 0;
        if (members == 0) {
            try { scopes.shard(current, scopeArtifactId, 0, authorizer); throw invalid("COVERAGE_SCOPE_INVALID"); }
            catch (IllegalArgumentException empty) {
                if (!"SCOPE_SHARD_UNAVAILABLE".equals(empty.getMessage())) throw empty;
            }
        }
        for (int shardIndex = startShard; shardIndex < endShard; shardIndex++) {
            FrozenQueryScope scope = scopes.shard(current, scopeArtifactId, shardIndex, authorizer);
            require(scope.parentMemberCount() == members && scope.shardCount() == shardCount && scope.shardIndex() == shardIndex
                    && scope.parentScopeRef().equals(text(parent, "scopeRef"))
                    && scope.parentMemberHash().equals(text(parent, "memberHash"))
                    && scope.enumerationVersion().equals(text(parent, "enumerationVersion")), "COVERAGE_SCOPE_INVALID");
            for (int periodIndex = 0; periodIndex < 2; periodIndex++) {
                // Resolve trusted slots and call consumer code outside the evidence-error boundary.
                Slot slot = slots.resolve(periodIndex, shardIndex);
                Checked checked;
                try { checked = verify(current, text(parent, "gid"), scope, periods.get(periodIndex), slot, authorizer, periodIndex, shardIndex); }
                catch (CoverageFailure failure) {
                    gaps.accept(new Gap(periodIndex, shardIndex, failure.getMessage())); gapCount++; continue;
                }
                sink.accept(checked.slot());
                completed[periodIndex]++;
                observations[periodIndex] = Math.addExact(observations[periodIndex], scope.linkIds().size());
                pv[periodIndex] = pv[periodIndex].add(checked.pv());
            }
        }
        List<PeriodCoverage> coverage = List.of(
                new PeriodCoverage(0, completed[0] == checkedShards, completed[0], observations[0], pv[0]),
                new PeriodCoverage(1, completed[1] == checkedShards, completed[1], observations[1], pv[1]));
        return new Summary(gapCount == 0, expectedSlots, completed[0] + completed[1], expectedMembers,
                observations[0] + observations[1], gapCount, coverage);
    }

    private Checked verify(Caller current, String gid, FrozenQueryScope scope, Period period, Slot slot,
                            ArtifactAuthorizer authorizer, int periodIndex, int shardIndex) {
        require(slot != null && slot.runToken() != null && slot.childId() != null && slot.artifactId() != null, "COVERAGE_SLOT_MISSING");
        if (!current.equals(slot.runToken().definition().caller())) throw new SecurityException("LEDGER_SUBJECT_MISMATCH");
        ChildRecord child = runs.child(slot.runToken(), slot.childId()).orElseThrow(() -> invalid("COVERAGE_CHILD_MISSING"));
        require(child.state() == ChildState.READY && slot.artifactId().equals(child.artifactId()), "COVERAGE_CHILD_NOT_READY");
        require(child.spec().mode() == ChildMode.ASYNC && child.spec().wire() != null
                && StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()), "COVERAGE_REQUEST_MISMATCH");
        StatisticsJobResultProtocol protocol;
        try { protocol = new StatisticsJobResultProtocol(child); }
        catch (IllegalStateException | IllegalArgumentException failure) { throw invalid("COVERAGE_REQUEST_MISMATCH"); }
        Map<String, Object> request = protocol.request();
        require("LINK_METRICS".equals(request.get("queryKind")) && gid.equals(request.get("gid"))
                && period.startDate().equals(request.get("startDate")) && period.endDate().equals(request.get("endDate")),
                "COVERAGE_REQUEST_MISMATCH");
        try { require(scope.equals(FrozenQueryScope.fromMap((Map<?, ?>) request.get("scope"))), "COVERAGE_SCOPE_PROOF_MISMATCH"); }
        catch (IllegalArgumentException | ClassCastException failure) { throw invalid("COVERAGE_SCOPE_PROOF_MISMATCH"); }
        var receipt = results.receipt(slot.runToken(), slot.childId()).orElseThrow(() -> invalid("COVERAGE_RECEIPT_MISSING"));
        require(receipt.published() && receipt.complete() && receipt.spec().totalRows() == scope.linkIds().size()
                && receipt.spec().pageCount() == 1 && receipt.storedPages() == 1 && receipt.nextPageIndex() == 1,
                "COVERAGE_ROWS_INCOMPLETE");
        Artifact artifact = runs.readArtifact(current, slot.artifactId(), authorizer);
        var meta = artifact.metadata(); var def = slot.runToken().definition();
        require(CampaignStatisticsResultStore.ARTIFACT_TYPE.equals(meta.ref().type())
                && CampaignStatisticsResultStore.SCHEMA_VERSION.equals(meta.ref().schemaVersion())
                && def.runId().equals(meta.runId()) && def.planId().equals(meta.planId()) && def.revision() == meta.revision()
                && child.spec().childId().equals(meta.childId()) && child.spec().actionId().equals(meta.actionId())
                && scope.parentScopeRef().equals(meta.ref().scopeRef()) && period.periodsRef().equals(meta.ref().periodsRef())
                && period.periodsRef().equals(receipt.spec().periodsRef()) && scope.parentScopeRef().equals(receipt.spec().scopeRef())
                && child.jobId().equals(receipt.spec().jobId()) && slot.artifactId().equals(receipt.spec().artifactId())
                && child.spec().wire().hash().equals(receipt.spec().requestHash())
                && meta.ref().expiresAt().toEpochMilli() == receipt.spec().expiresAtMillis(), "COVERAGE_ARTIFACT_BINDING_MISMATCH");
        JsonNode provenance = tree(meta.provenanceJson());
        require("FROZEN_SET".equals(text(provenance, "scopeMode"))
                && child.spec().wire().hash().equals(text(provenance, "requestHash"))
                && child.jobId().equals(text(provenance, "jobId")), "COVERAGE_ARTIFACT_BINDING_MISMATCH");
        String pageBody = results.readPage(current, slot.artifactId(), 0, authorizer);
        JsonNode payload = tree(pageBody);
        String initial = CampaignRunStore.sha256(CampaignStatisticsResultStore.SCHEMA_VERSION + ":" + specHash(receipt.spec()));
        String actualChain = CampaignRunStore.sha256(initial + ":0:" + CampaignRunStore.sha256(pageBody) + ":" + scope.linkIds().size());
        require(actualChain.equals(receipt.chainHash()), "COVERAGE_PAGE_CHAIN_MISMATCH");
        CampaignStatisticsResultStore.Page decoded;
        try {
            decoded = protocol.page(new StatisticsJobResultProtocol.Status(child.jobId(), "SUCCEEDED",
                    receipt.spec().totalRows(), receipt.spec().pageCount(), receipt.spec().expiresAtMillis(), null),
                    JSON.convertValue(payload, Object.class), 0);
        } catch (IllegalStateException | IllegalArgumentException failure) { throw invalid("COVERAGE_PAGE_CONTEXT_MISMATCH"); }
        JsonNode snapshot = tree(receipt.snapshotJson()), metrics = payload.get("metrics");
        require(snapshot.equals(tree(decoded.snapshotJson())) && tree(receipt.metricsJson()).equals(tree(decoded.metricsJson()))
                && tree(meta.qualityJson()).equals(snapshot), "COVERAGE_PAGE_CONTEXT_MISMATCH");
        require("LINK_WINDOW".equals(text(snapshot, "aggregationLevel"))
                && "AVAILABLE".equals(text(snapshot, "availability"))
                && "COMPLETE".equals(text(snapshot, "completeness")), "COVERAGE_QUERY_INCOMPLETE");
        require(provenance.get("scopeProof") != null && provenance.get("scopeProof").equals(snapshot.get("scopeProof")),
                "COVERAGE_SCOPE_PROOF_MISMATCH");
        JsonNode manifest = tree(artifact.payloadJson());
        require(Boolean.TRUE.equals(manifest.path("resultComplete").isBoolean() ? manifest.path("resultComplete").booleanValue() : null)
                && child.jobId().equals(text(manifest, "jobId")) && slot.artifactId().equals(text(manifest, "artifactId"))
                && count(manifest.get("pageCount")) == 1 && count(manifest.get("receivedPageCount")) == 1
                && count(manifest.get("totalRows")) == scope.linkIds().size()
                && receipt.chainHash().equals(text(manifest, "chainHash")) && snapshot.equals(manifest.get("meta"))
                && metrics.equals(manifest.get("metrics")), "COVERAGE_ARTIFACT_BINDING_MISMATCH");
        JsonNode requested = metrics.get("requested");
        window(requested, period);
        for (String counter : COUNTS) count(requested.get(counter));
        Set<Long> expected = new HashSet<>(scope.linkIds()), seen = new HashSet<>();
        List<JsonNode> rows = new ArrayList<>(); BigInteger pv = BigInteger.ZERO, denied = BigInteger.ZERO;
        JsonNode items = payload.get("items");
        require(items != null && items.isArray() && items.size() == scope.linkIds().size(), "COVERAGE_ROWS_INCOMPLETE");
        for (JsonNode row : items) {
            window(row, period);
            long id = count(row.get("linkId"));
            require(expected.contains(id) && seen.add(id), "COVERAGE_MEMBER_MISMATCH");
            for (String counter : COUNTS) count(row.get(counter));
            pv = pv.add(BigInteger.valueOf(count(row.get("pv"))));
            denied = denied.add(BigInteger.valueOf(count(row.get("denied"))));
            rows.add(row);
        }
        require(seen.equals(expected), "COVERAGE_ROWS_INCOMPLETE");
        require(pv.equals(BigInteger.valueOf(count(requested.get("pv"))))
                && denied.equals(BigInteger.valueOf(count(requested.get("denied")))), "COVERAGE_PV_SUM_MISMATCH");
        return new Checked(new VerifiedSlot(periodIndex, shardIndex, slot.artifactId(), child.spec().wire().hash(),
                payload.get("meta"), metrics, rows), pv);
    }

    private static void window(JsonNode value, Period period) {
        require(value != null && value.isObject() && "requested".equals(text(value, "window"))
                && count(value.get("startInclusive")) == period.startInclusive()
                && count(value.get("endExclusive")) == period.endExclusive(), "COVERAGE_WINDOW_MISMATCH");
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("COVERAGE_PERIOD_INVALID");
        return LocalDate.parse(value);
    }
    private static long count(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0, "COVERAGE_COUNT_INVALID");
        return value.longValue();
    }
    private static String text(JsonNode value, String name) {
        require(value != null && value.path(name).isTextual() && !value.path(name).textValue().isBlank(), "COVERAGE_CONTEXT_INVALID");
        return value.path(name).textValue();
    }
    private static JsonNode tree(String value) {
        try { JsonNode result = JSON.readTree(value); require(result != null && result.isObject(), "COVERAGE_JSON_INVALID"); return result; }
        catch (java.io.IOException | IllegalArgumentException malformed) { throw invalid("COVERAGE_JSON_INVALID"); }
    }
    private static List<JsonNode> copyRows(List<JsonNode> rows) { return rows.stream().map(value -> (JsonNode) value.deepCopy()).toList(); }
    private static String specHash(CampaignStatisticsResultStore.ReceiptSpec spec) {
        try { return CampaignRunStore.sha256(JSON.writeValueAsString(spec)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid("COVERAGE_RECEIPT_INVALID"); }
    }
    private static void require(boolean condition, String code) { if (!condition) throw invalid(code); }
    private static CoverageFailure invalid(String code) { return new CoverageFailure(code); }
    private static final class CoverageFailure extends IllegalStateException { CoverageFailure(String code) { super(code); } }
    private record Checked(VerifiedSlot slot, BigInteger pv) {}
}
