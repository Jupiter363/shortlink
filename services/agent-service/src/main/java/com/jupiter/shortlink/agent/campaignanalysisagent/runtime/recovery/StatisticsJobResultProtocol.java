package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import com.jupiter.shortlink.contract.FrozenQueryScope;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Closed decoder for an existing statistics job's original request and frozen result pages.
 * No HTTP, authorization, clock-based expiry decision, scope discovery or quality upgrading.
 * Frozen evidence is accepted only for an original frozen request carrying the same shard proof.
 */
public final class StatisticsJobResultProtocol {
    private static final String PROTOCOL = "STATISTICS_READ_PROTOCOL_UNAVAILABLE";
    private static final String MISMATCH = "STATISTICS_RESULT_MISMATCH";
    private static final String SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";
    public static final String FROZEN_SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs";
    private static final Set<String> REQUEST_FIELDS = Set.of("requestId", "gid", "fullShortUrl", "startDate",
            "endDate", "queryKind", "dimensions", "filters");
    private static final Set<String> FROZEN_REQUEST_FIELDS = Set.of("requestId", "gid", "fullShortUrl", "startDate",
            "endDate", "queryKind", "dimensions", "filters", "scope");
    private static final Set<String> KINDS = Set.of("METRICS", "ACCESS_RECORDS", "LINK_METRICS", "DIMENSION_BREAKDOWN");
    private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Status(String jobId, String state, long totalRows, int pageCount,
                         long expiresAtMillis, String errorCode) { }

    private final String jobId;
    private final Map<String, Object> request;
    private final FrozenQueryScope frozenScope;
    private final long start;
    private final long end;

    public StatisticsJobResultProtocol(ChildRecord child) {
        require(child != null && child.spec() != null && child.spec().wire() != null
                && child.spec().mode() == ChildMode.ASYNC && reference(child.jobId(), 128), PROTOCOL);
        jobId = child.jobId();
        DecodedRequest decoded = decodeRequest(child.spec(), true);
        request = decoded.request();
        frozenScope = decoded.scope();
        start = decoded.start();
        end = decoded.end();
    }

    /** Original closed request, also usable before a lost submission's job identity is recovered. */
    public static Map<String, Object> originalRequest(ChildSpec child) { return decodeRequest(child, false).request(); }

    private static DecodedRequest decodeRequest(ChildSpec child, boolean validateQuery) {
        require(child != null && child.mode() == ChildMode.ASYNC && child.wire() != null, PROTOCOL);
        boolean frozen = FROZEN_SUBMIT_PATH.equals(child.wire().path());
        require("POST".equals(child.wire().method()) && (frozen || SUBMIT_PATH.equals(child.wire().path())), PROTOCOL);
        Map<String, Object> parsed;
        try { parsed = object(JSON.readValue(child.wire().bodyJson(), Object.class)); }
        catch (JsonProcessingException | IllegalArgumentException invalid) { throw failure(PROTOCOL); }
        require((frozen ? FROZEN_REQUEST_FIELDS : REQUEST_FIELDS).containsAll(parsed.keySet()), PROTOCOL);
        require(reference(child.requestId(), 96) && child.requestId().equals(parsed.get("requestId")), MISMATCH);
        // Legacy identity recovery keeps its existing semantics. The frozen protocol and all
        // result reads additionally require a valid complete query before accepting evidence.
        if (!frozen && !validateQuery)
            return new DecodedRequest(object(freezeJson(parsed, new IdentityHashMap<>(), 0)), null, 0, 0);
        require(text(parsed.get("gid")) && text(parsed.get("queryKind")) && KINDS.contains(parsed.get("queryKind")), PROTOCOL);
        long start = boundary(parsed.get("startDate"), false);
        long end = boundary(parsed.get("endDate"), true);
        require(start >= 0 && end > start && end - start <= 180L * 24 * 60 * 60 * 1000, PROTOCOL);
        if (parsed.get("fullShortUrl") != null) require(text(parsed.get("fullShortUrl")), PROTOCOL);
        FrozenQueryScope scope = null;
        if (frozen) {
            require(!parsed.containsKey("fullShortUrl"), PROTOCOL);
            require(((String) parsed.get("gid")).matches("[A-Za-z0-9_-]{1,64}"), PROTOCOL);
            try { scope = FrozenQueryScope.fromMap(object(parsed.get("scope"))); }
            catch (IllegalArgumentException invalid) { throw failure(PROTOCOL); }
        }
        validateDimensions(parsed);
        return new DecodedRequest(object(freezeJson(parsed, new IdentityHashMap<>(), 0)), scope, start, end);
    }

