package com.jupiter.shortlink.analytics.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class AuthorizationClient {
    private final ApiSettings settings;
    private final ObjectMapper json;
    private final HttpClient client;

    public AuthorizationClient(ApiSettings s, ObjectMapper j) {
        settings = s;
        json = j;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record Scope(String tenantId, List<Long> linkIds, String ownershipVersion) {}

    public Scope authorize(QueryRequest q) {
        if (q.tenantId() == null || q.subjectId() == null || q.authVersion() < 0)
            throw new QueryFailure("FORBIDDEN", "Identity is required");
        if (q.scope() != null) return authorizeSelected(q);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", q.tenantId());
        body.put("subjectId", q.subjectId());
        body.put("authVersion", q.authVersion());
        body.put("gid", q.gid());
        body.put("linkIds", q.linkIds());
        Map<String, Object> r =
                post(settings.commandUrl() + "/internal/v1/authorization/analytics", body);
        if (r.get("data") instanceof Map<?, ?> nested) r = cast(nested);
        if (!Boolean.TRUE.equals(r.get("allowed"))
                || !q.tenantId().equals(String.valueOf(r.get("tenantId"))))
            throw new QueryFailure("FORBIDDEN", "Analytics scope denied");
        if (!(r.get("linkIds") instanceof List<?> ids) || ids.size() > 500)
            throw new QueryFailure("TOO_LARGE", "Authorized scope exceeds 500 links");
        List<Long> links =
                ids.stream().map(v -> Long.valueOf(v.toString())).sorted().distinct().toList();
        Object version = r.get("ownershipVersion");
        if (version == null)
            throw new QueryFailure("UNAVAILABLE", "Missing authorization revision");
        return new Scope(q.tenantId(), links, version.toString());
    }

    private Scope authorizeSelected(QueryRequest q) {
        FrozenScopeValidation.request(q);
        if (!q.tenantId().matches("[1-9][0-9]{0,18}")
                || !q.subjectId().matches("[A-Za-z0-9_-]{3,64}") || q.authVersion() < 1)
            throw new QueryFailure("FORBIDDEN", "Current selected-scope identity is required");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", q.tenantId());
        body.put("subjectId", q.subjectId());
        body.put("authVersion", q.authVersion());
        body.put("gid", q.gid());
        body.put("linkIds", q.scope().linkIds());
        Map<String, Object> response = post(settings.commandUrl() + "/internal/v1/authorization/analytics-selected", body, true);
        if (response.get("data") instanceof Map<?, ?> nested) response = cast(nested);
        if (!"selected-scope/v1".equals(response.get("schemaVersion"))
                || !(response.get("allowed") instanceof Boolean)
                || !(response.get("tenantId") instanceof String)
                || !(response.get("subjectId") instanceof String)
                || !(response.get("gid") instanceof String)
                || !(response.get("authVersion") instanceof Number))
            throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization protocol is unavailable");
        if (!Boolean.TRUE.equals(response.get("allowed"))
                || !q.tenantId().equals(response.get("tenantId"))
                || !q.subjectId().equals(response.get("subjectId"))
                || !q.gid().equals(response.get("gid"))
                || !(response.get("authVersion") instanceof Number version)
                || !Long.toString(q.authVersion()).equals(version.toString()))
            throw new QueryFailure("FORBIDDEN", "Selected authorization identity mismatch");
        if (!(response.get("linkIds") instanceof List<?> values) || values.size() > 500)
            throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization members are invalid");
        List<Long> ids = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof java.math.BigInteger))
                throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization members are invalid");
            try { ids.add(new java.math.BigInteger(value.toString()).longValueExact()); }
            catch (ArithmeticException invalid) { throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization members are invalid"); }
        }
        Scope selected = new Scope(q.tenantId(), List.copyOf(ids),
                response.get("ownershipVersion") instanceof String ownership ? ownership : null);
        FrozenScopeValidation.authorized(q, selected);
        if (!(response.get("memberHash") instanceof String hash) || !hash.matches("[a-f0-9]{64}"))
            throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization proof is invalid");
        if (!q.scope().shardMemberHash().equals(response.get("memberHash")))
            throw new QueryFailure("QUERY_SCOPE_CHANGED", "Selected authorization proof mismatch");
        return selected;
    }

    public String activeEpoch() {
        Map<String, Object> r =
                post(settings.workerUrl() + "/internal/analytics/v1/worker/readiness", Map.of());
        if (!Boolean.TRUE.equals(r.get("ready")))
            throw new QueryFailure("NOT_READY", "Analytics recovery gate is closed");
        Object e = r.get("recoveryEpoch");
        if (e == null) throw new QueryFailure("NOT_READY", "No recovery epoch");
        return e.toString();
    }

    public Map<String, Object> coverage(long start) {
        return post(
                settings.workerUrl() + "/internal/analytics/v1/worker/coverage",
                Map.of("startInclusive", start));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private Map<String, Object> post(String url, Object body) {
        return post(url, body, false);
    }

    private Map<String, Object> post(String url, Object body, boolean selected) {
        try {
            var request =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("X-Internal-Token", settings.token())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(body)))
                            .build();
            var r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = r.body();
                    var deadline = BoundedLines.watch(input, 5000)) {
                if (r.statusCode() == 403 || selected && r.statusCode() == 401)
                    throw new QueryFailure("FORBIDDEN", "Authorization denied");
                if (selected && Set.of(404, 405, 501).contains(r.statusCode()))
                    throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization protocol is unavailable");
                if (r.statusCode() != 200)
                    throw new QueryFailure("UNAVAILABLE", "Authority unavailable");
                byte[] bytes = input.readNBytes(262145);
                if (bytes.length > 262144)
                    throw new QueryFailure("UNAVAILABLE", "Authority response exceeds budget");
                Map<String, Object> decoded = json.readValue(
                        bytes,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
                if (selected && decoded == null)
                    throw new QueryFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE", "Selected authorization protocol is unavailable");
                return decoded;
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new QueryFailure(selected ? "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE" : "UNAVAILABLE",
                    selected ? "Authority response is invalid" : "Authority unavailable");
        } catch (Exception e) {
            throw new QueryFailure("UNAVAILABLE", "Authority unavailable");
        }
    }
}
