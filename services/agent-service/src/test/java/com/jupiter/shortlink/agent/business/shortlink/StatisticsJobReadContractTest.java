package com.jupiter.shortlink.agent.business.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Dedicated backend reads never turn missing/expired/denied evidence into a new submission. */
class StatisticsJobReadContractTest {
    private static final String BASE = "http://admin.test";
    private static final String PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs/job-1";
    private static final ToolContext CONTEXT = new ToolContext("session-1", "zhangsan", Map.of(), StatsTestFixtures.PRINCIPAL);
    private static final String PROTOCOL = "STATISTICS_READ_PROTOCOL_UNAVAILABLE";

    @Test
    void exactStatusAndPageReadsPreservePendingAndPartialEvidenceWithTrustedHeaders() {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo(BASE + PATH)).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-UserId", "1001"))
                .andExpect(header("X-Agent-Auth-Version", "7"))
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andExpect(header("X-Agent-Internal-Token", StatsTestFixtures.SECRET))
                .andRespond(withSuccess("{\"code\":\"0\",\"data\":{\"jobId\":\"job-1\",\"state\":\"RUNNING\",\"status\":\"PENDING\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + PATH + "/page?")))
                .andExpect(queryParam("pageIndex", "2")).andExpect(queryParam("size", "500")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-UserId", "1001"))
                .andRespond(withSuccess("""
                        {"code":"0","data":{"items":[{"pv":13}],"metrics":{"pv":13},
                         "meta":{"snapshotId":"job-1","pageIndex":2,"nextPageIndex":3,"completeness":"PARTIAL"}}}
                        """, MediaType.APPLICATION_JSON));
        var gateway = new ShortLinkBusinessHttpGateway(properties(), rest);
        ToolResult status = gateway.readStatisticsJob(CONTEXT, "job-1");
        assertThat(status.success()).isTrue();
        assertThat(((Map<?, ?>) status.data()).get("state")).isEqualTo("RUNNING");
        var page = gateway.readStatisticsJobPage(CONTEXT, "job-1", 2, 500);
        assertThat(page.success()).isTrue();
        var meta = (Map<?, ?>) ((Map<?, ?>) page.data()).get("meta");
        assertThat(meta.get("nextPageIndex")).isEqualTo(3);
        assertThat(meta.get("completeness")).isEqualTo("PARTIAL");
        server.verify();
    }

    @Test
    void boundedTransportKeepsBusinessCodesAndNeverExposesUntrustedFailureDetails() throws Exception {
        try (var remote = new ReadFixture()) {
            var gateway = remote.gateway();
            List<String> codes = List.of("FORBIDDEN", "SNAPSHOT_EXPIRED", "QUERY_SCOPE_CHANGED", "NOT_READY", "INVALID_QUERY", "UNAVAILABLE");
            for (String code : codes) {
                remote.body = new ObjectMapper().writeValueAsString(Map.of("code", code, "success", true,
                        "message", "private remote data", "data", Map.of("private", "sensitive query")));
                var result = gateway.readStatisticsJob(CONTEXT, "job-1");
                failure(result, code);
                assertThat(result.message()).doesNotContain("private", "sensitive");
            }
            assertThat(remote.requests).hasSize(codes.size()).allMatch(request -> request.equals("GET " + PATH + " 1001 7"));
        }
    }

    @Test
    void httpAndTransportFailuresAreMachineReadableWithoutInventingMissingJobOrRetrying() throws Exception {
        try (var remote = new ReadFixture()) {
            var gateway = remote.gateway();
            int[] statuses = {401, 403, 404, 405, 501, 429, 503};
            for (int status : statuses) {
                remote.status = status;
                String code = status == 401 || status == 403 ? "FORBIDDEN"
                        : status == 404 || status == 405 || status == 501 ? PROTOCOL : "REMOTE_UNAVAILABLE";
                failure(gateway.readStatisticsJobPage(CONTEXT, "job-1", 0, 500), code);
            }
            assertThat(remote.requests).hasSize(statuses.length).allMatch(request -> request.startsWith("GET " + PATH + "/page?"));
            remote.server.stop(0);
            failure(gateway.readStatisticsJob(CONTEXT, "job-1"), "REMOTE_UNAVAILABLE");
        }

        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo(BASE + PATH)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + PATH + "/page?")))
                .andExpect(queryParam("pageIndex", "0")).andExpect(queryParam("size", "500"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        var compatible = new ShortLinkBusinessHttpGateway(properties(), rest);
        failure(compatible.readStatisticsJob(CONTEXT, "job-1"), "FORBIDDEN");
        failure(compatible.readStatisticsJobPage(CONTEXT, "job-1", 0, 500), PROTOCOL);
        server.verify();
    }

    @Test
    void invalidInputsAndPrincipalsMakeNoIoAndLegacyGatewayDoesNotFallBack() throws Exception {
        try (var remote = new ReadFixture()) {
            var gateway = remote.gateway();
            for (String job : List.of("../job", "job?tenant=other", "x".repeat(129), ""))
                failure(gateway.readStatisticsJob(CONTEXT, job), "INVALID_QUERY");
            failure(gateway.readStatisticsJobPage(CONTEXT, "job-1", -1, 500), "INVALID_QUERY");
            failure(gateway.readStatisticsJobPage(CONTEXT, "job-1", 0, 501), "INVALID_QUERY");
            failure(gateway.readStatisticsJobPage(CONTEXT, "job-1", 0, 100), "INVALID_QUERY");
            failure(gateway.readStatisticsJobPage(CONTEXT, "job-1", 0, 0), "INVALID_QUERY");
            failure(gateway.readStatisticsJob(null, "job-1"), "FORBIDDEN");
            failure(gateway.readStatisticsJob(new ToolContext("s", "zhangsan", Map.of()), "job-1"), "FORBIDDEN");
            failure(gateway.readStatisticsJob(new ToolContext("s", "someone-else", Map.of(), StatsTestFixtures.PRINCIPAL), "job-1"), "FORBIDDEN");
            failure(gateway.readStatisticsJob(new ToolContext("s", "system", Map.of(), AgentPrincipal.system("system")), "job-1"), "FORBIDDEN");
            assertThat(remote.requests).isEmpty();

            ShortLinkBusinessGateway legacy = new ShortLinkBusinessGateway() {
                public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                    throw new AssertionError("No fallback to legacy unstructured GET");
                }
                public ToolResult post(String path, ToolContext context, Map<String, Object> payload) {
                    throw new AssertionError("No new submission");
                }
            };
            failure(legacy.readStatisticsJob(CONTEXT, "job-1"), PROTOCOL);
            failure(legacy.readStatisticsJobPage(CONTEXT, "job-1", 0, 500), PROTOCOL);
        }
    }