    /** Deeply immutable original wire values; request identity is never regenerated or normalized. */
    public Map<String, Object> request() { return request; }

    /** Validates the original shard before exposing its proof for durable artifact provenance. */
    public Map<String, Object> frozenScopeProof(String scopeRef, Object snapshot) {
        require(frozenScope != null && frozenScope.parentScopeRef().equals(scopeRef), MISMATCH);
        Map<String, Object> meta = object(snapshot);
        validateFrozenScope(meta);
        return object(freezeJson(object(meta.get("scopeProof")), new IdentityHashMap<>(), 0));
    }

    public Status status(Object data) {
        Map<String, Object> status = object(data);
        require(jobId.equals(status.get("jobId")), MISMATCH);
        require(status.get("state") instanceof String state && STATES.contains(state), PROTOCOL);
        String state = (String) status.get("state");
        if (status.containsKey("resultState")) {
            Object resultState = status.get("resultState");
            if ("RELEASED".equals(resultState)) {
                require(Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)
                        && Boolean.FALSE.equals(status.get("resultReady"))
                        && "RESULT_RELEASED".equals(status.get("resultCode")), PROTOCOL);
                throw failure("RESULT_RELEASED");
            }
            String expected = "SUCCEEDED".equals(state) ? "AVAILABLE"
                    : Set.of("QUEUED", "RUNNING").contains(state) ? "PENDING" : "UNAVAILABLE";
            require(expected.equals(resultState) && Boolean.valueOf("AVAILABLE".equals(expected)).equals(status.get("resultReady"))
                    && status.get("resultCode") == null, PROTOCOL);
        }
        String error = null;
        if (status.get("errorCode") != null) {
            require(status.get("errorCode") instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}"), PROTOCOL);
            error = (String) status.get("errorCode");
        }
        if (!"SUCCEEDED".equals(state)) return new Status(jobId, state, 0, 0, 0, error);
        long total = integer(status.get("rowCount"));
        long pageCount = integer(status.get("pageCount"));
        long expires = integer(status.get("expiresAt"));
        require(pageCount <= Integer.MAX_VALUE && expires > 0, PROTOCOL);
        require(pageCount == pages(total), MISMATCH);
        return new Status(jobId, state, total, (int) pageCount, expires, error);
    }

    public CampaignStatisticsResultStore.Page page(Status status, Object data, int pageIndex) {
        require(status != null && jobId.equals(status.jobId()) && "SUCCEEDED".equals(status.state()), MISMATCH);
        require(status.totalRows() >= 0 && status.pageCount() >= 0 && status.expiresAtMillis() > 0
                && status.pageCount() == pages(status.totalRows()), PROTOCOL);
        require(pageIndex >= 0 && pageIndex < Math.max(1, status.pageCount()), MISMATCH);
        Map<String, Object> payload = object(data);
        require(payload.get("items") instanceof List<?>, PROTOCOL);
        List<?> items = (List<?>) payload.get("items");
        Map<String, Object> metrics = object(payload.get("metrics"));
        Map<String, Object> meta = object(payload.get("meta"));
        require(items.stream().allMatch(item -> item instanceof Map<?, ?>), PROTOCOL);
        require(jobId.equals(meta.get("snapshotId")) && request.get("queryKind").equals(meta.get("queryKind"))
                && request.get("gid").equals(meta.get("gid")), MISMATCH);
        require(integer(meta.get("requestedStart")) == start && integer(meta.get("requestedEnd")) == end
                && integer(meta.get("effectiveEnd")) == end, MISMATCH);
        require("Asia/Shanghai".equals(meta.get("businessTimezone")), MISMATCH);
        require(integer(meta.get("snapshotExpiresAt")) == status.expiresAtMillis(), MISMATCH);
        require(integer(meta.get("totalRows")) == status.totalRows() && integer(meta.get("pageIndex")) == pageIndex, MISMATCH);
        validateSnapshot(meta);
        Set<Long> linkIds = linkIds(meta.get("linkIds"));
        if (frozenScope != null) {
            validateFrozenScope(meta);
        } else if (request.get("fullShortUrl") != null) {
            require(linkIds.size() == 1 && text(meta.get("fullShortUrl"))
                    && normalizeUrl(request.get("fullShortUrl")).equals(normalizeUrl(meta.get("fullShortUrl"))), MISMATCH);
        } else {
            require(Boolean.TRUE.equals(meta.get("groupScopeComplete")), MISMATCH);
        }
        if (frozenScope == null) require(!meta.containsKey("scopeProof"), MISMATCH);
        validateNestedProof(meta);
        if ("DIMENSION_BREAKDOWN".equals(request.get("queryKind"))) {
            require(DimensionQuery.matches(meta, request), MISMATCH);
        }
        for (Object item : items) {
            Map<String, Object> row = object(item);
            if (row.containsKey("linkId")) require(linkIds.contains(integer(row.get("linkId"))), MISMATCH);
        }
        long expectedRows = status.totalRows() == 0 ? 0 : Math.min(500L, status.totalRows() - (long) pageIndex * 500);
        require(items.size() == expectedRows, MISMATCH);
        boolean more = pageIndex + 1 < status.pageCount();
        require(meta.containsKey("nextPageIndex"), PROTOCOL);
        Integer next = null;
        if (more) {
            require(integer(meta.get("nextPageIndex")) == pageIndex + 1L, MISMATCH);
            next = pageIndex + 1;
        } else require(meta.get("nextPageIndex") == null, MISMATCH);
        if (meta.containsKey("hasMore")) require(Boolean.valueOf(more).equals(meta.get("hasMore")), MISMATCH);
        if (payload.containsKey("hasMore")) require(Boolean.valueOf(more).equals(payload.get("hasMore")), MISMATCH);
        Map<String, Object> snapshot = new LinkedHashMap<>(meta);
        snapshot.remove("pageIndex");
        snapshot.remove("nextPageIndex");
        snapshot.remove("hasMore");
        return new CampaignStatisticsResultStore.Page(pageIndex, next, items.size(), canonical(snapshot),
                canonical(metrics), canonical(payload));
    }

    private static void validateDimensions(Map<String, Object> query) {
        try {
            if ("DIMENSION_BREAKDOWN".equals(query.get("queryKind"))) {
                DimensionQuery.dimensions(query.get("dimensions"));
                DimensionQuery.filters(query.get("filters"));
                if (query.get("filters") instanceof List<?> filters) {
                    for (Object filter : filters) require(Set.of("dimension", "operator", "values")
                            .containsAll(object(filter).keySet()), PROTOCOL);
                }
            } else {
                require(query.get("dimensions") == null || query.get("dimensions") instanceof List<?> list && list.isEmpty(), PROTOCOL);
                require(query.get("filters") == null || query.get("filters") instanceof List<?> list && list.isEmpty(), PROTOCOL);
            }
        } catch (IllegalArgumentException invalid) { throw failure(PROTOCOL); }
    }

    private void validateFrozenScope(Map<String, Object> meta) {
        require(Boolean.FALSE.equals(meta.get("groupScopeComplete")), MISMATCH);
        require(!meta.containsKey("parentComplete") || Boolean.FALSE.equals(meta.get("parentComplete")), MISMATCH);
        if (meta.containsKey("scopeRef")) require(frozenScope.parentScopeRef().equals(meta.get("scopeRef")), MISMATCH);
        require(meta.get("linkIds") instanceof List<?>, PROTOCOL);
        List<Long> members = ((List<?>) meta.get("linkIds")).stream().map(StatisticsJobResultProtocol::integer).toList();
        require(frozenScope.linkIds().equals(members), MISMATCH);
        try { require(frozenScope.equals(FrozenQueryScope.fromProof(object(meta.get("scopeProof")))), MISMATCH); }
        catch (IllegalArgumentException invalid) { throw failure(MISMATCH); }
    }

    private static void validateSnapshot(Map<String, Object> meta) {
        require(text(meta.get("metricVersion")) && text(meta.get("recoveryEpoch")), PROTOCOL);
        Map<String, Object> source = object(meta.get("sourceCut"));
        Map<String, Object> manifest = object(meta.get("manifestVersion"));
        require(text(source.get("manifestSelectionHash")) && text(manifest.get("selectionHash")), PROTOCOL);
        require(source.get("manifestSelectionHash").equals(manifest.get("selectionHash")), MISMATCH);
        if (meta.containsKey("manifestSelectionHash"))
            require(source.get("manifestSelectionHash").equals(meta.get("manifestSelectionHash")), MISMATCH);
        // This old shape describes only each returned page, not the fixed whole-window quality contract.
        require(!"RETURNED_PAGE_LEGACY_JOB".equals(meta.get("dimensionQualityScope")), MISMATCH);
        for (String time : List.of("snapshotCreatedAt", "generatedAt")) {
            if (meta.containsKey(time)) require(integer(meta.get(time)) < integer(meta.get("snapshotExpiresAt")), MISMATCH);
        }
    }

    private static void validateNestedProof(Map<String, Object> meta) {
        if (!meta.containsKey("queryScope")) return;
        Map<String, Object> nested = object(meta.get("queryScope"));
        for (Map.Entry<String, Object> entry : nested.entrySet()) {
            String sourceKey = switch (entry.getKey()) {
                case "startInclusive" -> "requestedStart";
                case "endExclusive" -> "requestedEnd";
                default -> entry.getKey();
            };
            if (meta.containsKey(sourceKey))
                require(canonical(meta.get(sourceKey)).equals(canonical(entry.getValue())), MISMATCH);
        }
    }

    private static Set<Long> linkIds(Object input) {
        require(input instanceof List<?> list && list.size() <= 500, PROTOCOL);
        Set<Long> ids = new HashSet<>();
        for (Object value : (List<?>) input) {
            long id = integer(value);
            require(id > 0 && ids.add(id), PROTOCOL);
        }
        return ids;
    }

    private static long pages(long rows) { return rows / 500 + (rows % 500 == 0 ? 0 : 1); }

    private static long boundary(Object value, boolean end) {
        require(text(value), PROTOCOL);
        String text = (String) value;
        try {
            if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate day = LocalDate.parse(text);
                return (end ? day.plusDays(1) : day).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            }
            try { return Instant.parse(text).toEpochMilli(); }
            catch (DateTimeException notInstant) {
                return LocalDateTime.parse(text, LOCAL_TIME).atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
            }
        } catch (DateTimeException | ArithmeticException invalid) { throw failure(PROTOCOL); }
    }

    private static long integer(Object value) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger, PROTOCOL);
        try {
            long number = value instanceof BigInteger big ? big.longValueExact() : ((Number) value).longValue();
            require(number >= 0, PROTOCOL);
            return number;
        } catch (ArithmeticException invalid) { throw failure(PROTOCOL); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance), PROTOCOL);
        return (Map<String, Object>) value;
    }

    private static boolean reference(String value, int length) {
        return value != null && value.matches("[A-Za-z0-9_-]{1," + length + "}");
    }

    private static boolean text(Object value) {
        return value instanceof String string && !string.isBlank() && string.chars().noneMatch(Character::isISOControl);
    }

    private static String normalizeUrl(Object value) { return ((String) value).replaceFirst("(?i)^https?://", ""); }

    private static String canonical(Object value) {
        Object frozen = freezeJson(value, new IdentityHashMap<>(), 0);
        try { return JSON.writeValueAsString(frozen); }
        catch (JsonProcessingException invalid) { throw failure(PROTOCOL); }
    }

    /** Sort object keys only; retain arrays, nulls, quality details and per-row display identities. */
    private static Object freezeJson(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        require(depth <= 128, PROTOCOL);
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) return value;
        if (value instanceof Double number) { require(Double.isFinite(number), PROTOCOL); return value; }
        if (value instanceof Float number) { require(Float.isFinite(number), PROTOCOL); return value; }
        require(value instanceof Map<?, ?> || value instanceof List<?>, PROTOCOL);
        require(visiting.put(value, Boolean.TRUE) == null, PROTOCOL);
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    require(entry.getKey() instanceof String, PROTOCOL);
                    copy.put((String) entry.getKey(), freezeJson(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            List<Object> copy = new ArrayList<>();
            for (Object element : (List<?>) value) copy.add(freezeJson(element, visiting, depth + 1));
            return Collections.unmodifiableList(copy);
        } finally { visiting.remove(value); }
    }

    private static void require(boolean condition, String code) { if (!condition) throw failure(code); }
    private static IllegalArgumentException failure(String code) { return new IllegalArgumentException(code); }
    private record DecodedRequest(Map<String, Object> request, FrozenQueryScope scope, long start, long end) { }
}
