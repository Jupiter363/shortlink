package com.jupiter.shortlink.agent.business.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** Backend-only dispatch preserves the frozen request and never delegates to legacy submission. */
class StatisticsJobSubmitContractTest {
    private static final String PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";
    private static final String PROTOCOL = "STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolContext CONTEXT = new ToolContext("session-1", "zhangsan", Map.of(), StatsTestFixtures.PRINCIPAL);

    @Test
    void bothTransportsSubmitTheExactFrozenWireOnceWithTrustedIdentity() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new SubmitFixture()) {
                var gateway = remote.gateway(bounded);
                Map<String, Object> request = request();
                request.put("fullShortUrl", "nurl.ink/owned");
                ToolResult result = gateway.submitStatisticsJob(CONTEXT, request);
                assertThat(result.success()).isTrue();
                assertThat(result.data()).isEqualTo(Map.of("jobId", "job-original", "state", "QUEUED"));
                assertThat(result.message()).isNull();
                assertThat(remote.requests).hasSize(1);
                Captured received = remote.requests.element();
                assertThat(received.method()).isEqualTo("POST");
                assertThat(received.path()).isEqualTo(PATH);
                assertThat(received.body()).isEqualTo(request);
                assertThat(received.tenant()).isEqualTo("1001");
                assertThat(received.username()).isEqualTo("zhangsan");
                assertThat(received.authVersion()).isEqualTo("7");
                assertThat(received.internalToken()).isEqualTo(StatsTestFixtures.SECRET);
                assertThat(received.mode()).isNull();

                // Validation must not sort or deduplicate the already frozen request.
                var dimensions = request();
                dimensions.put("queryKind", "DIMENSION_BREAKDOWN");
                dimensions.put("dimensions", List.of("device", "province"));
                dimensions.put("filters", List.of(Map.of("dimension", "province", "operator", "IN",
                        "values", List.of("Z", "A", "Z"))));
                remote.body = "{\"code\":0,\"data\":{\"jobId\":\"job-original\",\"state\":\"SUCCEEDED\"}}";
                assertThat(gateway.submitStatisticsJob(CONTEXT, dimensions).success()).isTrue();
                assertThat(remote.requests).hasSize(2);
                assertThat(new ArrayList<>(remote.requests).get(1).body()).isEqualTo(dimensions);
            }
        }
    }

    @Test
    void malformedContradictoryBusinessAndHttpFailuresNeverFallbackOrExposeRemoteDetails() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new SubmitFixture()) {
                var gateway = remote.gateway(bounded);
                List<String> invalid = List.of("null", "{broken", "[]", "{}",
                        "{\"success\":true,\"data\":{\"jobId\":\"job-original\",\"state\":\"QUEUED\"}}",
                        "{\"code\":\"0\",\"success\":false,\"data\":{\"jobId\":\"job-original\",\"state\":\"QUEUED\"}}",
                        "{\"code\":0.0,\"data\":{\"jobId\":\"job-original\",\"state\":\"QUEUED\"}}",
                        "{\"code\":\"0\",\"data\":{\"jobId\":\"../other\",\"state\":\"QUEUED\"}}",
                        "{\"code\":\"0\",\"data\":{\"jobId\":\"job-original\",\"state\":\"PENDING\"}}",
                        "{\"code\":\"0\",\"data\":{\"jobId\":\"job-original\"}}",
                        "{\"code\":\"0\",\"data\":\"private payload\"}");
                for (String body : invalid) {
                    remote.body = body;
                    failure(gateway.submitStatisticsJob(CONTEXT, request()), PROTOCOL);
                }
                List<String> codes = List.of("CONFLICT", "FORBIDDEN", "CAPACITY_EXCEEDED", "UNAVAILABLE");
                for (String code : codes) {
                    remote.body = JSON.writeValueAsString(Map.of("code", code, "success", true,
                            "message", "private SQL and credentials", "data", Map.of("private", "secret")));
                    failure(gateway.submitStatisticsJob(CONTEXT, request()), code);
                }
                remote.body = "{\"code\":\"private invalid code\",\"message\":\"private SQL\"}";
                failure(gateway.submitStatisticsJob(CONTEXT, request()), PROTOCOL);
                int[] statuses = {401, 403, 404, 405, 501, 429, 503};
                for (int status : statuses) {
                    remote.status = status;
                    failure(gateway.submitStatisticsJob(CONTEXT, request()), status == 401 || status == 403 ? "FORBIDDEN"
                            : status == 404 || status == 405 || status == 501 ? PROTOCOL : "REMOTE_UNAVAILABLE");
                }
                assertThat(remote.requests).hasSize(invalid.size() + codes.size() + 1 + statuses.length)
                        .allMatch(received -> received.method().equals("POST") && received.path().equals(PATH));
                remote.server.stop(0);
                failure(gateway.submitStatisticsJob(CONTEXT, request()), "REMOTE_UNAVAILABLE");
            }
        }
    }

    @Test
    void invalidPrincipalsAndClosedWireViolationsMakeNoHttpAndUnsupportedAdapterDoesNotFallback() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new SubmitFixture()) {
                var gateway = remote.gateway(bounded);
                failure(gateway.submitStatisticsJob(null, request()), "FORBIDDEN");
                failure(gateway.submitStatisticsJob(new ToolContext("s", "zhangsan", Map.of()), request()), "FORBIDDEN");
                failure(gateway.submitStatisticsJob(new ToolContext("s", "different", Map.of(), StatsTestFixtures.PRINCIPAL), request()), "FORBIDDEN");
                failure(gateway.submitStatisticsJob(new ToolContext("s", "system", Map.of(), AgentPrincipal.system("system")), request()), "FORBIDDEN");
                failure(gateway.submitStatisticsJob(CONTEXT, null), "INVALID_QUERY");
                List<Map<String, Object>> invalid = new ArrayList<>();
                invalid.add(with("requestId", "x".repeat(97)));
                invalid.add(with("requestId", "../request"));
                invalid.add(with("gid", ""));
                invalid.add(with("startDate", Double.NaN));
                invalid.add(with("endDate", "2026-09-02\n"));
                invalid.add(with("queryKind", "CUSTOM_SQL"));
                invalid.add(with("principal", "spoofed"));
                invalid.add(with("linkIds", List.of(1)));
                invalid.add(with("scopeKind", "FROZEN_SET"));
                invalid.add(with("filters", List.of()));
                var missing = request(); missing.remove("endDate"); invalid.add(missing);
                var nested = request(); nested.put("queryKind", "DIMENSION_BREAKDOWN");
                nested.put("dimensions", List.of("province"));
                nested.put("filters", List.of(Map.of("dimension", "province", "operator", "IN",
                        "values", List.of("A"), "sql", "private expression")));
                invalid.add(nested);
                var cycle = request(); cycle.put("queryKind", "DIMENSION_BREAKDOWN");
                cycle.put("dimensions", List.of("province")); cycle.put("filters", List.of(cycle)); invalid.add(cycle);
                for (var value : invalid) failure(gateway.submitStatisticsJob(CONTEXT, value), "INVALID_QUERY");
                assertThat(remote.requests).isEmpty();
            }
        }
        ShortLinkBusinessGateway unsupported = new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("No legacy GET fallback");
            }
            public ToolResult post(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("No legacy POST fallback");
            }
        };
        failure(unsupported.submitStatisticsJob(CONTEXT, request()), PROTOCOL);
    }

    private static Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("requestId", "request-stable-1", "gid", "g1",
                "queryKind", "METRICS", "startDate", "2026-09-01", "endDate", "2026-09-02"));
    }

    private static Map<String, Object> with(String key, Object value) {
        var request = request(); request.put(key, value); return request;
    }

    private static void failure(ToolResult result, String code) {
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", code));
        assertThat(result.message()).doesNotContain("private", "credentials", "SQL", "secret");
    }

    private record Captured(String method, String path, Map<String, Object> body, String tenant,
                            String username, String authVersion, String internalToken, String mode) {}

    /** Short-lived test endpoint; both gateway branches make real HTTP requests to it. */
    private static final class SubmitFixture implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<Captured> requests = new ConcurrentLinkedQueue<>();
        private volatile int status = 200;
        private volatile String body = "{\"code\":\"0\",\"data\":{\"jobId\":\"job-original\",\"state\":\"QUEUED\"}}";

        private SubmitFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var headers = exchange.getRequestHeaders();
                Map<String, Object> payload = JSON.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(), payload,
                        headers.getFirst("X-Agent-UserId"), headers.getFirst("X-Agent-Username"),
                        headers.getFirst("X-Agent-Auth-Version"), headers.getFirst("X-Agent-Internal-Token"),
                        headers.getFirst("X-Agent-Principal-Mode")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }

        private ShortLinkBusinessHttpGateway gateway(boolean bounded) {
            var properties = new AgentProperties();
            properties.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
            return bounded ? new ShortLinkBusinessHttpGateway(properties, new BoundedHttpTransport())
                    : new ShortLinkBusinessHttpGateway(properties, new RestTemplate());
        }

        public void close() { server.stop(0); }
    }
}
