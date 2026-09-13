package com.jupiter.shortlink.agent.riskprofile;

import static org.assertj.core.api.Assertions.*;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.agent.riskprofile.source.ShortLinkBusinessRiskStatsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

class ScheduledRiskStatsAuthorizationTest {
    private final BoundedHttpTransport transport = new BoundedHttpTransport();
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AgentProperties properties = new AgentProperties();
    private final Instant now = Instant.ofEpochMilli(StatsTestFixtures.NOW);
    private final List<Map<String, String>> observedHeaders = new ArrayList<>();
    private final List<Map<String, Object>> observedResolveRequests = new ArrayList<>();
    private ShortLinkBusinessRiskStatsGateway gateway;
    private volatile boolean denyWindows;
    private volatile boolean repeatCursor;
    private volatile boolean wrongTenant;
    private volatile boolean changedEpoch;
    private volatile boolean independentCuts;
    private volatile boolean changedWindowCut;
    private volatile Throwable serverFailure;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        properties.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getBusiness().setUsername("localdev");
        properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
        AgentAuthorityClient authority = new AgentAuthorityClient(properties, transport);
        gateway = new ShortLinkBusinessRiskStatsGateway(properties, transport, authority);
        server.createContext("/", exchange -> {
            try {
                Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((key, values) -> headers.put(key, values.get(0)));
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<String, Object> request = body.length == 0 ? Map.of() : json.readValue(body, Map.class);
                byte[] response = json.writeValueAsBytes(answer(exchange.getRequestURI(), headers, request));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                serverFailure = failure;
                exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void tearDown() {
        if (server != null) server.stop(0);
        assertThat(serverFailure).isNull();
    }

    private Map<String, Object> answer(URI uri, Map<String, String> headers, Map<String, Object> request) {
            observedHeaders.add(headers);
            if (uri.getPath().endsWith("scheduled-scope")) {
                assertThat(headers.get("X-Agent-Principal-Mode")).isEqualTo("SYSTEM");
                assertThat(uri.getQuery()).contains("tenantId=1001", "gid=g1");
                if (denyWindows) return Map.of("code", "401");
                return Map.of("code", "0", "data", Map.of("tenantId", wrongTenant ? "1002" : "1001", "username", "Jupiter",
                        "authVersion", 7L, "gids", List.of("g1")));
            }
            if (uri.getPath().endsWith("scheduled-scopes")) {
                assertThat(headers.get("X-Agent-Principal-Mode")).isEqualTo("SYSTEM");
                assertThat(headers.get("X-Agent-Username")).isEqualTo("localdev");
                if (repeatCursor) return Map.of("code", "0", "data", Map.of("items", List.of(), "nextCursor", "1001:0:0"));
                String query = uri.getQuery();
                if (!query.contains("cursor=")) return discovered("1001", "Jupiter", "g1", "1001:0:0");
                if (query.contains("1001")) return discovered("1002", "alice", "g2", "1002:0:0");
                return Map.of("code", "0", "data", Map.of("items", List.of()));
            }
            assertThat(headers).doesNotContainKey("X-Agent-Principal-Mode");
            String tenant = headers.get("X-Agent-UserId");
            assertThat(headers.get("X-Agent-Username")).isEqualTo(tenant.equals("1001") ? "Jupiter" : "alice");
            assertThat(headers.get("X-Agent-Auth-Version")).isEqualTo("7");
            String gid = tenant.equals("1001") ? "g1" : "g2";
            if (uri.getPath().endsWith("authorization/resolve")) {
                observedResolveRequests.add(request);
                assertThat(request.get("gid")).isEqualTo(gid);
                return Map.of("code", "0", "data", Map.of("tenantId", tenant, "ownershipVersion", "ownership-" + tenant,
                        "links", List.of(row(tenant, gid, "requested", 168))));
            }
            Map<String, Object> metadata = StatsTestFixtures.meta(); metadata.put("tenantId", tenant);
            if (uri.getPath().endsWith("active-link-query")) {
                if (wrongTenant) return evidence(List.of(row("1002", "g2", "requested", 168)), tenantMeta("1002"));
                if (changedEpoch && tenant.equals("1002")) metadata.put("recoveryEpoch", "epoch-2");
                if (independentCuts && tenant.equals("1002")) {
                    metadata.put("effectiveEnd", StatsTestFixtures.NOW - 1000);
                    metadata.put("sourceCut", Map.of("click", Map.of("0", 125L)));
                    metadata.put("manifestVersion", Map.of("newer", "build"));
                }
                return evidence(List.of(row(tenant, gid, "requested", 168)), metadata);
            }
            if (uri.getPath().endsWith("short-link-windows")) {
                if (denyWindows) return Map.of("code", "401", "message", "Account is unavailable");
                if (changedWindowCut) metadata.put("effectiveEnd", StatsTestFixtures.NOW - 1000);
                return evidence(List.of(row(tenant, gid, "2h", 2), row(tenant, gid, "24h", 24), row(tenant, gid, "7d", 168)), metadata);
            }
            throw new AssertionError("Unexpected request " + uri.getPath());
    }

    @Test void discoversMultipleTenantsByExplicitGroupsAndReauthorizesWindowsAsOwner() {
        var candidates = gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)));
        assertThat(candidates).extracting(candidate -> candidate.tenantId()).containsExactly("1001", "1002");
        assertThat(observedResolveRequests).hasSize(2).allSatisfy(request -> assertThat(request).containsKey("gid"));
        assertThat(candidates.get(0).principal()).isEqualTo(new AgentPrincipal("1001", "Jupiter", 7, false));
        assertThat(candidates.get(1).principal()).isEqualTo(new AgentPrincipal("1002", "alice", 7, false));
        assertThat(gateway.loadStatsWindows(candidates.get(0), now)).containsOnlyKeys("2h", "24h", "7d");
        assertThat(gateway.loadStatsWindows(candidates.get(1), now).get("2h").tenantId()).isEqualTo("1002");
    }

