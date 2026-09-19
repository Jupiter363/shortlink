package com.jupiter.shortlink.admin.remote.analytics;

import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.exception.RemoteException;
import com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.jupiter.shortlink.contract.GroupMembersPage;

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

    /** Enumerate only IDs from one current, version-pinned group; display identities stay upstream. */
    public GroupMembersPage groupMembersPage(GroupMembersPage.Request request) {
        String tenant = UserContext.getUserId();
        String subject = UserContext.getUsername();
        Long authVersion = UserContext.getAuthVersion();
        if (tenant == null || subject == null || subject.isBlank() || authVersion == null || authVersion < 0)
            throw AnalyticsJsonClient.authorityPageFailure("FORBIDDEN");
        if (request == null || request.gid().length() > 64)
            throw AnalyticsJsonClient.authorityPageFailure("INVALID_QUERY");
        JSONObject resolved = client.groupMembersPage(request.asMap());
        try {
            if (resolved == null || !tenant.equals(resolved.get("tenantId"))
                    || !(resolved.get("links") instanceof List<?> links) || links.size() > GroupMembersPage.PAGE_SIZE)
                throw new IllegalArgumentException("Invalid authority identity");
            List<Object> ids = new ArrayList<>();
            for (Object value : links) {
                if (!(value instanceof Map<?, ?> link) || !request.gid().equals(link.get("gid")))
                    throw new IllegalArgumentException("Invalid authority member group");
                ids.add(link.get("linkId"));
            }
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("schemaVersion", GroupMembersPage.SCHEMA);
            page.put("tenantId", tenant); page.put("subjectId", subject); page.put("authVersion", authVersion);
            page.put("gid", request.gid()); page.put("ownershipVersion", resolved.get("ownershipVersion"));
            page.put("afterLinkId", request.afterLinkId()); page.put("linkIds", ids);
            page.put("nextCursor", resolved.get("nextCursor"));
            // Shared decoding rejects coerced IDs, duplicates, wrong order and incomplete continuation pages.
            GroupMembersPage result = GroupMembersPage.fromMap(page);
            result.requireMatches(request, tenant, subject, authVersion);
            return result;
        } catch (IllegalArgumentException invalid) {
            throw AnalyticsJsonClient.authorityPageFailure("QUERY_SCOPE_CHANGED".equals(invalid.getMessage())
                    ? "QUERY_SCOPE_CHANGED" : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        }
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
        return jobData(client.createJob(jobRequest(requestId, gid, fullShortUrl, start, end,
                queryKind, dimensions, filters)), true);
    }

    public Map<String, Object> recoverExistingJob(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters) {
        JSONObject response = client.recoverExistingJob(jobRequest(requestId, gid, fullShortUrl,
                start, end, queryKind, dimensions, filters));
        if (response == null || !"0".equals(response.getString("code"))) {
            throw AnalyticsJsonClient.recoveryFailure(response == null ? null : response.getString("code"));
        }
        if (!(response.get("data") instanceof Map<?, ?> data)
                || !(data.get("jobId") instanceof String jobId)
                || !jobId.matches("[A-Za-z0-9_-]{1,128}")
                || !(data.get("state") instanceof String state)
                || !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")
                        .contains(state)) {
            throw AnalyticsJsonClient.recoveryFailure("RECOVERY_PROTOCOL_UNAVAILABLE");
        }
        return jobData(response, true);
    }

    /** A current authorization of exactly these members. Empty means empty, never current group. */
    public Map<String, Object> authorizeSelectedScope(String gid, List<Long> linkIds, String ownershipVersion) {
        requireFrozenPrincipal();
        List<Long> members;
        try {
            if (gid == null || gid.isBlank() || gid.length() > 64 || gid.chars().anyMatch(Character::isISOControl)
                    || ownershipVersion != null && !hash(ownershipVersion)) throw new IllegalArgumentException();
            members = FrozenQueryScope.validatedMembers(linkIds);
        } catch (IllegalArgumentException invalid) {
            throw AnalyticsJsonClient.frozenFailure("INVALID_QUERY");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("gid", gid);
        request.put("linkIds", members);
        if (ownershipVersion != null) request.put("ownershipVersion", ownershipVersion);
        JSONObject response = client.resolveSelected(request);
        if (response != null && Boolean.FALSE.equals(response.get("allowed")))
            throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
        if (response == null || !"selected-scope/v1".equals(response.get("schemaVersion"))
                || !Boolean.TRUE.equals(response.get("allowed"))
                || !hash(response.get("ownershipVersion")) || !(response.get("links") instanceof List<?> links)
                || !(response.get("linkIds") instanceof List<?> rawIds))
            throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        List<Long> actual = selectedIds(rawIds);
        if (!UserContext.getUserId().equals(response.get("tenantId"))
                || !UserContext.getUsername().equals(response.get("subjectId"))
                || selectedInteger(response.get("authVersion")) != UserContext.getAuthVersion()
                || !gid.equals(response.get("gid")) || !members.equals(actual)
                || !FrozenQueryScope.memberHash(members).equals(response.get("memberHash")))
            throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
        if (ownershipVersion != null && !ownershipVersion.equals(response.get("ownershipVersion")))
            throw AnalyticsJsonClient.frozenFailure("QUERY_SCOPE_CHANGED");
        if (links.size() != members.size()) throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
        for (int i = 0; i < links.size(); i++) {
            if (!(links.get(i) instanceof Map<?, ?> link)
                    || selectedInteger(link.get("linkId")) != members.get(i) || !gid.equals(link.get("gid")))
                throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
            for (String field : List.of("domain", "shortUri", "fullShortUrl"))
                if (!(link.get(field) instanceof String text) || text.isBlank())
                    throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
            selectedInteger(link.get("ownershipVersion"));
        }
        return new LinkedHashMap<>(response);
    }

    public Map<String, Object> submitFrozenJob(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters, FrozenQueryScope scope) {
        return frozenJobData(client.createFrozenJob(frozenJobRequest(requestId, gid, fullShortUrl,
                start, end, queryKind, dimensions, filters, scope)));
    }

    public Map<String, Object> recoverFrozenJob(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters, FrozenQueryScope scope) {
        return frozenJobData(client.recoverFrozenJob(frozenJobRequest(requestId, gid, fullShortUrl,
                start, end, queryKind, dimensions, filters, scope)));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> releaseFrozenJobResult(String jobId, String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters, FrozenQueryScope scope) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}"))
            throw AnalyticsJsonClient.releaseFailure("INVALID_QUERY");
        JSONObject response = client.releaseFrozenJobResult(jobId, frozenJobRequest(requestId, gid, fullShortUrl,
                start, end, queryKind, dimensions, filters, scope));
        if (response == null || !(response.get("code") instanceof String code))
            throw AnalyticsJsonClient.releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(code)) throw AnalyticsJsonClient.releaseFailure(code);
        if (!(response.get("data") instanceof Map<?, ?> data)
                || !Set.of("jobId", "state", "rowCount", "byteCount", "pageCount", "errorCode", "expiresAt",
                        "resultState", "resultReady", "resultCode").containsAll(data.keySet())
                || !jobId.equals(data.get("jobId")) || !(data.get("state") instanceof String state)
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)
                || !"RELEASED".equals(data.get("resultState")) || !Boolean.FALSE.equals(data.get("resultReady"))
                || !"RESULT_RELEASED".equals(data.get("resultCode")) || !integerAtLeast(data.get("expiresAt"), 1))
            throw AnalyticsJsonClient.releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        for (String count : List.of("rowCount", "byteCount", "pageCount"))
            if (data.containsKey(count) && !integerAtLeast(data.get(count), 0))
                throw AnalyticsJsonClient.releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) data);
        result.put("status", state);
        return result;
    }

    private Map<String, Object> frozenJobRequest(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters, FrozenQueryScope scope) {
        requireFrozenPrincipal();
        if (scope == null || fullShortUrl != null || requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")
                || queryKind == null || !Set.of("METRICS", "ACCESS_RECORDS", "LINK_METRICS", "DIMENSION_BREAKDOWN").contains(queryKind)
                || !"DIMENSION_BREAKDOWN".equals(queryKind) && (dimensions != null || filters != null)
                || "DIMENSION_BREAKDOWN".equals(queryKind) && (dimensions == null || dimensions.isEmpty()))
            throw AnalyticsJsonClient.frozenFailure("INVALID_QUERY");
        long from, until;
        try {
            from = parse(start, false); until = parse(end, true);
            if (from < 0 || until <= from || until - from > Duration.ofDays(180).toMillis())
                throw new IllegalArgumentException();
        } catch (ClientException | IllegalArgumentException invalid) {
            throw AnalyticsJsonClient.frozenFailure("INVALID_QUERY");
        }
        authorizeSelectedScope(gid, scope.linkIds(), null);
        var query = new AnalyticsQueryRequest(UserContext.getUserId(), UserContext.getUsername(),
                UserContext.getAuthVersion(), gid, scope.linkIds(), from, until, null, "REQUESTED",
                null, null, 500, queryKind, dimensions, filters, scope);
        return Map.of("requestId", requestId, "query", query);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> frozenJobData(JSONObject response) {
        if (response == null || !(response.get("code") instanceof String code))
            throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        AnalyticsJsonClient.rejectCapacity(response);
        if (!"0".equals(code)) throw AnalyticsJsonClient.frozenFailure(code);
        if (!(response.get("data") instanceof Map<?, ?> data)
                || !(data.get("jobId") instanceof String jobId) || !jobId.matches("[A-Za-z0-9_-]{1,128}")
                || !(data.get("state") instanceof String state)
                || !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state))
            throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) data);
        result.put("status", Set.of("QUEUED", "RUNNING").contains(state) ? "PENDING" : state);
        resultReadiness(result, state, () -> AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"));
        return result;
    }

    /** Execution success and retained result availability are independent in the new protocol. */
    private static void resultReadiness(Map<String, Object> result, String state,
            java.util.function.Supplier<RemoteException> invalid) {
        if (!result.containsKey("resultState")) {
            // Old Analytics versions did not expose result lifecycle fields.
            if (result.get("resultCode") != null) throw invalid.get();
            result.put("resultReady", "SUCCEEDED".equals(state));
            return;
        }
        Object resultState = result.get("resultState");
        if (!(resultState instanceof String lifecycle)
                || !Set.of("PENDING", "AVAILABLE", "UNAVAILABLE", "RELEASED").contains(lifecycle)
                || !(result.get("resultReady") instanceof Boolean ready)
                || ready != "AVAILABLE".equals(lifecycle)
                || "AVAILABLE".equals(lifecycle) && !"SUCCEEDED".equals(state)
                || "PENDING".equals(lifecycle) && !Set.of("QUEUED", "RUNNING").contains(state)
                || "UNAVAILABLE".equals(lifecycle) && !Set.of("FAILED", "CANCELLED").contains(state)
                || "RELEASED".equals(lifecycle) && !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)
                || "RELEASED".equals(lifecycle) && !"RESULT_RELEASED".equals(result.get("resultCode"))
                || !"RELEASED".equals(lifecycle) && result.get("resultCode") != null)
            throw invalid.get();
        result.put("resultReady", ready);
    }

    private static boolean integerAtLeast(Object value, long minimum) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger)) return false;
        try { return new java.math.BigInteger(value.toString()).longValueExact() >= minimum; }
        catch (ArithmeticException invalid) { return false; }
    }

    private static void requireFrozenPrincipal() {
        if (UserContext.getUserId() == null || UserContext.getUsername() == null || UserContext.getAuthVersion() == null)
            throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
    }

    private static boolean hash(Object value) { return value instanceof String text && text.matches("[a-f0-9]{64}"); }

    private static long selectedInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger)) throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        try {
            long result = new java.math.BigInteger(value.toString()).longValueExact();
            if (result < 0) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException invalid) { throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"); }
    }

    private static List<Long> selectedIds(List<?> values) {
        try { return FrozenQueryScope.validatedMembers(values.stream().map(AgentAnalyticsFacade::selectedInteger).toList()); }
        catch (IllegalArgumentException invalid) { throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jobRequest(String requestId, String gid, String fullShortUrl,
            String start, String end, String queryKind, List<String> dimensions,
            List<Map<String, Object>> filters) {
        requirePrincipal();
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9_-]{1,96}") || queryKind == null
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
        return Map.of("requestId", requestId, "query", query);
    }

    public Map<String, Object> jobStatus(String jobId) {
        Map<String, Object> result = readJobData(client.job(jobId, "status", jobIdentity()));
        if (!jobId.equals(result.get("jobId"))
                || !(result.get("state") instanceof String state)
                || !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state))
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        result.put("status", Set.of("QUEUED", "RUNNING").contains(state) ? "PENDING" : state);
        resultReadiness(result, state, () -> AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE"));
        return result;
    }

    public Map<String, Object> jobPage(String jobId, int pageIndex, int size) {
        if (pageIndex < 0 || size != 500)
            throw AnalyticsJsonClient.readFailure("INVALID_QUERY");
        Map<String, Object> request = jobIdentity();
        request.put("pageIndex", pageIndex);
        request.put("size", size);
        Map<String, Object> result = readJobData(client.job(jobId, "page", request));
        if (!(result.get("meta") instanceof Map<?, ?> meta)
                || !(result.get("items") instanceof List<?> items)
                || !jobId.equals(meta.get("snapshotId"))
                || !matchesPageIndex(meta.get("pageIndex"), pageIndex)
                || items.stream().anyMatch(item -> !(item instanceof Map<?, ?>)))
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        enrichJobIdentities(result);
        return result;
    }

    private static boolean matchesPageIndex(Object value, int expected) {
        return (value instanceof Integer || value instanceof Long)
                && ((Number) value).longValue() == expected;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJobData(JSONObject response) {
        if (response == null || !(response.get("code") instanceof String code))
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(code)) throw AnalyticsJsonClient.readFailure(code);
        if (!(response.get("data") instanceof Map<?, ?> data))
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        return new LinkedHashMap<>((Map<String, Object>) data);
    }

    private Map<String, Object> resolveForJobRead(String gid, List<Long> linkIds) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("gid", gid);
        if (linkIds != null) request.put("linkIds", linkIds);
        JSONObject scope = client.resolveForJobRead(request);
        if (scope == null || !(scope.get("tenantId") instanceof String)
                || !(scope.get("links") instanceof List<?> links)
                || links.stream().anyMatch(link -> !(link instanceof Map<?, ?>)))
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        if (!UserContext.getUserId().equals(scope.get("tenantId")))
            throw AnalyticsJsonClient.readFailure("FORBIDDEN");
        return scope;
    }

    private static long readLinkId(Object value) {
        try {
            return longValue(value);
        } catch (RemoteException invalid) {
            throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        }
    }

    @SuppressWarnings("unchecked")
    private void enrichJobIdentities(Map<String, Object> result) {
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) result.get("meta"));
        if (meta.containsKey("scopeProof")) {
            enrichFrozenJobIdentities(result, meta);
            return;
        }
        // Older frozen jobs have no scope metadata. Preserve them without inventing an identity.
        if (!(meta.get("linkIds") instanceof List<?> rawIds) || meta.get("gid") == null) return;
        List<Long> ids = rawIds.stream().map(AgentAnalyticsFacade::readLinkId).toList();
        Map<String, Object> scope = resolveForJobRead(meta.get("gid").toString(), ids);
        List<Map<String, Object>> links = (List<Map<String, Object>>) scope.get("links");
        Map<Long, Map<String, Object>> identities = new LinkedHashMap<>();
        for (Map<String, Object> link : links) identities.put(readLinkId(link.get("linkId")), link);
        if (!identities.keySet().containsAll(ids))
            throw AnalyticsJsonClient.readFailure("FORBIDDEN");
        Map<String, Object> fullGroup = resolveForJobRead(meta.get("gid").toString(), null);
        var currentIds = new java.util.HashSet<Long>();
        for (var link : (List<Map<String, Object>>) fullGroup.get("links"))
            currentIds.add(readLinkId(link.get("linkId")));
        meta.put("groupScopeComplete", fullGroup.get("nextCursor") == null
                && currentIds.equals(new java.util.HashSet<>(ids)));
        if (ids.size() == 1) meta.put("fullShortUrl", identities.get(ids.get(0)).get("fullShortUrl"));
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object value : (List<?>) result.get("items")) {
            if (!(value instanceof Map<?, ?> row))
                throw AnalyticsJsonClient.readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) row);
            if (item.get("linkId") != null) {
                Map<String, Object> identity = identities.get(readLinkId(item.get("linkId")));
                if (identity == null) throw AnalyticsJsonClient.readFailure("FORBIDDEN");
                for (String field : List.of("gid", "domain", "shortUri", "fullShortUrl"))
                    item.put(field, identity.get(field));
            }
            enriched.add(item);
        }
        result.put("meta", meta);
        result.put("items", enriched);
    }

    @SuppressWarnings("unchecked")
    private void enrichFrozenJobIdentities(Map<String, Object> result, Map<String, Object> meta) {
        FrozenQueryScope scope;
        String version;
        try {
            if (!(meta.get("scopeProof") instanceof Map<?, ?> proof)) throw new IllegalArgumentException();
            scope = FrozenQueryScope.fromProof(proof);
            version = (String) proof.get("authorizedSelectionVersion");
            if (!(meta.get("gid") instanceof String) || !(meta.get("linkIds") instanceof List<?> ids)
                    || !scope.linkIds().equals(selectedIds(ids))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw AnalyticsJsonClient.frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        }
        Map<String, Object> selected = authorizeSelectedScope((String) meta.get("gid"), scope.linkIds(), version);
        Map<Long, Map<String, Object>> identities = new LinkedHashMap<>();
        for (Object value : (List<?>) selected.get("links")) {
            Map<String, Object> identity = (Map<String, Object>) value;
            identities.put(selectedInteger(identity.get("linkId")), identity);
        }
        // A complete shard is not proof of either the current group or the full frozen parent.
        meta.put("groupScopeComplete", false);
        if (scope.linkIds().size() == 1)
            meta.put("fullShortUrl", identities.get(scope.linkIds().get(0)).get("fullShortUrl"));
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (Object value : (List<?>) result.get("items")) {
            Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) value);
            if (item.get("linkId") != null) {
                Map<String, Object> identity = identities.get(selectedInteger(item.get("linkId")));
                if (identity == null) throw AnalyticsJsonClient.frozenFailure("FORBIDDEN");
                for (String field : List.of("gid", "domain", "shortUri", "fullShortUrl"))
                    item.put(field, identity.get(field));
            }
            enriched.add(item);
        }
        result.put("meta", meta);
        result.put("items", enriched);
    }

    private Map<String, Object> jobIdentity() {
        if (UserContext.getUserId() == null || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null)
            throw AnalyticsJsonClient.readFailure("FORBIDDEN");
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("tenantId", UserContext.getUserId());
        identity.put("subjectId", UserContext.getUsername());
        identity.put("authVersion", UserContext.getAuthVersion());
        return identity;
    }

    private Map<String, Object> jobData(JSONObject response, boolean status) {
        AnalyticsJsonClient.rejectCapacity(response);
        if (!"0".equals(response.getString("code")) || response.getJSONObject("data") == null)
            throw new RemoteException("Statistics job unavailable: " + response.getString("code"));
        Map<String, Object> result = new LinkedHashMap<>(response.getJSONObject("data"));
        if (status) {
            String state = String.valueOf(result.get("state"));
            if (!Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state))
                throw new RemoteException("Statistics job state is invalid");
            result.put("status", Set.of("QUEUED", "RUNNING").contains(state) ? "PENDING" : state);
            resultReadiness(result, state, () -> new RemoteException("Statistics job result state is invalid"));
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
