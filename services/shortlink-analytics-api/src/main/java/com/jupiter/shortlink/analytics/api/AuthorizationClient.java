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
                if (r.statusCode() == 403)
                    throw new QueryFailure("FORBIDDEN", "Authorization denied");
                if (r.statusCode() != 200)
                    throw new QueryFailure("UNAVAILABLE", "Authority unavailable");
                byte[] bytes = input.readNBytes(262145);
                if (bytes.length > 262144)
                    throw new QueryFailure("UNAVAILABLE", "Authority response exceeds budget");
                return json.readValue(
                        bytes,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
            }
        } catch (QueryFailure e) {
            throw e;
        } catch (Exception e) {
            throw new QueryFailure("UNAVAILABLE", "Authority unavailable");
        }
    }
}
