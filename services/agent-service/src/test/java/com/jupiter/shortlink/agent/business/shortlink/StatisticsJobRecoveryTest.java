package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.agent.tool.shortlink.StatisticsQueryJobTools;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Dedicated recovery must never turn uncertain submission into another create request. */
class StatisticsJobRecoveryTest {
    private static final String PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs/recover-existing";
    private static final Map<String, Object> REQUEST = Map.of("requestId", "child-1", "gid", "g1",
            "startDate", "2026-07-01", "endDate", "2026-08-01", "queryKind", "METRICS");
    private static final ToolContext CONTEXT = new ToolContext("session-1", "zhangsan", Map.of(),
            StatsTestFixtures.PRINCIPAL);

    @Test
    void recoversOriginalJobWithFrozenRequestAndTrustedIdentityInOneRequest() {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://admin.test" + PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Agent-UserId", "1001"))
                .andExpect(header("X-Agent-Auth-Version", "7"))
                .andExpect(content().json("""
                        {"requestId":"child-1","gid":"g1","startDate":"2026-07-01",
                         "endDate":"2026-08-01","queryKind":"METRICS"}
                        """, true))
                .andRespond(withSuccess("""
                        {"code":"0","data":{"jobId":"original-job","state":"RUNNING","status":"PENDING"}}
                        """, MediaType.APPLICATION_JSON));
        var result = new ShortLinkBusinessHttpGateway(properties("http://admin.test"), rest)
                .recoverExistingStatisticsJob(CONTEXT, REQUEST);
        assertThat(result.success()).isTrue();
        assertThat((Map<String, Object>) result.data()).containsEntry("jobId", "original-job");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPLAY_UNAVAILABLE", "CONFLICT", "FORBIDDEN", "RECOVERY_PROTOCOL_UNAVAILABLE"})
    void keepsStructuredFailureAndDoesNotTrySubmit(String code) {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://admin.test" + PATH)).andRespond(withSuccess(
                "{\"code\":\"" + code + "\",\"message\":\"untrusted remote detail\"}", MediaType.APPLICATION_JSON));
        var result = new ShortLinkBusinessHttpGateway(properties("http://admin.test"), rest)
                .recoverExistingStatisticsJob(CONTEXT, REQUEST);
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", code));
        assertThat(result.message()).doesNotContain("untrusted");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"404,RECOVERY_PROTOCOL_UNAVAILABLE", "405,RECOVERY_PROTOCOL_UNAVAILABLE",
            "501,RECOVERY_PROTOCOL_UNAVAILABLE", "403,FORBIDDEN", "503,REMOTE_UNAVAILABLE"})
    void actualTransportKeepsHttpStatusAndNeverFallsBack(int status, String expectedCode) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var paths = new ArrayList<String>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var gateway = new ShortLinkBusinessHttpGateway(
                    properties("http://127.0.0.1:" + server.getAddress().getPort()), new BoundedHttpTransport());
            var result = gateway.recoverExistingStatisticsJob(CONTEXT, REQUEST);
            assertThat(result.success()).isFalse();
            assertThat(result.data()).isEqualTo(Map.of("code", expectedCode));
            assertThat(paths).containsExactly(PATH);
        } finally { server.stop(0); }
    }

    @Test
    void missingReceiptFieldsFailClosed() {
        RestTemplate rest = new RestTemplate();
        var server = MockRestServiceServer.createServer(rest);
        server.expect(requestTo("http://admin.test" + PATH)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"state\":\"QUEUED\"}}", MediaType.APPLICATION_JSON));
        var result = new ShortLinkBusinessHttpGateway(properties("http://admin.test"), rest)
                .recoverExistingStatisticsJob(CONTEXT, REQUEST);
        assertThat(result.success()).isFalse();
        assertThat(result.data()).isEqualTo(Map.of("code", "RECOVERY_PROTOCOL_UNAVAILABLE"));
        server.verify();
    }

    @Test
    void oldGatewayAndOversizedIdentityCannotFallBackToPost() {
        ShortLinkBusinessGateway legacy = new ShortLinkBusinessGateway() {
            public ToolResult get(String path, ToolContext context, Map<String, Object> query) {
                throw new AssertionError("No GET expected");
            }
            public ToolResult post(String path, ToolContext context, Map<String, Object> body) {
                throw new AssertionError("No submission expected");
            }
        };
        assertThat(legacy.recoverExistingStatisticsJob(CONTEXT, REQUEST).data())
                .isEqualTo(Map.of("code", "RECOVERY_PROTOCOL_UNAVAILABLE"));
        assertThat(new StatisticsQueryJobTools(legacy).submit("r".repeat(97), "g1", "2026-07-01",
                "2026-08-01", "METRICS", null, null).success()).isFalse();
        var request = new java.util.LinkedHashMap<>(REQUEST);
        request.put("tenantId", "another-tenant");
        var gateway = new ShortLinkBusinessHttpGateway(properties("http://admin.test"), new RestTemplate());
        assertThat(gateway.recoverExistingStatisticsJob(CONTEXT, request).data())
                .isEqualTo(Map.of("code", "INVALID_QUERY"));
    }

    private static AgentProperties properties(String base) {
        var properties = new AgentProperties();
        properties.getBusiness().setBaseUrl(base);
        properties.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
        return properties;
    }
}
