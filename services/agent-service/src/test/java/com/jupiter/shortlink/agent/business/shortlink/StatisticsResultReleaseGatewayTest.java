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
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Both gateway transports use actual loopback HTTP; no application or real statistics service. */
class StatisticsResultReleaseGatewayTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/internal/short-link-admin/v1/agent-tools/statistics/";
    private static final String JOB = "job-original";
    private static final String RELEASE = ROOT + "frozen-jobs/" + JOB + "/release-result";
    private static final String PROTOCOL = "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE";
    private static final String CAPACITY = "QUERY_CAPACITY_EXHAUSTED";
    private static final ToolContext CONTEXT = new ToolContext("session-1", "zhangsan", Map.of(), StatsTestFixtures.PRINCIPAL);

    @Test
    void cancellationUsesOneDedicatedRequestAndPreservesRealTerminalStateWithoutFallback() throws Exception {
        String protocol = "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE";
        String path = ROOT + "jobs/" + JOB + "/cancel";
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                failure(gateway.cancelStatisticsJob(null, JOB), "FORBIDDEN");
                failure(gateway.cancelStatisticsJob(new ToolContext("s", "system", Map.of(), AgentPrincipal.system("system")), JOB), "FORBIDDEN");
                failure(gateway.cancelStatisticsJob(CONTEXT, "../other"), "INVALID_QUERY");
                assertThat(remote.calls).isEmpty();

                Map<String, Object> cancelled = null;
                int attempts = 0;
                for (String state : List.of("CANCELLED", "SUCCEEDED", "FAILED")) {
                    var receipt = new LinkedHashMap<String, Object>(Map.of("jobId", JOB, "state", state,
                            "expiresAt", 1900000000000L, "resultState", "SUCCEEDED".equals(state) ? "AVAILABLE" : "UNAVAILABLE",
                            "resultReady", "SUCCEEDED".equals(state)));
                    receipt.put("resultCode", null);
                    if (state.equals("CANCELLED")) cancelled = receipt;
                    var status = new LinkedHashMap<>(receipt);
                    status.put("rowCount", 0); status.put("byteCount", 0); status.put("pageCount", 0);
                    status.put("errorCode", state.equals("CANCELLED") ? "CANCELLED" : null);
                    remote.body = envelope(status);
                    ToolResult result = gateway.cancelStatisticsJob(CONTEXT, JOB);
                    assertThat(result.success()).isTrue();
                    assertThat(result.data()).isEqualTo(receipt);
                    assertThat(remote.calls).hasSize(++attempts);
                }
                remote.body = envelope(released("SUCCEEDED"));
                assertThat(gateway.cancelStatisticsJob(CONTEXT, JOB).data()).isEqualTo(released("SUCCEEDED"));
                assertThat(remote.calls).hasSize(++attempts);

                List<Map<String, Object>> bad = new ArrayList<>();
                bad.add(Map.of("jobId", JOB, "state", "CANCELLED"));
                for (var change : Map.<String, Object>of("jobId", "another-job", "state", "RUNNING",
                        "resultState", "AVAILABLE", "resultReady", true, "resultCode", "RESULT_RELEASED", "expiresAt", "1900000000000").entrySet()) {
                    var receipt = new LinkedHashMap<>(cancelled);
                    receipt.put(change.getKey(), change.getValue()); bad.add(receipt);
                }
                for (var receipt : bad) {
                    remote.body = envelope(receipt);
                    failure(gateway.cancelStatisticsJob(CONTEXT, JOB), protocol);
                    assertThat(remote.calls).hasSize(++attempts);
                }
                for (int status : List.of(404, 405, 501, 403, 503)) {
                    remote.status = status;
                    failure(gateway.cancelStatisticsJob(CONTEXT, JOB), status == 403 ? "FORBIDDEN"
                            : status == 503 ? "REMOTE_UNAVAILABLE" : protocol);
                    assertThat(remote.calls).hasSize(++attempts);
                }
                remote.status = 200;
                remote.body = JSON.writeValueAsString(Map.of("code", "QUERY_SCOPE_CHANGED", "message", "private SQL credentials"));
                failure(gateway.cancelStatisticsJob(CONTEXT, JOB), "QUERY_SCOPE_CHANGED");
                assertThat(remote.calls).hasSize(++attempts);
                for (Captured call : remote.calls) {
                    assertThat(call.method()).isEqualTo("POST"); assertThat(call.path()).isEqualTo(path);
                    assertThat(call.body()).isEmpty();
                    assertThat(call.tenant()).isEqualTo("1001"); assertThat(call.username()).isEqualTo("zhangsan");
                    assertThat(call.authVersion()).isEqualTo("7"); assertThat(call.principalMode()).isNull();
                }
            }
        }
        ShortLinkBusinessGateway unsupported = (pathName, context, query) -> {
            throw new AssertionError("Cancellation must not fall back to a generic request");
        };
        failure(unsupported.cancelStatisticsJob(CONTEXT, JOB), protocol);
    }

    @Test
    void releasePostsTheOriginalFrozenRequestAndReleasedReadsKeepTheSameUnavailableIdentity() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                for (String state : List.of("SUCCEEDED", "FAILED", "CANCELLED")) {
                    var receipt = released(state);
                    var remoteReceipt = new LinkedHashMap<>(receipt);
                    remoteReceipt.put("private", "untrusted extra payload");
                    remote.body = envelope(remoteReceipt);
                    ToolResult result = gateway.releaseStatisticsJobResult(CONTEXT, JOB, request());
                    assertThat(result.success()).isTrue();
                    assertThat(result.data()).isEqualTo(receipt);
                    assertThat(result.message()).isNull();
                }
                for (Captured call : remote.calls) {
                    assertThat(call.method()).isEqualTo("POST");
                    assertThat(call.path()).isEqualTo(RELEASE);
                    assertThat(JSON.readTree(JSON.writeValueAsString(call.body())))
                            .isEqualTo(JSON.readTree(JSON.writeValueAsString(request())));
                    assertThat(call.tenant()).isEqualTo("1001");
                    assertThat(call.username()).isEqualTo("zhangsan");
                    assertThat(call.authVersion()).isEqualTo("7");
                    assertThat(call.principalMode()).isNull();
                }

                remote.body = envelope(released("SUCCEEDED"));
                for (ToolResult result : List.of(gateway.readStatisticsJob(CONTEXT, JOB),
                        gateway.submitFrozenStatisticsJob(CONTEXT, request()),
                        gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()))) {
                    assertThat(result.success()).isTrue();
                    Map<?, ?> data = (Map<?, ?>) result.data();
                    assertThat(data.get("jobId")).isEqualTo(JOB);
                    assertThat(data.get("resultReady")).isEqualTo(false);
                    assertThat(data.get("resultCode")).isEqualTo("RESULT_RELEASED");
                }
                var contradictory = released("SUCCEEDED");
                contradictory.put("resultReady", true);
                remote.body = envelope(contradictory);
                failure(gateway.readStatisticsJob(CONTEXT, JOB), "STATISTICS_READ_PROTOCOL_UNAVAILABLE");
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
                failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
                assertThat(remote.calls).hasSize(9);
                assertThat(new ArrayList<>(remote.calls).subList(3, 9)).extracting(Captured::path)
                        .containsExactly(ROOT + "jobs/" + JOB, ROOT + "frozen-jobs", ROOT + "frozen-jobs/recover-existing",
                                ROOT + "jobs/" + JOB, ROOT + "frozen-jobs", ROOT + "frozen-jobs/recover-existing");
            }
        }
    }

    @Test
    void invalidRequestsOldServicesAndMismatchedReleaseReceiptsNeverUseAFallback() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                failure(gateway.releaseStatisticsJobResult(null, JOB, request()), "FORBIDDEN");
                failure(gateway.releaseStatisticsJobResult(
                        new ToolContext("s", "system", Map.of(), AgentPrincipal.system("system")), JOB, request()), "FORBIDDEN");
                failure(gateway.releaseStatisticsJobResult(CONTEXT, "../other", request()), "INVALID_QUERY");
                for (String field : List.of("fullShortUrl", "principal", "unknown")) {
                    var changed = request(); changed.put(field, "not permitted");
                    failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, changed), "INVALID_QUERY");
                }
                var noScope = request(); noScope.remove("scope");
                failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, noScope), "INVALID_QUERY");
                var wrongDate = request(); wrongDate.put("startDate", "2026-09-99");
                failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, wrongDate), "INVALID_QUERY");
                assertThat(remote.calls).isEmpty();

                List<Map<String, Object>> invalidReceipts = new ArrayList<>();
                invalidReceipts.add(Map.of("jobId", JOB, "state", "SUCCEEDED"));
                for (var change : Map.<String, Object>of("jobId", "job-different", "state", "RUNNING",
                        "resultState", "AVAILABLE", "resultCode", "NOT_READY", "resultReady", true, "expiresAt", 0L).entrySet()) {
                    var changed = released("SUCCEEDED"); changed.put(change.getKey(), change.getValue());
                    invalidReceipts.add(changed);
                }
                var coercedExpiry = released("SUCCEEDED"); coercedExpiry.put("expiresAt", "1900000000000");
                invalidReceipts.add(coercedExpiry);
                for (var invalid : invalidReceipts) {
                    remote.body = envelope(invalid);
                    failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, request()), PROTOCOL);
                }
                for (int status : List.of(404, 405, 501)) {
                    remote.status = status;
                    failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, request()), PROTOCOL);
                }
                remote.status = 200;
                remote.body = JSON.writeValueAsString(Map.of("code", "FORBIDDEN", "message", "private SQL credentials"));
                failure(gateway.releaseStatisticsJobResult(CONTEXT, JOB, request()), "FORBIDDEN");
                assertThat(remote.calls).hasSize(invalidReceipts.size() + 4)
                        .allMatch(call -> call.method().equals("POST") && call.path().equals(RELEASE));
            }
        }
        ShortLinkBusinessGateway unsupported = new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("No generic GET fallback");
            }
            public ToolResult post(String path, ToolContext context, Map<String, Object> payload) {
                throw new AssertionError("No generic POST or replacement submission");
            }
        };
        failure(unsupported.releaseStatisticsJobResult(CONTEXT, JOB, request()), PROTOCOL);
    }

    @Test
    void onlyExplicitValidCapacityReceiptsCanDeclareAnUnadmittedSubmission() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                var currentQuery = request(); currentQuery.remove("scope");
                for (String kind : List.of("ACTIVE_EXECUTION", "RESULT_STORAGE", "RECOVERY_IDENTITY")) {
                    remote.body = JSON.writeValueAsString(Map.of("code", CAPACITY, "admitted", false,
                            "capacityKind", kind, "message", "private SQL credentials", "data", Map.of("secret", "discard")));
                    for (ToolResult result : List.of(gateway.submitFrozenStatisticsJob(CONTEXT, request()),
                            gateway.submitStatisticsJob(CONTEXT, currentQuery))) {
                        assertThat(result.success()).isFalse();
                        assertThat(result.data()).isEqualTo(Map.of("code", CAPACITY, "admitted", false, "capacityKind", kind));
                        assertThat(result.message()).doesNotContain("private", "credentials", "SQL", "secret");
                    }
                }
                List<Map<String, Object>> malformed = List.of(
                        Map.of("code", CAPACITY, "capacityKind", "RESULT_STORAGE"),
                        Map.of("code", CAPACITY, "admitted", true, "capacityKind", "RESULT_STORAGE"),
                        Map.of("code", CAPACITY, "admitted", "false", "capacityKind", "RESULT_STORAGE"),
                        Map.of("code", CAPACITY, "admitted", false, "capacityKind", "UNKNOWN"),
                        Map.of("code", CAPACITY, "admitted", false, "capacityKind", "RESULT_STORAGE", "success", true),
                        Map.of("code", CAPACITY, "data", Map.of("admitted", false, "capacityKind", "RESULT_STORAGE")));
                for (var receipt : malformed) {
                    remote.body = JSON.writeValueAsString(receipt);
                    failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
                    failure(gateway.submitStatisticsJob(CONTEXT, currentQuery), "STATISTICS_SUBMIT_PROTOCOL_UNAVAILABLE");
                }
                remote.body = JSON.writeValueAsString(Map.of("code", CAPACITY, "admitted", false, "capacityKind", "RESULT_STORAGE"));
                failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()), "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
                remote.status = 429;
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), "REMOTE_UNAVAILABLE");
                assertThat(remote.calls).hasSize(20);
                remote.server.stop(0);
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), "REMOTE_UNAVAILABLE");
            }
        }
    }

    private static LinkedHashMap<String, Object> request() {
        List<Long> members = List.of(1L, 2L);
        String hash = FrozenQueryScope.memberHash(members);
        FrozenQueryScope scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "parent-scope", hash, 2,
                "e".repeat(64), FrozenQueryScope.shardIdFor("parent-scope", 0, hash), 0, 1, hash, members);
        return new LinkedHashMap<>(Map.of("requestId", "original-request", "gid", "g1", "queryKind", "LINK_METRICS",
                "startDate", "2026-09-01", "endDate", "2026-09-02", "scope", scope.asMap()));
    }

    private static LinkedHashMap<String, Object> released(String state) {
        return new LinkedHashMap<>(Map.of("jobId", JOB, "state", state, "resultState", "RELEASED",
                "resultCode", "RESULT_RELEASED", "resultReady", false, "expiresAt", 1900000000000L));
    }

    private static String envelope(Object data) throws Exception {
        return JSON.writeValueAsString(Map.of("code", "0", "data", data));
    }

    private static void failure(ToolResult result, String code) {
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", code));
        assertThat(result.message()).doesNotContain("private", "credentials", "SQL", "secret");
    }

    private record Captured(String method, String path, Map<String, Object> body,
                            String tenant, String username, String authVersion, String principalMode) {}

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<Captured> calls = new ConcurrentLinkedQueue<>();
        private volatile int status = 200;
        private volatile String body = "{}";

        private Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] request = exchange.getRequestBody().readAllBytes();
                Map<String, Object> payload = request.length == 0 ? Map.of() : JSON.readValue(request, new TypeReference<>() {});
                var headers = exchange.getRequestHeaders();
                calls.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(), payload,
                        headers.getFirst("X-Agent-UserId"), headers.getFirst("X-Agent-Username"),
                        headers.getFirst("X-Agent-Auth-Version"), headers.getFirst("X-Agent-Principal-Mode")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }

        private ShortLinkBusinessHttpGateway gateway(boolean bounded) {
            AgentProperties properties = new AgentProperties();
            properties.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
            return bounded ? new ShortLinkBusinessHttpGateway(properties, new BoundedHttpTransport())
                    : new ShortLinkBusinessHttpGateway(properties, new RestTemplate());
        }

        public void close() { server.stop(0); }
    }
}
