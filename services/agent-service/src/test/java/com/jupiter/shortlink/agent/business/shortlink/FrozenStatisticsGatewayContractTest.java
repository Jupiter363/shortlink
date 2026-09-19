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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

class FrozenStatisticsGatewayContractTest {
    private static final String ROOT = "/internal/short-link-admin/v1/agent-tools/statistics/";
    private static final String PROTOCOL = "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE";
    private static final String VERSION = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolContext CONTEXT = new ToolContext("session-1", "zhangsan", Map.of(), StatsTestFixtures.PRINCIPAL);

    @Test
    void frozenSubmissionRecoveryAndExactAuthorizationUseDedicatedWireOnBothTransports() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                var request = request();
                assertThat(gateway.submitFrozenStatisticsJob(CONTEXT, request).success()).isTrue();
                assertThat(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request).success()).isTrue();
                var captured = new ArrayList<>(remote.requests);
                assertThat(captured).hasSize(2);
                assertThat(captured.get(0).path()).isEqualTo(ROOT + "frozen-jobs");
                assertThat(captured.get(1).path()).isEqualTo(ROOT + "frozen-jobs/recover-existing");
                assertThat(JSON.readTree(JSON.writeValueAsBytes(captured.get(0).body())))
                        .isEqualTo(JSON.readTree(JSON.writeValueAsBytes(request)));
                assertThat(captured.get(1).body()).isEqualTo(captured.get(0).body());
                remote.body = envelope(selected(List.of(1L, 2L)));
                var expected = Map.<String, Object>of("gid", "g1", "linkIds", List.of(1L, 2L), "ownershipVersion", VERSION);
                var authorized = gateway.authorizeStatisticsScope(CONTEXT, expected);
                assertThat(authorized.success()).isTrue();
                assertThat(authorized.data()).isEqualTo(selected(List.of(1L, 2L)));
                remote.body = envelope(selected(List.of()));
                var empty = gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of()));
                assertThat(empty.success()).isTrue();
                assertThat(((Map<?, ?>) empty.data()).get("linkIds")).isEqualTo(List.of());
                captured = new ArrayList<>(remote.requests);
                assertThat(captured).hasSize(4).allMatch(call -> call.method().equals("POST")
                        && call.tenant().equals("1001") && call.subject().equals("zhangsan") && call.authVersion().equals("7"));
                assertThat(captured.get(2).path()).isEqualTo(ROOT + "authorize-scope");
                assertThat(JSON.readTree(JSON.writeValueAsBytes(captured.get(2).body())))
                        .isEqualTo(JSON.readTree(JSON.writeValueAsBytes(expected)));
                assertThat(captured.get(3).body()).isEqualTo(Map.of("gid", "g1", "linkIds", List.of()));
            }
        }
    }

    @Test
    void invalidFrozenShapeOrPrincipalMakesNoRequestAndUnsupportedAdapterNeverFallsBack() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                var unknownScope = request();
                var scope = new LinkedHashMap<>((Map<String, Object>) unknownScope.get("scope"));
                scope.put("sql", "private expression"); unknownScope.put("scope", scope);
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, unknownScope), "INVALID_QUERY");
                var missing = request(); missing.remove("scope");
                failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, missing), "INVALID_QUERY");
                var conflictingUrl = request(); conflictingUrl.put("fullShortUrl", "https://nurl.ink/owned");
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, conflictingUrl), "INVALID_QUERY");
                var unknown = request(); unknown.put("linkIds", List.of(1L));
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, unknown), "INVALID_QUERY");
                failure(gateway.submitFrozenStatisticsJob(null, request()), "FORBIDDEN");
                var system = new ToolContext("s", "system", Map.of(), AgentPrincipal.system("system"));
                failure(gateway.recoverExistingFrozenStatisticsJob(system, request()), "FORBIDDEN");
                failure(gateway.authorizeStatisticsScope(new ToolContext("s", "other", Map.of(), StatsTestFixtures.PRINCIPAL),
                        Map.of("gid", "g1", "linkIds", List.of())), "FORBIDDEN");
                failure(gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1")), "INVALID_QUERY");
                failure(gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of(2L, 1L))), "INVALID_QUERY");
                failure(gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of(1.0))), "INVALID_QUERY");
                failure(gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of(), "tenantId", "other")), "INVALID_QUERY");
                assertThat(remote.requests).isEmpty();
            }
        }
        var unsupported = new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No GET fallback"); }
            public ToolResult post(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("No POST fallback"); }
            public ToolResult submitStatisticsJob(ToolContext context, Map<String, Object> query) { throw new AssertionError("No current-group submission"); }
            public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> query) { throw new AssertionError("No current-group recovery"); }
        };
        failure(unsupported.submitFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
        failure(unsupported.recoverExistingFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
        failure(unsupported.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of())), PROTOCOL);
    }

    @Test
    void oldServicesAndMismatchedAuthorizationFailClosedWithoutRetryOrRemoteDetails() throws Exception {
        for (boolean bounded : List.of(true, false)) {
            try (var remote = new Fixture()) {
                var gateway = remote.gateway(bounded);
                for (int status : List.of(404, 405, 501)) {
                    remote.status = status;
                    failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
                    failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
                    failure(gateway.authorizeStatisticsScope(CONTEXT, Map.of("gid", "g1", "linkIds", List.of())), PROTOCOL);
                }
                assertThat(remote.requests).hasSize(9);
                remote.status = 200;
                remote.body = "{\"success\":true,\"data\":{\"jobId\":\"job-original\",\"state\":\"RUNNING\"}}";
                failure(gateway.submitFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
                remote.body = "{\"code\":\"REPLAY_UNAVAILABLE\",\"message\":\"private SQL\",\"data\":{\"secret\":\"credentials\"}}";
                failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()), "REPLAY_UNAVAILABLE");
                var authorization = Map.<String, Object>of("gid", "g1", "linkIds", List.of(1L, 2L), "ownershipVersion", VERSION);
                remote.body = envelope(Map.of("allowed", true, "linkIds", List.of(1L, 2L)));
                failure(gateway.authorizeStatisticsScope(CONTEXT, authorization), PROTOCOL);
                for (String field : List.of("tenantId", "subjectId", "gid", "memberHash")) {
                    var changed = selected(List.of(1L, 2L)); changed.put(field, field.equals("memberHash") ? "b".repeat(64) : "other");
                    remote.body = envelope(changed);
                    failure(gateway.authorizeStatisticsScope(CONTEXT, authorization), "FORBIDDEN");
                }
                remote.body = envelope(selected(List.of(1L, 2L, 3L)));
                failure(gateway.authorizeStatisticsScope(CONTEXT, authorization), "FORBIDDEN");
                var changedVersion = selected(List.of(1L, 2L)); changedVersion.put("ownershipVersion", "b".repeat(64));
                remote.body = envelope(changedVersion);
                failure(gateway.authorizeStatisticsScope(CONTEXT, authorization), "QUERY_SCOPE_CHANGED");
                remote.body = "{\"code\":\"0\",\"success\":false,\"data\":{\"jobId\":\"job-original\",\"state\":\"RUNNING\"}}";
                failure(gateway.recoverExistingFrozenStatisticsJob(CONTEXT, request()), PROTOCOL);
                assertThat(remote.requests).hasSize(19).allMatch(call -> call.method().equals("POST")
                        && List.of(ROOT + "frozen-jobs", ROOT + "frozen-jobs/recover-existing", ROOT + "authorize-scope").contains(call.path()));
            }
        }
    }

    private static Map<String, Object> request() {
        List<Long> ids = List.of(1L, 2L);
        String hash = FrozenQueryScope.memberHash(ids);
        var scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "parent-scope", hash, 2,
                "e".repeat(64), FrozenQueryScope.shardIdFor("parent-scope", 0, hash), 0, 1, hash, ids);
        return new LinkedHashMap<>(Map.of("requestId", "stable-request-1", "gid", "g1", "startDate", "2026-09-01",
                "endDate", "2026-09-02", "queryKind", "LINK_METRICS", "scope", scope.asMap()));
    }

    private static LinkedHashMap<String, Object> selected(List<Long> ids) {
        return new LinkedHashMap<>(Map.of("schemaVersion", "selected-scope/v1", "allowed", true, "tenantId", "1001",
                "subjectId", "zhangsan", "authVersion", 7L, "gid", "g1", "linkIds", ids,
                "memberHash", FrozenQueryScope.memberHash(ids), "ownershipVersion", VERSION));
    }

    private static String envelope(Object data) throws Exception { return JSON.writeValueAsString(Map.of("code", "0", "data", data)); }

    private static void failure(ToolResult result, String code) {
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", code));
        assertThat(result.message()).doesNotContain("private", "SQL", "credentials", "secret");
    }

    private record Captured(String method, String path, Map<String, Object> body, String tenant, String subject, String authVersion) {}

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<Captured> requests = new ConcurrentLinkedQueue<>();
        private volatile int status = 200;
        private volatile String body = "{\"code\":\"0\",\"data\":{\"jobId\":\"job-original\",\"state\":\"RUNNING\"}}";

        private Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var headers = exchange.getRequestHeaders();
                Map<String, Object> request = JSON.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(), request,
                        headers.getFirst("X-Agent-UserId"), headers.getFirst("X-Agent-Username"), headers.getFirst("X-Agent-Auth-Version")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
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