    @Test
    void malformedOrLegacySuccessEnvelopesFailClosedWithoutTreatingEmptyDataAsZero() throws Exception {
        try (var remote = new ReadFixture()) {
            var gateway = remote.gateway();
            Map<String, Object> missingData = new LinkedHashMap<>();
            missingData.put("code", "0");
            missingData.put("data", null);
            List<Map<String, Object>> envelopes = List.of(Map.of(), Map.of("success", true, "data", Map.of("jobId", "job-1")),
                    Map.of("code", "bad code with private text"), missingData,
                    Map.of("code", "0", "data", "private unexpected text"), Map.of("code", "0", "data", Map.of()));
            for (var envelope : envelopes) {
                remote.body = new ObjectMapper().writeValueAsString(envelope);
                failure(gateway.readStatisticsJob(CONTEXT, "job-1"), PROTOCOL);
            }
            remote.body = "null";
            failure(gateway.readStatisticsJob(CONTEXT, "job-1"), PROTOCOL);
            remote.body = "{broken";
            failure(gateway.readStatisticsJob(CONTEXT, "job-1"), PROTOCOL);
            assertThat(remote.requests).hasSize(envelopes.size() + 2).allMatch(request -> request.startsWith("GET " + PATH + " "));
        }
    }

    private static void failure(ToolResult result, String code) {
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", code));
    }

    private static AgentProperties properties() {
        var properties = new AgentProperties();
        properties.getBusiness().setBaseUrl(BASE);
        properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
        return properties;
    }

    /** Short-lived backend HTTP fixture exercises the production bounded transport, not an application. */
    private static final class ReadFixture implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
        private volatile int status = 200;
        private volatile String body = "{}";

        private ReadFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " " + exchange.getRequestHeaders().getFirst("X-Agent-UserId")
                        + " " + exchange.getRequestHeaders().getFirst("X-Agent-Auth-Version"));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }

        private ShortLinkBusinessHttpGateway gateway() {
            var configuration = properties();
            configuration.getBusiness().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            return new ShortLinkBusinessHttpGateway(configuration, new BoundedHttpTransport());
        }

        public void close() { server.stop(0); }
    }
}
