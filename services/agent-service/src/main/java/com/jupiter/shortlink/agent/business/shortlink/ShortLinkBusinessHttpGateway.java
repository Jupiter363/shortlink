package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import com.jupiter.shortlink.contract.FrozenQueryScope;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ShortLinkBusinessHttpGateway implements ShortLinkBusinessGateway {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    private static final String USERNAME_HEADER = "X-Agent-Username";

    private static final String STATISTICS_JOBS_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";

    private static final String STATISTICS_READ_PROTOCOL_UNAVAILABLE = "STATISTICS_READ_PROTOCOL_UNAVAILABLE";

    private static final String STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE = "STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE";

    private static final String FROZEN_SCOPE_PROTOCOL_UNAVAILABLE = "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE";

    private static final String STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE = "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE";

    private static final String STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE = "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE";

    private static final String QUERY_CAPACITY_EXHAUSTED = "QUERY_CAPACITY_EXHAUSTED";

    private static final String FROZEN_JOBS_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs";

    private static final String AUTHORIZE_STATISTICS_SCOPE_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/authorize-scope";

    private final AgentProperties agentProperties;

    private final RestTemplate restTemplate;
    private com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport transport;

    @Autowired
    public ShortLinkBusinessHttpGateway(
            AgentProperties agentProperties,
            com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport transport) {
        this(agentProperties, (RestTemplate) null);
        this.transport = transport;
    }

    ShortLinkBusinessHttpGateway(AgentProperties agentProperties, RestTemplate restTemplate) {
        this.agentProperties = agentProperties;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
        try {
            Map<String, Object> body;
            if (transport != null)
                body =
                        transport.exchange(
                                "GET",
                                uri(path, queryParams),
                                headers(context).toSingleValueMap(),
                                null);
            else {
                ResponseEntity<Map> response =
                        restTemplate.exchange(
                                uri(path, queryParams),
                                HttpMethod.GET,
                                new HttpEntity<>(headers(context)),
                                Map.class);
                body = response.getBody();
            }
            if (body == null) {
                return ToolResult.failure("Short link business API returned empty response");
            }
            if (!isSuccess(body)) {
                return ToolResult.failure(message(body));
            }
            return ToolResult.success(body.get("data"));
        } catch (RuntimeException ex) {
            return ToolResult.failure("Short link business API is unavailable or unauthorized");
        }
    }

    @Override
    public ToolResult post(String path, ToolContext context, Map<String, Object> payload) {
        return post(path, context, payload, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        if (!statisticsReadPrincipal(context)) return statisticsSubmitFailure("FORBIDDEN");
        final Map<String, Object> request;
        try {
            request = frozenStatisticsSubmission(frozenRequest);
        } catch (RuntimeException invalid) {
            return statisticsSubmitFailure("INVALID_QUERY");
        }
        try {
            Map<String, Object> body;
            if (transport != null) {
                body = transport.exchange("POST", uri(STATISTICS_JOBS_PATH, Map.of()),
                        headers(context).toSingleValueMap(), request);
            } else {
                ResponseEntity<Map> response = restTemplate.exchange(uri(STATISTICS_JOBS_PATH, Map.of()),
                        HttpMethod.POST, new HttpEntity<>(request, headers(context)), Map.class);
                if (response.getStatusCode().value() != 200)
                    return statisticsSubmitHttpFailure(response.getStatusCode().value());
                body = response.getBody();
            }
            if (body == null) return statisticsSubmitFailure(STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE);
            Object code = body.get("code");
            boolean success = "0".equals(code) || code instanceof Number number && "0".equals(number.toString());
            if (!success) {
                if (QUERY_CAPACITY_EXHAUSTED.equals(code))
                    return statisticsCapacityFailure(body, STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE);
                String safeCode = code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}")
                        ? value : STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE;
                return statisticsSubmitFailure(safeCode);
            }
            if (Boolean.FALSE.equals(body.get("success"))
                    || body.containsKey("success") && !(body.get("success") instanceof Boolean)
                    || !validRecoveredJob(body.get("data"))) {
                return statisticsSubmitFailure(STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE);
            }
            return ToolResult.success(body.get("data"));
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return statisticsSubmitHttpFailure(failure.statusCode());
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return statisticsSubmitHttpFailure(failure.getStatusCode().value());
        } catch (SecurityException denied) {
            return statisticsSubmitFailure("FORBIDDEN");
        } catch (RuntimeException failure) {
            return statisticsSubmitFailure(statisticsReadDecodeFailure(failure)
                    ? STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
        }
    }

    /** Closed wire shape is also a finite JSON copy: no recursive arbitrary values reach serialization. */
    private static Map<String, Object> frozenStatisticsSubmission(Map<String, Object> source) {
        Set<String> fields = Set.of("requestId", "gid", "startDate", "endDate", "queryKind",
                "fullShortUrl", "dimensions", "filters");
        if (source == null || !fields.containsAll(source.keySet())
                || !(source.get("requestId") instanceof String requestId)
                || !requestId.matches("[A-Za-z0-9_-]{1,96}")) throw new IllegalArgumentException();
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String field : List.of("requestId", "gid", "startDate", "endDate", "queryKind")) {
            if (!(source.get(field) instanceof String text) || text.isBlank()
                    || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            copy.put(field, text);
        }
        String kind = (String) copy.get("queryKind");
        if (!Set.of("METRICS", "ACCESS_RECORDS", "LINK_METRICS", "DIMENSION_BREAKDOWN").contains(kind))
            throw new IllegalArgumentException();
        if (source.containsKey("fullShortUrl")) {
            if (!(source.get("fullShortUrl") instanceof String url) || url.isBlank()
                    || url.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            copy.put("fullShortUrl", url);
        }
        if ("DIMENSION_BREAKDOWN".equals(kind)) {
            copy.put("dimensions", DimensionQuery.dimensions(source.get("dimensions")));
            if (source.containsKey("filters")) {
                if (!(source.get("filters") instanceof List<?> filters)) throw new IllegalArgumentException();
                DimensionQuery.filters(filters); // Validate without rewriting the frozen request's ordering/values.
                List<Map<String, Object>> frozen = new ArrayList<>();
                for (Object entry : filters) {
                    Map<?, ?> filter = (Map<?, ?>) entry;
                    if (!Set.of("dimension", "operator", "values").containsAll(filter.keySet()))
                        throw new IllegalArgumentException();
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("dimension", filter.get("dimension"));
                    value.put("operator", filter.get("operator"));
                    if (filter.containsKey("values")) value.put("values", filter.get("values") == null
                            ? null : List.copyOf((List<?>) filter.get("values")));
                    frozen.add(Collections.unmodifiableMap(value));
                }
                copy.put("filters", List.copyOf(frozen));
            }
        } else if (source.containsKey("dimensions") || source.containsKey("filters")) {
            throw new IllegalArgumentException();
        }
        return Collections.unmodifiableMap(copy);
    }

    private static ToolResult statisticsSubmitHttpFailure(int status) {
        String code = status == 401 || status == 403 ? "FORBIDDEN"
                : Set.of(404, 405, 501).contains(status) ? STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE";
        return statisticsSubmitFailure(code);
    }

    private static ToolResult statisticsSubmitFailure(String code) {
        return failure(code, "Statistics job submission failed: " + code);
    }

    @Override
    public ToolResult submitFrozenStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return frozenStatisticsJob(context, frozenRequest, false);
    }

    @Override
    public ToolResult recoverExistingFrozenStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        return frozenStatisticsJob(context, frozenRequest, true);
    }

    private ToolResult frozenStatisticsJob(ToolContext context, Map<String, Object> source, boolean recovery) {
        if (!statisticsReadPrincipal(context)) return frozenStatisticsFailure("FORBIDDEN");
        final Map<String, Object> request;
        try {
            request = frozenSetStatisticsSubmission(source);
        } catch (RuntimeException invalid) {
            return frozenStatisticsFailure("INVALID_QUERY");
        }
        return frozenStatisticsPost(FROZEN_JOBS_PATH + (recovery ? "/recover-existing" : ""), context, request, false);
    }

    private static Map<String, Object> frozenSetStatisticsSubmission(Map<String, Object> source) {
        if (source == null || source.containsKey("fullShortUrl")
                || !(source.get("scope") instanceof Map<?, ?> scope)) throw new IllegalArgumentException();
        FrozenQueryScope selected = FrozenQueryScope.fromMap(scope);
        Map<String, Object> query = new LinkedHashMap<>(source);
        query.remove("scope");
        Map<String, Object> copy = new LinkedHashMap<>(frozenStatisticsSubmission(query));
        String startText = (String) copy.get("startDate"), endText = (String) copy.get("endDate");
        java.time.LocalDate start = java.time.LocalDate.parse(startText), end = java.time.LocalDate.parse(endText);
        if (((String) copy.get("gid")).length() > 64 || !start.toString().equals(startText)
                || !end.toString().equals(endText) || end.isBefore(start)) throw new IllegalArgumentException();
        copy.put("scope", selected.asMap());
        return Collections.unmodifiableMap(copy);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult releaseStatisticsJobResult(ToolContext context, String jobId,
                                                Map<String, Object> originalFrozenRequest) {
        if (!statisticsReadPrincipal(context)) return statisticsReleaseFailure("FORBIDDEN");
        if (!validStatisticsJobId(jobId)) return statisticsReleaseFailure("INVALID_QUERY");
        final Map<String, Object> request;
        try {
            request = frozenSetStatisticsSubmission(originalFrozenRequest);
        } catch (RuntimeException invalid) {
            return statisticsReleaseFailure("INVALID_QUERY");
        }
        try {
            String path = FROZEN_JOBS_PATH + "/" + jobId + "/release-result";
            Map<String, Object> body;
            if (transport != null) {
                body = transport.exchange("POST", uri(path, Map.of()), headers(context).toSingleValueMap(), request);
            } else {
                ResponseEntity<Map> response = restTemplate.exchange(uri(path, Map.of()), HttpMethod.POST,
                        new HttpEntity<>(request, headers(context)), Map.class);
                if (response.getStatusCode().value() != 200)
                    return statisticsReleaseHttpFailure(response.getStatusCode().value());
                body = response.getBody();
            }
            if (body == null) return statisticsReleaseFailure(STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE);
            Object code = body.get("code");
            if (!("0".equals(code) || code instanceof Number number && "0".equals(number.toString()))) {
                return statisticsReleaseFailure(code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}")
                        ? value : STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE);
            }
            if (body.containsKey("success") && !Boolean.TRUE.equals(body.get("success"))
                    || !(body.get("data") instanceof Map<?, ?> data)
                    || !validReleasedJob(data, jobId))
                return statisticsReleaseFailure(STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE);
            // A release receipt is a short protocol fact, not an arbitrary remote result payload.
            return ToolResult.success(Map.of("jobId", jobId, "state", data.get("state"),
                    "resultState", "RELEASED", "resultCode", "RESULT_RELEASED", "resultReady", false,
                    "expiresAt", selectedInteger(data.get("expiresAt"))));
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return statisticsReleaseHttpFailure(failure.statusCode());
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return statisticsReleaseHttpFailure(failure.getStatusCode().value());
        } catch (SecurityException denied) {
            return statisticsReleaseFailure("FORBIDDEN");
        } catch (RuntimeException failure) {
            return statisticsReleaseFailure(statisticsReadDecodeFailure(failure)
                    ? STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult cancelStatisticsJob(ToolContext context, String jobId) {
        if (!statisticsReadPrincipal(context)) return statisticsCancelFailure("FORBIDDEN");
        if (!validStatisticsJobId(jobId)) return statisticsCancelFailure("INVALID_QUERY");
        try {
            String path = STATISTICS_JOBS_PATH + "/" + jobId + "/cancel";
            Map<String, Object> body;
            if (transport != null) {
                body = transport.exchange("POST", uri(path, Map.of()), headers(context).toSingleValueMap(), Map.of());
            } else {
                ResponseEntity<Map> response = restTemplate.exchange(uri(path, Map.of()), HttpMethod.POST,
                        new HttpEntity<>(Map.of(), headers(context)), Map.class);
                if (response.getStatusCode().value() != 200)
                    return statisticsCancelHttpFailure(response.getStatusCode().value());
                body = response.getBody();
            }
            if (body == null) return statisticsCancelFailure(STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE);
            Object code = body.get("code");
            if (!("0".equals(code) || code instanceof Number number && "0".equals(number.toString())))
                return statisticsCancelFailure(code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}")
                        ? value : STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE);
            if (body.containsKey("success") && !Boolean.TRUE.equals(body.get("success"))
                    || !(body.get("data") instanceof Map<?, ?> data) || !validCancelledJob(data, jobId))
                return statisticsCancelFailure(STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE);
            Map<String, Object> receipt = new LinkedHashMap<>();
            for (String field : List.of("jobId", "state", "expiresAt", "resultState", "resultReady", "resultCode"))
                receipt.put(field, data.get(field));
            // SUCCEEDED/FAILED mean the cancellation opportunity ended, not that it was cancelled.
            return ToolResult.success(Collections.unmodifiableMap(receipt));
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return statisticsCancelHttpFailure(failure.statusCode());
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return statisticsCancelHttpFailure(failure.getStatusCode().value());
        } catch (SecurityException denied) {
            return statisticsCancelFailure("FORBIDDEN");
        } catch (RuntimeException failure) {
            return statisticsCancelFailure(statisticsReadDecodeFailure(failure)
                    ? STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
        }
    }

    private static boolean validCancelledJob(Map<?, ?> data, String jobId) {
        if (!Set.of("jobId", "state", "expiresAt", "resultState", "resultReady", "resultCode",
                        "rowCount", "byteCount", "pageCount", "errorCode").containsAll(data.keySet())
                || !data.keySet().containsAll(Set.of("jobId", "state", "expiresAt", "resultState", "resultReady", "resultCode"))
                || !jobId.equals(data.get("jobId")) || !(data.get("state") instanceof String state)
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)) return false;
        try {
            if (selectedInteger(data.get("expiresAt")) <= 0) return false;
            for (String count : List.of("rowCount", "byteCount", "pageCount"))
                if (data.containsKey(count) && selectedInteger(data.get(count)) < 0) return false;
        } catch (RuntimeException invalid) { return false; }
        if (data.get("errorCode") != null && !(data.get("errorCode") instanceof String)) return false;
        if ("RELEASED".equals(data.get("resultState")))
            return Boolean.FALSE.equals(data.get("resultReady")) && "RESULT_RELEASED".equals(data.get("resultCode"));
        return data.get("resultCode") == null && ("SUCCEEDED".equals(state)
                ? "AVAILABLE".equals(data.get("resultState")) && Boolean.TRUE.equals(data.get("resultReady"))
                : "UNAVAILABLE".equals(data.get("resultState")) && Boolean.FALSE.equals(data.get("resultReady")));
    }

    private static ToolResult statisticsCancelHttpFailure(int status) {
        return statisticsCancelFailure(status == 401 || status == 403 ? "FORBIDDEN"
                : Set.of(404, 405, 501).contains(status) ? STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE
                : status == 400 ? "INVALID_QUERY" : status == 409 ? "CONFLICT" : "REMOTE_UNAVAILABLE");
    }

    private static ToolResult statisticsCancelFailure(String code) {
        return failure(code, "Statistics job cancellation is unavailable");
    }

    private static ToolResult statisticsReleaseHttpFailure(int status) {
        return statisticsReleaseFailure(status == 401 || status == 403 ? "FORBIDDEN"
                : Set.of(404, 405, 501).contains(status) ? STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
    }

    private static ToolResult statisticsReleaseFailure(String code) {
        return failure(code, "Statistics result release failed: " + code);
    }

    private static ToolResult statisticsCapacityFailure(Map<String, Object> envelope, String protocolUnavailable) {
        if (!Boolean.FALSE.equals(envelope.get("admitted"))
                || !(envelope.get("capacityKind") instanceof String kind)
                || !Set.of("ACTIVE_EXECUTION", "RESULT_STORAGE", "RECOVERY_IDENTITY").contains(kind)
                || envelope.containsKey("success") && !Boolean.FALSE.equals(envelope.get("success")))
            return failure(protocolUnavailable, "Statistics capacity receipt is invalid");
        return new ToolResult(false, Map.of("code", QUERY_CAPACITY_EXHAUSTED, "admitted", false,
                "capacityKind", kind), "Statistics query capacity is unavailable");
    }

    @Override
    public ToolResult authorizeStatisticsScope(ToolContext context, Map<String, Object> source) {
        if (!statisticsReadPrincipal(context)) return frozenStatisticsFailure("FORBIDDEN");
        final Map<String, Object> request;
        try {
            if (source == null || !Set.of("gid", "linkIds", "ownershipVersion").containsAll(source.keySet())
                    || !(source.get("gid") instanceof String gid) || gid.isBlank() || gid.length() > 64
                    || gid.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("gid", gid);
            copy.put("linkIds", selectedMemberIds(source.get("linkIds")));
            if (source.containsKey("ownershipVersion")) {
                if (!selectedHash(source.get("ownershipVersion"))) throw new IllegalArgumentException();
                copy.put("ownershipVersion", source.get("ownershipVersion"));
            }
            request = Collections.unmodifiableMap(copy);
        } catch (RuntimeException invalid) {
            return frozenStatisticsFailure("INVALID_QUERY");
        }
        return frozenStatisticsPost(AUTHORIZE_STATISTICS_SCOPE_PATH, context, request, true);
    }

    @SuppressWarnings("unchecked")
    private ToolResult frozenStatisticsPost(String path, ToolContext context, Map<String, Object> request, boolean authorization) {
        try {
            Map<String, Object> body;
            if (transport != null) {
                body = transport.exchange("POST", uri(path, Map.of()), headers(context).toSingleValueMap(), request);
            } else {
                ResponseEntity<Map> response = restTemplate.exchange(uri(path, Map.of()), HttpMethod.POST,
                        new HttpEntity<>(request, headers(context)), Map.class);
                if (response.getStatusCode().value() != 200) return frozenStatisticsHttpFailure(response.getStatusCode().value());
                body = response.getBody();
            }
            if (body == null) return frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
            Object code = body.get("code");
            if (!("0".equals(code) || code instanceof Number number && "0".equals(number.toString()))) {
                if (QUERY_CAPACITY_EXHAUSTED.equals(code))
                    return !authorization && FROZEN_JOBS_PATH.equals(path)
                            ? statisticsCapacityFailure(body, FROZEN_SCOPE_PROTOCOL_UNAVAILABLE)
                            : frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
                return frozenStatisticsFailure(code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}")
                        ? value : FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
            }
            if (body.containsKey("success") && !Boolean.TRUE.equals(body.get("success"))
                    || !(body.get("data") instanceof Map<?, ?> data))
                return frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
            if (authorization) return selectedStatisticsScope(context, request, data);
            if (!validRecoveredJob(data)) return frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
            return ToolResult.success(data);
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return frozenStatisticsHttpFailure(failure.statusCode());
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return frozenStatisticsHttpFailure(failure.getStatusCode().value());
        } catch (SecurityException denied) {
            return frozenStatisticsFailure("FORBIDDEN");
        } catch (RuntimeException failure) {
            return frozenStatisticsFailure(statisticsReadDecodeFailure(failure)
                    ? FROZEN_SCOPE_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
        }
    }

    private static ToolResult selectedStatisticsScope(ToolContext context, Map<String, Object> request, Map<?, ?> data) {
        if (!"selected-scope/v1".equals(data.get("schemaVersion")) || !(data.get("allowed") instanceof Boolean)
                || !(data.get("tenantId") instanceof String) || !(data.get("subjectId") instanceof String)
                || !(data.get("gid") instanceof String) || !selectedHash(data.get("memberHash"))
                || !selectedHash(data.get("ownershipVersion"))) return frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE);
        final List<Long> members;
        final long version;
        try { members = selectedMemberIds(data.get("linkIds")); version = selectedInteger(data.get("authVersion")); }
        catch (RuntimeException invalid) { return frozenStatisticsFailure(FROZEN_SCOPE_PROTOCOL_UNAVAILABLE); }
        if (!Boolean.TRUE.equals(data.get("allowed")) || !context.principal().tenantId().equals(data.get("tenantId"))
                || !context.principal().username().equals(data.get("subjectId")) || context.principal().authVersion() != version
                || !request.get("gid").equals(data.get("gid")) || !request.get("linkIds").equals(members)
                || !FrozenQueryScope.memberHash(members).equals(data.get("memberHash")))
            return frozenStatisticsFailure("FORBIDDEN");
        if (request.containsKey("ownershipVersion") && !request.get("ownershipVersion").equals(data.get("ownershipVersion")))
            return frozenStatisticsFailure("QUERY_SCOPE_CHANGED");
        // Return only the verified authorization facts; display identities are not needed by this backend gate.
        return ToolResult.success(Map.of("schemaVersion", "selected-scope/v1", "allowed", true,
                "tenantId", context.principal().tenantId(), "subjectId", context.principal().username(),
                "authVersion", version, "gid", request.get("gid"), "linkIds", members,
                "memberHash", data.get("memberHash"), "ownershipVersion", data.get("ownershipVersion")));
    }

    private static List<Long> selectedMemberIds(Object source) {
        if (!(source instanceof List<?> values) || values.size() > FrozenQueryScope.SHARD_SIZE) throw new IllegalArgumentException();
        return FrozenQueryScope.validatedMembers(values.stream().map(ShortLinkBusinessHttpGateway::selectedInteger).toList());
    }

    private static long selectedInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger)) throw new IllegalArgumentException();
        return new java.math.BigInteger(value.toString()).longValueExact();
    }

    private static boolean selectedHash(Object value) {
        return value instanceof String text && text.matches("[a-f0-9]{64}");
    }

    private static ToolResult frozenStatisticsHttpFailure(int status) {
        return frozenStatisticsFailure(status == 401 || status == 403 ? "FORBIDDEN"
                : Set.of(404, 405, 501).contains(status) ? FROZEN_SCOPE_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
    }

    private static ToolResult frozenStatisticsFailure(String code) {
        return failure(code, "Frozen statistics request failed: " + code);
    }

    @Override
    public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> frozenRequest) {
        // This method accepts a ledger-owned request, not model arguments or a replacement plan.
        if (frozenRequest == null
                || !(frozenRequest.get("requestId") instanceof String id)
                || !id.matches("[A-Za-z0-9_-]{1,96}")
                || !Set.of("requestId", "gid", "fullShortUrl", "startDate", "endDate",
                        "queryKind", "dimensions", "filters").containsAll(frozenRequest.keySet()))
            return failure("INVALID_QUERY", "Invalid frozen statistics job request");
        return post("/internal/short-link-admin/v1/agent-tools/statistics/jobs/recover-existing",
                context, frozenRequest, true);
    }

    @Override
    public ToolResult readStatisticsJob(ToolContext context, String jobId) {
        if (!statisticsReadPrincipal(context)) return statisticsReadFailure("FORBIDDEN");
        if (!validStatisticsJobId(jobId)) return statisticsReadFailure("INVALID_QUERY");
        return readStatistics(context, jobId, Map.of(), false);
    }

    @Override
    public ToolResult readStatisticsJobPage(ToolContext context, String jobId, int pageIndex, int size) {
        if (!statisticsReadPrincipal(context)) return statisticsReadFailure("FORBIDDEN");
        if (!validStatisticsJobId(jobId) || pageIndex < 0 || size != 500)
            return statisticsReadFailure("INVALID_QUERY");
        return readStatistics(context, jobId, Map.of("pageIndex", pageIndex, "size", size), true);
    }

    /** Strict, single-request backend contract; deliberately independent of legacy get/post behavior. */
    @SuppressWarnings("unchecked")
    private ToolResult readStatistics(ToolContext context, String jobId, Map<String, Object> query, boolean page) {
        try {
            String path = STATISTICS_JOBS_PATH + "/" + jobId + (page ? "/page" : "");
            Map<String, Object> body;
            if (transport != null) {
                body = transport.exchange("GET", uri(path, query), headers(context).toSingleValueMap(), null);
            } else {
                ResponseEntity<Map> response = restTemplate.exchange(uri(path, query), HttpMethod.GET,
                        new HttpEntity<>(headers(context)), Map.class);
                if (response.getStatusCode().value() != 200)
                    return statisticsReadHttpFailure(response.getStatusCode().value());
                body = response.getBody();
            }
            if (body == null) return statisticsReadFailure(STATISTICS_READ_PROTOCOL_UNAVAILABLE);
            Object code = body.get("code");
            boolean success = "0".equals(code) || code instanceof Number number && "0".equals(number.toString());
            if (!success) {
                String safeCode = code instanceof String value && value.matches("[A-Z][A-Z0-9_]{0,63}")
                        ? value : STATISTICS_READ_PROTOCOL_UNAVAILABLE;
                return statisticsReadFailure(safeCode);
            }
            if (!(body.get("data") instanceof Map<?, ?> data) || data.isEmpty()
                    || !validStatisticsReadData(data, jobId, page)) {
                return statisticsReadFailure(STATISTICS_READ_PROTOCOL_UNAVAILABLE);
            }
            return ToolResult.success(data);
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return statisticsReadHttpFailure(failure.statusCode());
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return statisticsReadHttpFailure(failure.getStatusCode().value());
        } catch (SecurityException denied) {
            return statisticsReadFailure("FORBIDDEN");
        } catch (RuntimeException failure) {
            return statisticsReadFailure(statisticsReadDecodeFailure(failure)
                    ? STATISTICS_READ_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE");
        }
    }

    private static boolean validStatisticsReadData(Map<?, ?> data, String jobId, boolean page) {
        // Real status DTO has jobId/state. Page DTO instead has items/metrics/meta; no top-level jobId/state.
        if (page) return data.get("items") instanceof List<?> && data.get("metrics") instanceof Map<?, ?>
                && data.get("meta") instanceof Map<?, ?>;
        return jobId.equals(data.get("jobId")) && validRecoveredJob(data);
    }

    private static boolean statisticsReadPrincipal(ToolContext context) {
        return context != null && context.principal() != null && !context.principal().system()
                && context.principal().username().equals(context.username());
    }

    private static boolean validStatisticsJobId(String jobId) {
        return jobId != null && jobId.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static ToolResult statisticsReadHttpFailure(int status) {
        String code = status == 401 || status == 403 ? "FORBIDDEN"
                : Set.of(404, 405, 501).contains(status) ? STATISTICS_READ_PROTOCOL_UNAVAILABLE : "REMOTE_UNAVAILABLE";
        return statisticsReadFailure(code);
    }

    private static ToolResult statisticsReadFailure(String code) {
        return failure(code, "Statistics job read failed: " + code);
    }

    private static boolean statisticsReadDecodeFailure(RuntimeException failure) {
        // The bounded transport wraps Jackson's IOException; RestTemplate uses conversion exceptions.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || cause instanceof org.springframework.http.converter.HttpMessageConversionException
                    || cause instanceof org.springframework.web.client.UnknownContentTypeException) return true;
        }
        // JSON null is rejected inside the existing bounded adapter before it can return its Map.
        return failure instanceof IllegalStateException && failure.getCause() == null
                && "Business authority returned an empty response".equals(failure.getMessage());
    }

    @SuppressWarnings("unchecked")
    private ToolResult post(String path, ToolContext context, Map<String, Object> payload, boolean recovery) {
        try {
            Map<String, Object> body =
                    transport != null
                            ? transport.exchange(
                                    "POST",
                                    uri(path, Map.of()),
                                    headers(context).toSingleValueMap(),
                                    payload)
                            : restTemplate
                                    .exchange(
                                            uri(path, Map.of()),
                                            HttpMethod.POST,
                                            new HttpEntity<>(payload, headers(context)),
                                            Map.class)
                                    .getBody();
            if (body == null)
                return failure(recovery ? "RECOVERY_PROTOCOL_UNAVAILABLE" : "REMOTE_FAILURE",
                        "Statistics job API returned an empty response");
            if (recovery ? !"0".equals(String.valueOf(body.get("code"))) : !isSuccess(body)) {
                String code = body.get("code") instanceof String value
                        && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value
                        : recovery ? "RECOVERY_PROTOCOL_UNAVAILABLE" : "REMOTE_FAILURE";
                return failure(code, recovery ? "Statistics job recovery failed: " + code : message(body));
            }
            if (recovery && !validRecoveredJob(body.get("data")))
                return failure("RECOVERY_PROTOCOL_UNAVAILABLE", "Statistics job recovery receipt is invalid");
            return ToolResult.success(body.get("data"));
        } catch (com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport.HttpStatusFailure failure) {
            return httpFailure(failure.statusCode(), recovery);
        } catch (org.springframework.web.client.HttpStatusCodeException failure) {
            return httpFailure(failure.getStatusCode().value(), recovery);
        } catch (RuntimeException failure) {
            return failure("REMOTE_UNAVAILABLE", "Statistics job API is unavailable or unauthorized");
        }
    }

    private static boolean validRecoveredJob(Object data) {
        return data instanceof Map<?, ?> job
                && job.get("jobId") instanceof String id && id.matches("[A-Za-z0-9_-]{1,128}")
                && job.get("state") instanceof String state
                && Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state)
                && validJobResultState(job, id);
    }

    private static boolean validJobResultState(Map<?, ?> job, String jobId) {
        if (job.containsKey("resultState") && (!(job.get("resultState") instanceof String resultState)
                || !Set.of("PENDING", "AVAILABLE", "UNAVAILABLE", "RELEASED").contains(resultState))) return false;
        if ("RELEASED".equals(job.get("resultState")) || "RESULT_RELEASED".equals(job.get("resultCode")))
            return validReleasedJob(job, jobId);
        return true;
    }

    private static boolean validReleasedJob(Map<?, ?> job, String jobId) {
        if (!jobId.equals(job.get("jobId")) || !(job.get("state") instanceof String state)
                || !Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)
                || !"RELEASED".equals(job.get("resultState")) || !"RESULT_RELEASED".equals(job.get("resultCode"))
                || !Boolean.FALSE.equals(job.get("resultReady"))) return false;
        try { return selectedInteger(job.get("expiresAt")) > 0; }
        catch (RuntimeException invalid) { return false; }
    }

    private static ToolResult httpFailure(int status, boolean recovery) {
        String code = recovery && Set.of(404, 405, 501).contains(status)
                ? "RECOVERY_PROTOCOL_UNAVAILABLE"
                : status == 401 || status == 403 ? "FORBIDDEN" : "REMOTE_UNAVAILABLE";
        return failure(code, "Statistics job API failed: " + code);
    }

    private static ToolResult failure(String code, String message) {
        return new ToolResult(false, Map.of("code", code), message);
    }

    private URI uri(String path, Map<String, Object> queryParams) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromHttpUrl(baseUrl() + normalizePath(path));
        safeQueryParams(queryParams)
                .forEach(
                        (key, value) -> {
                            if (value != null) {
                                builder.queryParam(key, value);
                            }
                        });
        return builder.build().encode().toUri();
    }

    private HttpHeaders headers(ToolContext context) {
        HttpHeaders headers = new HttpHeaders();
        if (context == null
                || context.principal() == null
                || !context.username().equals(context.principal().username())) {
            throw new IllegalArgumentException("Trusted Agent principal is required");
        }
        headers.add(USERNAME_HEADER, context.principal().username());
        if (context.principal().system()) headers.add("X-Agent-Principal-Mode", "SYSTEM");
        else {
            headers.add("X-Agent-UserId", context.principal().tenantId());
            headers.add("X-Agent-Auth-Version", Long.toString(context.principal().authVersion()));
        }
        String internalToken = agentProperties.getBusiness().getInternalToken();
        if (internalToken == null || internalToken.length() < 24)
            throw new SecurityException("Service credential is unavailable");
        if (StringUtils.hasText(internalToken)) {
            headers.add(INTERNAL_TOKEN_HEADER, internalToken);
        }
        return headers;
    }

    private String baseUrl() {
        String baseUrl = agentProperties.getBusiness().getBaseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private Map<String, Object> safeQueryParams(Map<String, Object> queryParams) {
        return queryParams == null ? Map.of() : queryParams;
    }

    private boolean isSuccess(Map<String, Object> body) {
        Object success = body.get("success");
        Object code = body.get("code");
        return Boolean.TRUE.equals(success) || "0".equals(String.valueOf(code));
    }

    private String message(Map<String, Object> body) {
        Object message = body.get("message");
        Object code = body.get("code");
        if (message != null) {
            return String.valueOf(message);
        }
        return "Short link business API failed with code " + code;
    }
}
