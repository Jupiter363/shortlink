package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsJobResultProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB = "job-1";
    private static final long START = day("2026-09-01");
    private static final long END = day("2026-09-03");
    private static final long EXPIRES = day("2026-10-01");

    @Test
    void releasedStatusCannotBecomeAnEmptyResultOrStartPageReception() throws Exception {
        var protocol = protocol(request("METRICS"));
        var released = new LinkedHashMap<String, Object>(status(501));
        released.put("resultState", "RELEASED");
        released.put("resultCode", "RESULT_RELEASED");
        released.put("resultReady", false);
        var unavailable = assertThrows(IllegalArgumentException.class, () -> protocol.status(released));
        assertEquals("RESULT_RELEASED", unavailable.getMessage());
        released.put("resultReady", true);
        assertEquals("STATISTICS_READ_PROTOCOL_UNAVAILABLE",
                assertThrows(IllegalArgumentException.class, () -> protocol.status(released)).getMessage());
        released.put("resultState", "AVAILABLE");
        released.remove("resultCode");
        assertEquals(501, protocol.status(released).totalRows());
        assertEquals(501, protocol.status(status(501)).totalRows(), "Legacy status stays readable");
    }

    @Test
    void acceptsFixedTwoPageAndZeroRowReceiptsWithoutRequiringCountsWhileStillWaiting() throws Exception {
        StatisticsJobResultProtocol protocol = protocol(request("ACCESS_RECORDS"));
        var status = protocol.status(status(501));
        Map<String, Object> firstData = page(501, 0);
        Map<String, Object> lastData = page(501, 1);
        meta(firstData).put("hasMore", true);
        meta(lastData).put("hasMore", false);
        var first = protocol.page(status, firstData, 0);
        var last = protocol.page(status, lastData, 1);
        assertEquals(500, first.rowCount());
        assertEquals(1, first.nextPageIndex());
        assertEquals(1, last.rowCount());
        assertNull(last.nextPageIndex());
        assertEquals(first.snapshotJson(), last.snapshotJson());
        assertEquals(first.metricsJson(), last.metricsJson());
        assertFalse(first.snapshotJson().contains("nextPageIndex"));
        assertFalse(first.snapshotJson().contains("hasMore"));
        assertTrue(first.payloadJson().contains("nextPageIndex"));
        assertTrue(first.payloadJson().contains("hasMore"));
        var empty = protocol.page(protocol.status(status(0)), page(0, 0), 0);
        assertEquals(0, empty.rowCount());
        assertNull(empty.nextPageIndex());
        assertThrows(IllegalArgumentException.class, () -> protocol.page(protocol.status(status(0)), page(0, 0), 1));
        for (String state : List.of("QUEUED", "RUNNING", "FAILED", "CANCELLED")) {
            var pending = protocol.status(Map.of("jobId", JOB, "state", state));
            assertEquals(state, pending.state());
            assertEquals(0, pending.totalRows());
            assertEquals(0, pending.pageCount());
            assertEquals(0, pending.expiresAtMillis());
        }
        assertThrows(UnsupportedOperationException.class, () -> protocol.request().put("gid", "other"));
        Map<String, Object> instants = request("ACCESS_RECORDS");
        instants.put("startDate", "2026-08-31T16:00:00Z");
        instants.put("endDate", "2026-09-03 00:00:00");
        assertEquals(first.payloadJson(), protocol(instants).page(status, firstData, 0).payloadJson());
    }

    @Test
    void rejectsWrongIdentityBoundariesCountsCursorsAndUnsupportedFrozenRequestFields() throws Exception {
        var protocol = protocol(request("ACCESS_RECORDS"));
        for (Consumer<Map<String, Object>> mutate : List.<Consumer<Map<String, Object>>>of(
                value -> value.put("jobId", "other-job"),
                value -> value.put("rowCount", 501.0),
                value -> value.put("rowCount", "501"),
                value -> value.put("rowCount", -1),
                value -> value.put("pageCount", 1),
                value -> value.put("expiresAt", 0))) {
            Map<String, Object> invalid = status(501);
            mutate.accept(invalid);
            fixedFailure(() -> protocol.status(invalid));
        }
        var status = protocol.status(status(501));
        for (Consumer<Map<String, Object>> mutate : List.<Consumer<Map<String, Object>>>of(
                value -> value.put("snapshotId", "wrong-job"),
                value -> value.put("requestedStart", START + 1),
                value -> value.put("requestedEnd", END - 1),
                value -> value.put("totalRows", 500),
                value -> value.put("pageIndex", 1),
                value -> value.put("nextPageIndex", 2),
                value -> value.remove("nextPageIndex"),
                value -> value.put("snapshotExpiresAt", EXPIRES + 1),
                value -> value.put("groupScopeComplete", false),
                value -> value.put("linkIds", List.of(1, 1)),
                value -> value.remove("linkIds"),
                value -> value.put("sourceCut", Map.of()),
                value -> value.put("manifestVersion", Map.of("selectionHash", "changed-hash")),
                value -> value.put("queryScope", Map.of("gid", "other-group")),
                value -> value.put("queryScope", Map.of("startInclusive", START + 1)),
                value -> value.put("dimensionQualityScope", "RETURNED_PAGE_LEGACY_JOB"))) {
            Map<String, Object> invalid = page(501, 0);
            mutate.accept(meta(invalid));
            fixedFailure(() -> protocol.page(status, invalid, 0));
        }
        Map<String, Object> shortPage = page(501, 0);
        shortPage.put("items", records(499, 0));
        fixedFailure(() -> protocol.page(status, shortPage, 0));
        Map<String, Object> invalidLast = page(501, 1);
        meta(invalidLast).put("nextPageIndex", 2);
        fixedFailure(() -> protocol.page(status, invalidLast, 1));
        Map<String, Object> unsupported = request("ACCESS_RECORDS");
        unsupported.put("scopeMode", "FROZEN_SET");
        fixedFailure(() -> protocol(unsupported));
        unsupported.remove("scopeMode");
        unsupported.put("queryKind", "UNKNOWN_QUERY");
        fixedFailure(() -> protocol(unsupported));
        unsupported.remove("queryKind");
        fixedFailure(() -> protocol(unsupported));
        Map<String, Object> invalidDate = request("ACCESS_RECORDS");
        invalidDate.put("startDate", "2026-02-30");
        fixedFailure(() -> protocol(invalidDate));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimensionAndFilterProofUsesExistingCanonicalContractAndSpecificUrlRemainsBound() throws Exception {
        Map<String, Object> query = request("DIMENSION_BREAKDOWN");
        query.put("dimensions", List.of("province", "device"));
        query.put("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江", "江苏"))));
        query.put("fullShortUrl", "https://short.test/a");
        var protocol = protocol(query);
        Map<String, Object> data = page(1, 0);
        meta(data).put("queryKind", "DIMENSION_BREAKDOWN");
        meta(data).put("dimensions", List.of("province", "device"));
        meta(data).put("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("江苏", "浙江"))));
        meta(data).put("fullShortUrl", "short.test/a");
        meta(data).put("groupScopeComplete", false);
        assertEquals(1, protocol.page(protocol.status(status(1)), data, 0).rowCount());
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) protocol.request().get("dimensions")).add("browser"));
        meta(data).put("dimensions", List.of("province", "browser"));
        fixedFailure(() -> protocol.page(protocol.status(status(1)), data, 0));
        meta(data).put("dimensions", List.of("province", "device"));
        meta(data).put("filters", List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("广东"))));
        fixedFailure(() -> protocol.page(protocol.status(status(1)), data, 0));
        meta(data).put("filters", query.get("filters"));
        meta(data).put("fullShortUrl", "short.test/b");
        fixedFailure(() -> protocol.page(protocol.status(status(1)), data, 0));
        query.put("dimensions", List.of("province", "sql"));
        fixedFailure(() -> protocol(query));
    }

    @Test
    void canonicalPagesPreservePartialQualityNullsAndArrayOrderButNotMapInsertionOrder() throws Exception {
        var protocol = protocol(request("ACCESS_RECORDS"));
        var status = protocol.status(status(2));
        Map<String, Object> original = page(2, 0);
        meta(original).put("qualityDetails", Map.of("limits", List.of("missing-source", "estimated-uv"),
                "nested", Map.of("z", 2, "a", 1)));
        meta(original).put("ruleVersion", null);
        var accepted = protocol.page(status, original, 0);
        Map<String, Object> reordered = reverseMaps(original);
        var equivalent = protocol.page(status, reordered, 0);
        assertEquals(accepted, equivalent);
        Map<?, ?> restoredMeta = (Map<?, ?>) JSON.readValue(accepted.snapshotJson(), Map.class);
        assertEquals("PARTIAL", restoredMeta.get("completeness"));
        assertEquals(Map.of("status", "UNKNOWN"), restoredMeta.get("collectionQuality"));
        assertTrue(restoredMeta.containsKey("ruleVersion"));
        assertNull(restoredMeta.get("ruleVersion"));
        assertFalse(restoredMeta.containsKey("resultComplete"), "Receiving all pages cannot invent data-quality completeness");
        List<Object> reversedRows = new ArrayList<>((List<?>) reordered.get("items"));
        Collections.reverse(reversedRows);
        reordered.put("items", reversedRows);
        assertNotEquals(accepted.payloadJson(), protocol.page(status, reordered, 0).payloadJson());
        Map<String, Object> renamed = page(2, 0);
        Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) ((List<?>) renamed.get("items")).get(0));
        row.put("displayName", "changed-after-freeze");
        renamed.put("items", List.of(row, ((List<?>) renamed.get("items")).get(1)));
        assertNotEquals(protocol.page(status, page(2, 0), 0).payloadJson(), protocol.page(status, renamed, 0).payloadJson(),
                "Display identity changes remain detectable by the store's immutable page checksum");
    }

    @Test
    void frozenPagesRequireTheExactOriginalShardProofWithoutUpgradingPartialQualityOrLegacyRequests() throws Exception {
        FrozenQueryScope scope = scope(List.of(1L, 2L));
        Map<String, Object> query = request("ACCESS_RECORDS");
        query.put("scope", scope.asMap());
        var protocol = protocol(query, StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH);
        var status = protocol.status(status(1));
        Map<String, Object> data = page(1, 0);
        meta(data).put("linkIds", List.of(1L, 2L));
        meta(data).put("groupScopeComplete", false);
        meta(data).put("scopeProof", scope.proof("b".repeat(64)));
        var accepted = protocol.page(status, data, 0);
        var snapshot = JSON.readTree(accepted.snapshotJson());
        assertEquals("PARTIAL", snapshot.path("completeness").asText());
        assertEquals("UNKNOWN", snapshot.path("collectionQuality").path("status").asText());
        assertFalse(snapshot.path("scopeProof").path("parentComplete").booleanValue());
        assertEquals(scope, FrozenQueryScope.fromProof(protocol.frozenScopeProof(scope.parentScopeRef(), meta(data))));
        fixedFailure(() -> protocol.frozenScopeProof("other-parent", meta(data)));

        for (Consumer<Map<String, Object>> mutate : List.<Consumer<Map<String, Object>>>of(
                value -> value.remove("scopeProof"),
                value -> value.put("scopeProof", scope(List.of(1L, 3L)).proof("b".repeat(64))),
                value -> { var proof = new LinkedHashMap<>(scope.proof("b".repeat(64))); proof.put("parentComplete", true); value.put("scopeProof", proof); },
                value -> { var proof = new LinkedHashMap<>(scope.proof("b".repeat(64))); proof.put("extra", true); value.put("scopeProof", proof); },
                value -> value.put("linkIds", List.of(1L, 3L)),
                value -> value.put("linkIds", List.of(2L, 1L)),
                value -> value.put("groupScopeComplete", true),
                value -> value.put("parentComplete", true),
                value -> value.put("scopeRef", "other-parent"))) {
            Map<String, Object> invalid = new LinkedHashMap<>(data);
            invalid.put("meta", new LinkedHashMap<>(meta(data)));
            mutate.accept(meta(invalid));
            fixedFailure(() -> protocol.page(status, invalid, 0));
        }

        fixedFailure(() -> protocol(query)); // The old endpoint has no scope field.
        fixedFailure(() -> protocol(request("ACCESS_RECORDS"), StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH));
        Map<String, Object> withUrl = new LinkedHashMap<>(query);
        withUrl.put("fullShortUrl", "https://short.test/a");
        fixedFailure(() -> protocol(withUrl, StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH));
        withUrl.put("fullShortUrl", null);
        fixedFailure(() -> protocol(withUrl, StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH));
        Map<String, Object> extraField = new LinkedHashMap<>(query);
        extraField.put("scopeMode", "FROZEN_SET");
        fixedFailure(() -> protocol(extraField, StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH));
        Map<String, Object> legacyData = page(1, 0);
        meta(legacyData).put("scopeProof", scope.proof("b".repeat(64)));
        var legacy = protocol(request("ACCESS_RECORDS"));
        fixedFailure(() -> legacy.page(status, legacyData, 0));
    }

    private static StatisticsJobResultProtocol protocol(Map<String, Object> request) throws Exception {
        return protocol(request, "/internal/short-link-admin/v1/agent-tools/statistics/jobs");
    }

    private static StatisticsJobResultProtocol protocol(Map<String, Object> request, String path) throws Exception {
        var spec = new ChildSpec("child-1", "action-1", ChildMode.ASYNC, "request-1",
                new WireRequest("POST", path, JSON.writeValueAsString(request)));
        return new StatisticsJobResultProtocol(new ChildRecord(spec, ChildState.WAITING, JOB, null,
                "attempt-1", 1, DispatchPurpose.FRESH, false, null));
    }

    private static FrozenQueryScope scope(List<Long> members) {
        String hash = FrozenQueryScope.memberHash(members);
        return new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-frozen", hash,
                members.size(), "a".repeat(64), FrozenQueryScope.shardIdFor("scope-frozen", 0, hash),
                0, 1, hash, members);
    }

    private static Map<String, Object> request(String kind) {
        return new LinkedHashMap<>(Map.of("requestId", "request-1", "gid", "group-a", "startDate", "2026-09-01",
                "endDate", "2026-09-02", "queryKind", kind));
    }

    private static Map<String, Object> status(long rows) {
        return new LinkedHashMap<>(Map.of("jobId", JOB, "state", "SUCCEEDED", "rowCount", rows,
                "pageCount", rows / 500 + (rows % 500 == 0 ? 0 : 1), "expiresAt", EXPIRES));
    }

    private static Map<String, Object> page(int totalRows, int index) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("queryKind", "ACCESS_RECORDS");
        meta.put("gid", "group-a");
        meta.put("linkIds", List.of(1L));
        meta.put("snapshotId", JOB);
        meta.put("recoveryEpoch", "epoch-1");
        meta.put("metricVersion", "click-v1");
        meta.put("sourceCut", Map.of("manifestSelectionHash", "hash-1"));
        meta.put("manifestVersion", Map.of("selectionHash", "hash-1"));
        meta.put("manifestSelectionHash", "hash-1");
        meta.put("requestedStart", START);
        meta.put("requestedEnd", END);
        meta.put("effectiveEnd", END);
        meta.put("businessTimezone", "Asia/Shanghai");
        meta.put("snapshotExpiresAt", EXPIRES);
        meta.put("groupScopeComplete", true);
        meta.put("totalRows", totalRows);
        meta.put("pageIndex", index);
        int count = totalRows / 500 + (totalRows % 500 == 0 ? 0 : 1);
        meta.put("nextPageIndex", index + 1 < count ? index + 1 : null);
        meta.put("completeness", "PARTIAL");
        meta.put("collectionQuality", Map.of("status", "UNKNOWN"));
        return new LinkedHashMap<>(Map.of("items", records(Math.min(500, totalRows - index * 500), index * 500),
                "metrics", Map.of(), "meta", meta));
    }

    private static List<Map<String, Object>> records(int count, int offset) {
        return IntStream.range(offset, offset + count).mapToObj(index -> Map.<String, Object>of(
                "eventId", "event-" + index, "linkId", 1L, "occurredAt", START + index)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> meta(Map<String, Object> data) { return (Map<String, Object>) data.get("meta"); }

    private static Map<String, Object> reverseMaps(Map<String, Object> input) {
        List<String> keys = new ArrayList<>(input.keySet());
        Collections.reverse(keys);
        Map<String, Object> reversed = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = input.get(key);
            reversed.put(key, value instanceof Map<?, ?> ? reverseMaps(meta(Map.of("meta", value))) : value);
        }
        return reversed;
    }

    private static long day(String day) { return LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(); }

    private static void fixedFailure(org.junit.jupiter.api.function.Executable action) {
        String code = assertThrows(IllegalArgumentException.class, action).getMessage();
        assertTrue(List.of("STATISTICS_RESULT_MISMATCH", "STATISTICS_READ_PROTOCOL_UNAVAILABLE").contains(code), code);
    }
}
