package com.jupiter.shortlink.admin.remote.analytics;

import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.exception.RemoteException;
import com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AgentAnalyticsFacade {
    private static final String MEMORY_CAPACITY_MESSAGE = "ClickHouse memory capacity exceeded";
    private final AnalyticsJsonClient client;

    public AgentAnalyticsFacade(AnalyticsJsonClient client) {
        this.client = client;
    }

    public Map<String, Object> resolve(String gid, String fullShortUrl, List<Long> linkIds) {
        return resolve(gid, fullShortUrl, linkIds, null, null);
    }

    public Map<String, Object> resolve(
            String gid,
            String fullShortUrl,
            List<Long> linkIds,
            Long afterLinkId,
            String ownershipVersion) {
        requirePrincipal();
        Map<String, Object> request = new LinkedHashMap<>();
        if (gid != null) request.put("gid", gid);
        if (fullShortUrl != null) request.put("fullShortUrl", fullShortUrl);
        if (linkIds != null) request.put("linkIds", linkIds);
        if (afterLinkId != null) request.put("afterLinkId", afterLinkId);
        if (ownershipVersion != null) request.put("ownershipVersion", ownershipVersion);
        JSONObject resolved = client.resolve(request);
        if (!UserContext.getUserId().equals(resolved.getString("tenantId"))
                || !(resolved.get("links") instanceof List<?>)) {
            throw new RemoteException("Resource authority returned an invalid tenant scope");
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    public StatsEnvelope query(
            String gid,
            String fullShortUrl,
            String start,
            String end,
            List<String> windows,
            String snapshotId,
            String cursor,
            int pageSize,
            String queryKind) {
        return query(
                gid,
                fullShortUrl,
                start,
                end,
                windows,
                snapshotId,
                cursor,
                pageSize,
                queryKind,
                null);
    }

    @SuppressWarnings("unchecked")
    public StatsEnvelope query(
            String gid,
            String fullShortUrl,
            String start,
            String end,
            List<String> windows,
            String snapshotId,
            String cursor,
            int pageSize,
            String queryKind,
            List<Long> scopedLinkIds) {
        return query(gid, fullShortUrl, start, end, windows, snapshotId, cursor, pageSize,
                queryKind, scopedLinkIds, null, null);
    }

    @SuppressWarnings("unchecked")
    public StatsEnvelope query(String gid, String fullShortUrl, String start, String end,
            List<String> windows, String snapshotId, String cursor, int pageSize, String queryKind,
            List<Long> scopedLinkIds, List<String> dimensions, List<Map<String, Object>> filters) {
        requirePrincipal();
        if (pageSize < 1 || pageSize > 500)
            throw new ClientException("Analytics pageSize must be between 1 and 500");
        long endTime = parse(end, true);
        long startTime =
                start == null && windows != null
                        ? endTime - Duration.ofDays(7).toMillis()
                        : parse(start, false);
        if (startTime < 0
                || endTime <= startTime
                || endTime - startTime > Duration.ofDays(7).toMillis()) {
            throw new ClientException(
                    "Analytics ranges must be positive and no longer than seven days");
        }
        Map<String, Object> resolved = resolve(gid, fullShortUrl, scopedLinkIds);
        List<Map<String, Object>> links = (List<Map<String, Object>>) resolved.get("links");
        if (links.size() > 500 || resolved.get("nextCursor") != null)
            throw new ClientException("TOO_LARGE: narrow the authorized analytics scope");
        Map<Long, Map<String, Object>> identities = new LinkedHashMap<>();
        for (Map<String, Object> link : links) identities.put(longValue(link.get("linkId")), link);
        JSONObject response =
                client.query(
                        new AnalyticsQueryRequest(
                                UserContext.getUserId(),
                                UserContext.getUsername(),
                                UserContext.getAuthVersion(),
                                gid,
                                List.copyOf(identities.keySet()),
                                startTime,
                                endTime,
                                windows,
                                windows == null || windows.isEmpty()
                                        ? "REQUESTED"
                                        : "COMMON_AVAILABLE_END",
                                snapshotId,
                                cursor,
                                pageSize,
                                queryKind, dimensions, filters));
        if (!"0".equals(response.getString("code"))) {
            String error = "Analytics query unavailable: " + response.getString("code");
            // Only this normalized diagnostic is safe to expose; upstream text may contain SQL.
            if ("UNAVAILABLE".equals(response.getString("code"))
                    && MEMORY_CAPACITY_MESSAGE.equals(response.getString("message"))) {
                error += " (" + MEMORY_CAPACITY_MESSAGE + ")";
            }
            throw new RemoteException(error);
        }
        JSONObject envelope = response.getJSONObject("data");
        if (envelope == null
                || !(envelope.get("meta") instanceof Map<?, ?> metadata)
                || !(envelope.get("items") instanceof List<?> items))
            throw new RemoteException("Analytics envelope contract is invalid");
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) metadata);
        meta.put("tenantId", UserContext.getUserId());
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object value : items) {
            if (!(value instanceof Map<?, ?> row))
                throw new RemoteException("Analytics item contract is invalid");
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) row);
            if ("DIMENSION_BREAKDOWN".equals(queryKind)) {
                enriched.add(item);
                continue;
            }
            Map<String, Object> identity = identities.get(longValue(item.get("linkId")));
            if (identity == null)
                throw new RemoteException("Analytics returned an unauthorized link");
            for (String field : List.of("gid", "domain", "shortUri", "fullShortUrl"))
                item.put(field, identity.get(field));
            enriched.add(item);
        }
        return new StatsEnvelope((Map<String, Object>) envelope.get("metrics"), enriched, meta);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitJob(
            String requestId,
            String gid,
            String fullShortUrl,
            String start,
            String end,
            String queryKind) {
        return submitJob(requestId, gid, fullShortUrl, start, end, queryKind, null, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitJob(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters) {
        requirePrincipal();
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{1,128}")
                || !Set.of("METRICS", "ACCESS_RECORDS", "LINK_METRICS", "DIMENSION_BREAKDOWN").contains(queryKind))
            throw new ClientException("Invalid statistics job request");
        long startTime = parse(start, false), endTime = parse(end, true);
        if (startTime < 0
                || endTime <= startTime
                || endTime - startTime > Duration.ofDays(180).toMillis())
            throw new ClientException("Statistics job range must be positive and at most 180 days");
        Map<String, Object> resolved = resolve(gid, fullShortUrl, null);
        List<Map<String, Object>> links = (List<Map<String, Object>>) resolved.get("links");
        if (links.size() > 500 || resolved.get("nextCursor") != null)
            throw new ClientException("TOO_LARGE: narrow the authorized statistics job scope");
        var query =
                new AnalyticsQueryRequest(
                        UserContext.getUserId(),
                        UserContext.getUsername(),
                        UserContext.getAuthVersion(),
                        gid,
                        links.stream().map(link -> longValue(link.get("linkId"))).toList(),
                        startTime,
                        endTime,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        500,
                        queryKind, dimensions, filters);
        return jobData(client.createJob(Map.of("requestId", requestId, "query", query)), true);
    }

    public Map<String, Object> jobStatus(String jobId) {
        return jobData(client.job(jobId, "status", jobIdentity()), true);
    }

    public Map<String, Object> jobPage(String jobId, int pageIndex, int size) {
        if (pageIndex < 0 || size < 1 || size > 500)
            throw new ClientException("Invalid statistics job result page");
        Map<String, Object> request = jobIdentity();
        request.put("pageIndex", pageIndex);
        request.put("size", size);
        Map<String, Object> result = jobData(client.job(jobId, "page", request), false);
        if (!(result.get("meta") instanceof Map<?, ?>) || !(result.get("items") instanceof List<?>))
            throw new RemoteException("Statistics job result envelope is invalid");
        enrichJobIdentities(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void enrichJobIdentities(Map<String, Object> result) {
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) result.get("meta"));
        // Older frozen jobs have no scope metadata. Preserve them without inventing an identity.
        if (!(meta.get("linkIds") instanceof List<?> rawIds) || meta.get("gid") == null) return;
        List<Long> ids = rawIds.stream().map(AgentAnalyticsFacade::longValue).toList();
        Map<String, Object> scope = resolve(meta.get("gid").toString(), null, ids);
        List<Map<String, Object>> links = (List<Map<String, Object>>) scope.get("links");
        Map<Long, Map<String, Object>> identities = new LinkedHashMap<>();
        for (Map<String, Object> link : links) identities.put(longValue(link.get("linkId")), link);
        if (!identities.keySet().containsAll(ids))
            throw new RemoteException("Statistics job scope is no longer authorized");
        Map<String, Object> fullGroup = resolve(meta.get("gid").toString(), null, null);
        var currentIds = new java.util.HashSet<Long>();
        for (var link : (List<Map<String, Object>>) fullGroup.get("links"))
            currentIds.add(longValue(link.get("linkId")));
        meta.put("groupScopeComplete", fullGroup.get("nextCursor") == null
                && currentIds.equals(new java.util.HashSet<>(ids)));
        if (ids.size() == 1) meta.put("fullShortUrl", identities.get(ids.get(0)).get("fullShortUrl"));
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object value : (List<?>) result.get("items")) {
            if (!(value instanceof Map<?, ?> row))
                throw new RemoteException("Statistics job item is invalid");
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) row);
            if (item.get("linkId") != null) {
                Map<String, Object> identity = identities.get(longValue(item.get("linkId")));
                if (identity == null) throw new RemoteException("Statistics job returned an unauthorized link");
                for (String field : List.of("gid", "domain", "shortUri", "fullShortUrl"))
                    item.put(field, identity.get(field));
            }
            enriched.add(item);
        }
        result.put("meta", meta);
        result.put("items", enriched);
    }

    private Map<String, Object> jobIdentity() {
        requirePrincipal();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("tenantId", UserContext.getUserId());
        identity.put("subjectId", UserContext.getUsername());
        identity.put("authVersion", UserContext.getAuthVersion());
        return identity;
    }

    private Map<String, Object> jobData(JSONObject response, boolean status) {
        if (!"0".equals(response.getString("code")) || response.getJSONObject("data") == null)
            throw new RemoteException("Statistics job unavailable: " + response.getString("code"));
        Map<String, Object> result = new LinkedHashMap<>(response.getJSONObject("data"));
        if (status) {
            String state = String.valueOf(result.get("state"));
            if (!Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state))
                throw new RemoteException("Statistics job state is invalid");
            result.put("status", Set.of("QUEUED", "RUNNING").contains(state) ? "PENDING" : state);
            result.put("resultReady", "SUCCEEDED".equals(state));
        }
        return result;
    }

    private static void requirePrincipal() {
        if (UserContext.getUserId() == null
                || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null) {
            throw new ClientException("Current authenticated analytics scope is required");
        }
    }

    public static long parse(String value, boolean end) {
        if (value == null || value.isBlank())
            throw new ClientException("Analytics time boundary is required");
        try {
            if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate day = LocalDate.parse(value);
                return (end ? day.plusDays(1) : day)
                        .atStartOfDay(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli();
            }
            try {
                return Instant.parse(value).toEpochMilli();
            } catch (java.time.format.DateTimeParseException ignored) {
                return LocalDateTime.parse(
                                value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant()
                        .toEpochMilli();
            }
        } catch (RuntimeException invalid) {
            throw new ClientException("Analytics time boundary is invalid");
        }
    }

    private static long longValue(Object value) {
        if (value == null) throw new RemoteException("Analytics linkId is missing");
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException invalid) {
            throw new RemoteException("Analytics linkId is invalid");
        }
    }
}
