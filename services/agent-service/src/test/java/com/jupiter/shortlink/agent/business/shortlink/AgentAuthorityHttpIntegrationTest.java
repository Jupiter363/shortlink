package com.jupiter.shortlink.agent.business.shortlink;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.agent.riskpolicy.service.CommandPolicyClient;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.source.ShortLinkBusinessRiskStatsGateway;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Real HTTP transport with a bounded local authority stub; no model, Kafka or end-to-end
 * application.
 */
class AgentAuthorityHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final AgentProperties properties = new AgentProperties();
    private final BoundedHttpTransport http = new BoundedHttpTransport();
    private AgentAuthorityClient authority;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final List<Map<String, String>> headers = new ArrayList<>();
    private java.util.function.BiFunction<String, Map<String, Object>, Object> responder;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    byte[] input = exchange.getRequestBody().readAllBytes();
                    Map<String, Object> request =
                            input.length == 0
                                    ? Map.of()
                                    : JSON.readValue(input, new TypeReference<>() {});
                    requests.add(request);
                    Map<String, String> supplied = new LinkedHashMap<>();
                    for (String name :
                            List.of(
                                    "X-Agent-Username",
                                    "X-Agent-UserId",
                                    "X-Agent-Auth-Version",
                                    "X-Agent-Internal-Token",
                                    "X-Agent-Principal-Mode"))
                        supplied.put(name, exchange.getRequestHeaders().getFirst(name));
                    headers.add(supplied);
                    byte[] body =
                            JSON.writeValueAsBytes(
                                    responder.apply(exchange.getRequestURI().getPath(), request));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        properties.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
        properties.getBusiness().setUsername("zhangsan");
        authority = new AgentAuthorityClient(properties, http);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", "0");
        response.put("data", data);
        return response;
    }

    private static Map<String, Object> link(long id) {
        return Map.of(
                "linkId",
                id,
                "gid",
                "g1",
                "domain",
                "nurl.ink",
                "shortUri",
                "u" + id,
                "fullShortUrl",
                "nurl.ink/u" + id);
    }

    @Test
    void resolveCarriesCurrentPrincipalAndPreservesIdsAboveJavascriptPrecision() {
        long id = 9_007_199_254_740_999L;
        responder =
                (path, q) ->
                        success(
                                Map.of(
                                        "tenantId",
                                        "1001",
                                        "ownershipVersion",
                                        "v1",
                                        "links",
                                        List.of(link(id))));
        var resolved = authority.resolve(StatsTestFixtures.PRINCIPAL, "g1", null, List.of(id));
        assertThat(resolved.contains(id, "g1")).isTrue();
        assertThat(requests.get(0).get("linkIds")).isEqualTo(List.of(id));
        assertThat(headers.get(0))
                .containsEntry("X-Agent-UserId", "1001")
                .containsEntry("X-Agent-Auth-Version", "7")
                .containsEntry("X-Agent-Internal-Token", StatsTestFixtures.SECRET)
                .containsEntry("X-Agent-Username", "zhangsan");
    }

    @Test
    void scheduledDiscoveryPagesOwnershipScopesAndKeepsUnknownCountsMissing() {
        AtomicInteger discoveryPages = new AtomicInteger();
        List<String> paths = new ArrayList<>();
        responder =
                (path, q) -> {
                    paths.add(path);
                    if (path.endsWith("/risk/scheduled-scopes")) {
                        if (discoveryPages.incrementAndGet() > 1)
                            return success(Map.of("items", List.of()));
                        return success(Map.of(
                                "items", List.of(Map.of("tenantId", "1001", "username", "zhangsan",
                                        "authVersion", 7, "gids", List.of("g1"))),
                                "nextCursor", "1001:0:0"));
                    }
                    if (path.endsWith("authorization/resolve")) {
                        boolean next = q.containsKey("afterLinkId");
                        Map<String, Object> scope =
                                new LinkedHashMap<>(
                                        Map.of(
                                                "tenantId",
                                                "1001",
                                                "ownershipVersion",
                                                "v1",
                                                "links",
                                                List.of(link(next ? 100 : 99))));
                        if (!next) scope.put("nextCursor", 99L);
                        return success(scope);
                    }
                    long id = ((Number) ((List<?>) q.get("linkIds")).get(0)).longValue();
                    var row = new LinkedHashMap<>(link(id));
                    row.put("pv", 3_000_000_000L);
                    return success(
                            Map.of(
                                    "items",
                                    List.of(row),
                                    "metrics",
                                    Map.of(),
                                    "meta",
                                    StatsTestFixtures.meta()));
                };
        var gateway = new ShortLinkBusinessRiskStatsGateway(properties, http, authority);
        var candidates =
                gateway.listActiveShortLinks(
                        Instant.ofEpochMilli(StatsTestFixtures.NOW).minus(Duration.ofDays(7)));
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(c -> c.linkId()).containsExactly(99L, 100L);
        assertThat(candidates.get(0).pv()).isEqualTo(3_000_000_000L);
        assertThat(candidates.get(0).uv()).isNull();
        assertThat(requests.get(3))
                .containsEntry("gid", "g1")
                .containsEntry("afterLinkId", 99)
                .containsEntry("ownershipVersion", "v1");
        assertThat(requests.get(2)).containsEntry("linkIds", List.of(99));
        assertThat(requests.get(4)).containsEntry("linkIds", List.of(100));
        assertThat(discoveryPages).hasValue(2);
        assertThat(paths).containsExactly(
                "/internal/short-link-admin/v1/agent-tools/risk/scheduled-scopes",
                "/internal/short-link-admin/v1/agent-tools/authorization/resolve",
                "/internal/short-link-admin/v1/agent-tools/risk/active-link-query",
                "/internal/short-link-admin/v1/agent-tools/authorization/resolve",
                "/internal/short-link-admin/v1/agent-tools/risk/active-link-query",
                "/internal/short-link-admin/v1/agent-tools/risk/scheduled-scopes");
        for (int index : List.of(0, 5))
            assertThat(headers.get(index)).containsEntry("X-Agent-Principal-Mode", "SYSTEM")
                    .containsEntry("X-Agent-UserId", null).containsEntry("X-Agent-Auth-Version", null);
        assertThat(headers.subList(1, 5)).allSatisfy(h -> assertThat(h)
                .containsEntry("X-Agent-Principal-Mode", null)
                .containsEntry("X-Agent-Username", "zhangsan")
                .containsEntry("X-Agent-UserId", "1001")
                .containsEntry("X-Agent-Auth-Version", "7"));
    }

    @Test
    void changedOwnershipCannotReturnPartialCandidateSuccess() {
        AtomicInteger pages = new AtomicInteger();
        List<String> paths = new ArrayList<>();
        responder =
                (path, q) -> {
                    paths.add(path);
                    if (path.endsWith("/risk/scheduled-scopes"))
                        return success(Map.of(
                                "items", List.of(Map.of("tenantId", "1001", "username", "zhangsan",
                                        "authVersion", 7, "gids", List.of("g1"))),
                                "nextCursor", "1001:0:0"));
                    if (path.endsWith("authorization/resolve"))
                        return success(
                                Map.of(
                                        "tenantId",
                                        "1001",
                                        "ownershipVersion",
                                        pages.incrementAndGet() == 1 ? "v1" : "v2",
                                        "links",
                                        List.of(link(99)),
                                        "nextCursor",
                                        99));
                    var row = new LinkedHashMap<>(link(99));
                    row.put("pv", 3_000_000_000L);
                    return success(
                            Map.of(
                                    "items",
                                    List.of(row),
                                    "metrics",
                                    Map.of(),
                                    "meta",
                                    StatsTestFixtures.meta()));
                };
        assertThatThrownBy(
                        () ->
                                new ShortLinkBusinessRiskStatsGateway(properties, http, authority)
                                        .listActiveShortLinks(
                                                Instant.ofEpochMilli(StatsTestFixtures.NOW)
                                                        .minus(Duration.ofDays(7))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("ownership changed");
        assertThat(pages).hasValue(2);
        assertThat(requests.get(3)).containsEntry("afterLinkId", 99)
                .containsEntry("ownershipVersion", "v1");
        assertThat(paths).containsExactly(
                "/internal/short-link-admin/v1/agent-tools/risk/scheduled-scopes",
                "/internal/short-link-admin/v1/agent-tools/authorization/resolve",
                "/internal/short-link-admin/v1/agent-tools/risk/active-link-query",
                "/internal/short-link-admin/v1/agent-tools/authorization/resolve");
        assertThat(headers.get(0)).containsEntry("X-Agent-Principal-Mode", "SYSTEM");
        assertThat(headers.subList(1, 4)).allSatisfy(h -> assertThat(h)
                .containsEntry("X-Agent-Principal-Mode", null)
                .containsEntry("X-Agent-UserId", "1001")
                .containsEntry("X-Agent-Auth-Version", "7"));
    }

    @Test
    void committedReceiptIsReadDespiteExpiredEvidenceWithoutAnotherMutation() {
        List<String> paths = new ArrayList<>();
        responder =
                (path, q) -> {
                    paths.add(path);
                    return success(
                            Map.of(
                                    "commandId",
                                    "command-1",
                                    "status",
                                    "COMMITTED",
                                    "policyId",
                                    "policy-1",
                                    "policyRevision",
                                    7,
                                    "committedAt",
                                    StatsTestFixtures.NOW));
                };
        var client = new CommandPolicyClient(properties, http, authority);
        var service =
                new RiskPolicyService(
                        client,
                        properties,
                        Clock.fixed(
                                Instant.ofEpochMilli(StatsTestFixtures.NOW + 9_999_999),
                                ZoneOffset.UTC));
        assertThat(
                        service.autoLimitRate(
                                StatsTestFixtures.PRINCIPAL,
                                StatsTestFixtures.profile(),
                                "command-1",
                                "policy-1"))
                .containsEntry("status", "COMMITTED");
        assertThat(paths)
                .containsExactly(
                        "/internal/short-link-admin/v1/agent-tools/policies/commands/command-1");
    }

    @Test
    void asynchronousToolReturnsPendingAfterOneHttpRequestAndPreservesPrincipal() {
        List<String> paths = new ArrayList<>();
        responder =
                (path, q) -> {
                    paths.add(path);
                    return success(
                            Map.of(
                                    "jobId",
                                    "job-1",
                                    "state",
                                    "QUEUED",
                                    "status",
                                    "PENDING",
                                    "resultReady",
                                    false));
                };
        var gateway = new ShortLinkBusinessHttpGateway(properties, http);
        var tools = new com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobTools(gateway);
        var trusted =
                new org.springframework.ai.chat.model.ToolContext(
                        Map.of(
                                "sessionId",
                                "s1",
                                "username",
                                "zhangsan",
                                "principal",
                                StatsTestFixtures.PRINCIPAL.toState()));
        var result =
                tools.submit(
                        "request-1", "g1", "2026-07-01", "2026-08-01", "METRICS", null, trusted);
        assertThat(result.success()).isTrue();
        assertThat(((Map<?, ?>) result.data()).get("status")).isEqualTo("PENDING");
        assertThat(paths)
                .containsExactly("/internal/short-link-admin/v1/agent-tools/statistics/jobs");
        assertThat(headers.get(0))
                .containsEntry("X-Agent-UserId", "1001")
                .containsEntry("X-Agent-Auth-Version", "7");
        assertThat(requests.get(0)).doesNotContainKeys("tenantId", "subjectId", "authVersion");
        assertThat(tools.page("../../secret", 0, 500, trusted).success()).isFalse();
        assertThat(paths).hasSize(1);
    }
}