    @Test void stoppedAccountFailsClosedAfterDiscoveryAndARepeatedBatchStartsFresh() {
        var candidates = gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)));
        denyWindows = true;
        assertThatThrownBy(() -> gateway.loadStatsWindows(candidates.get(0), now)).hasMessageContaining("unavailable");
        denyWindows = false;
        var retried = gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)));
        assertThat(retried).hasSize(2);
        assertThat(gateway.loadStatsWindows(retried.get(0), now)).hasSize(3);
        assertThat(observedResolveRequests).hasSize(4);
    }

    @Test void repeatedDiscoveryCursorNeverReturnsPartialSuccess() {
        repeatCursor = true;
        assertThatThrownBy(() -> gateway.listActiveShortLinks(now.minus(Duration.ofDays(7))))
                .hasMessageContaining("cursor did not advance");
    }

    @Test void wrongTenantStatisticsAreRejected() {
        wrongTenant = true;
        assertThatThrownBy(() -> gateway.listActiveShortLinks(now.minus(Duration.ofDays(7))))
                .hasMessageContaining("tenant changed");
    }

    @Test void changedRecoveryEpochFailsWholeDiscoveryAndRetryCanRecover() {
        changedEpoch = true;
        assertThatThrownBy(() -> gateway.listActiveShortLinks(now.minus(Duration.ofDays(7))))
                .hasMessageContaining("cut changed");
        changedEpoch = false;
        assertThat(gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)))).hasSize(2);
    }

    @Test void independentResourceSnapshotsMayAdvanceWithoutFailingTheWholeBatch() {
        independentCuts = true;
        assertThat(gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)))).hasSize(2);
    }

    @Test void aCandidateWindowCannotSilentlyChangeItsCutoff() {
        var candidates = gateway.listActiveShortLinks(now.minus(Duration.ofDays(7)));
        changedWindowCut = true;
        assertThatThrownBy(() -> gateway.loadStatsWindows(candidates.get(0), now)).hasMessageContaining("effectiveEnd changed");
    }

    @Test void exactScheduledJobIdentityIsBoundToPersistedTenantAndRevalidatedOnRetry() {
        var client = new com.jupiter.shortlink.agent.riskprofile.source.ScheduledRiskPrincipalClient(properties, transport,
                new AgentAuthorityClient(properties, transport));
        assertThat(client.resolve("1001", "g1")).isEqualTo(new AgentPrincipal("1001", "Jupiter", 7, false));
        wrongTenant = true;
        assertThatThrownBy(() -> client.resolve("1001", "g1")).isInstanceOf(SecurityException.class);
        wrongTenant = false; denyWindows = true;
        assertThatThrownBy(() -> client.resolve("1001", "g1")).isInstanceOf(SecurityException.class);
        denyWindows = false;
        assertThat(client.resolve("1001", "g1").system()).isFalse();
    }

    private static Map<String, Object> discovered(String tenant, String username, String gid, String cursor) {
        return Map.of("code", "0", "data", Map.of("items", List.of(Map.of("tenantId", tenant, "username", username,
                "authVersion", 7L, "gids", List.of(gid))), "nextCursor", cursor));
    }
    private static Map<String, Object> tenantMeta(String tenant) {
        var meta = StatsTestFixtures.meta(); meta.put("tenantId", tenant); return meta;
    }
    private static Map<String, Object> evidence(List<Map<String, Object>> rows, Map<String, Object> meta) {
        return Map.of("code", "0", "data", Map.of("items", rows, "metrics", Map.of(), "meta", meta));
    }
    private static Map<String, Object> row(String tenant, String gid, String window, int hours) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("linkId", Long.parseLong(tenant)); row.put("gid", gid); row.put("domain", "nurl.ink");
        row.put("shortUri", "abc" + tenant); row.put("fullShortUrl", "nurl.ink/abc" + tenant);
        row.put("window", window); row.put("pv", 10L); row.put("uv", 5L); row.put("uip", 3L);
        row.put("startInclusive", StatsTestFixtures.NOW - Duration.ofHours(hours).toMillis());
        row.put("endExclusive", StatsTestFixtures.NOW); return row;
    }
}
