package com.nageoffer.shortlink.admin.remote;

import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskPolicyDisableReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.RiskReviewReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskReviewRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.RiskShortLinkDetailRespDTO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = AgentRiskRemoteServiceTest.FeignTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        }
)
class AgentRiskRemoteServiceTest {

    private static final RecordingHttpServer SERVER = RecordingHttpServer.start();

    @Autowired
    private AgentRiskRemoteService agentRiskRemoteService;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("short-link.agent.admin.remote-url", SERVER::baseUrl);
    }

    @AfterAll
    static void afterAll() {
        SERVER.stop();
    }

    @BeforeEach
    void setUp() {
        SERVER.reset();
    }

    @Test
    void groupOverviewSendsInternalPathAndTrustedHeaders() {
        Result<RiskGroupOverviewRespDTO> result = agentRiskRemoteService.groupOverview(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("GET");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/groups/g1/overview");
        assertTrustedHeaders();
        assertThat(SERVER.lastRequest.body()).isEmpty();
    }

    @Test
    void groupShortLinksSendsInternalPathAndTrustedHeaders() {
        Result<List<RiskShortLinkCardRespDTO>> result = agentRiskRemoteService.groupShortLinks(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("GET");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/groups/g1/short-links");
        assertTrustedHeaders();
    }

    @Test
    void shortLinkDetailUsesInternalPathVariables() {
        Result<RiskShortLinkDetailRespDTO> result = agentRiskRemoteService.shortLinkDetail(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "nurl.ink",
                "abc123"
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("GET");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/groups/g1/short-links/nurl.ink/abc123");
        assertTrustedHeaders();
    }

    @Test
    void eventsSendsInternalQueryParametersAndTrustedHeaders() {
        Result<RiskPageRespDTO<?>> result = agentRiskRemoteService.events(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "g1",
                "SHORT_LINK",
                "nurl.ink",
                "abc123",
                2,
                20
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("GET");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/events");
        assertThat(SERVER.lastRequest.query()).contains("gid=g1");
        assertThat(SERVER.lastRequest.query()).contains("targetType=SHORT_LINK");
        assertThat(SERVER.lastRequest.query()).contains("domain=nurl.ink");
        assertThat(SERVER.lastRequest.query()).contains("shortUri=abc123");
        assertThat(SERVER.lastRequest.query()).contains("pageNo=2");
        assertThat(SERVER.lastRequest.query()).contains("pageSize=20");
        assertTrustedHeaders();
    }

    @Test
    void reviewSendsInternalPathHeadersAndBody() {
        RiskReviewReqDTO request = new RiskReviewReqDTO();
        request.setEventId("event-1");
        request.setReviewer("trusted-user");
        request.setReviewAction("WATCH");

        Result<RiskReviewRespDTO> result = agentRiskRemoteService.review(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                request
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("POST");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/reviews");
        assertTrustedHeaders();
        assertThat(SERVER.lastRequest.body())
                .contains("\"eventId\":\"event-1\"")
                .contains("\"reviewer\":\"trusted-user\"")
                .contains("\"reviewAction\":\"WATCH\"");
    }

    @Test
    void disablePolicySendsInternalPathHeadersAndBody() {
        RiskPolicyDisableReqDTO request = new RiskPolicyDisableReqDTO();
        request.setGid("g1");
        request.setReviewer("trusted-user");
        request.setReason("false positive");
        request.setTraceId("trace-1");

        Result<Map<String, Object>> result = agentRiskRemoteService.disablePolicy(
                "internal-token",
                "trusted-user",
                "1001",
                "Trusted Name",
                "policy-1",
                request
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(SERVER.lastRequest.method()).isEqualTo("POST");
        assertThat(SERVER.lastRequest.path()).isEqualTo("/internal/short-link-agent/v1/risk/policies/policy-1/disable");
        assertTrustedHeaders();
        assertThat(SERVER.lastRequest.body())
                .contains("\"gid\":\"g1\"")
                .contains("\"reviewer\":\"trusted-user\"")
                .contains("\"reason\":\"false positive\"")
                .contains("\"traceId\":\"trace-1\"");
    }

    private void assertTrustedHeaders() {
        assertThat(SERVER.lastRequest.header("X-Agent-Internal-Token")).isEqualTo("internal-token");
        assertThat(SERVER.lastRequest.header("X-Agent-Username")).isEqualTo("trusted-user");
        assertThat(SERVER.lastRequest.header("X-Agent-UserId")).isEqualTo("1001");
        assertThat(SERVER.lastRequest.header("X-Agent-RealName")).isEqualTo("Trusted Name");
    }

    @Configuration
    @EnableAutoConfiguration
    @EnableFeignClients(clients = AgentRiskRemoteService.class)
    static class FeignTestConfiguration {
    }

    private static class RecordingHttpServer {

        private final HttpServer server;

        private volatile RecordedRequest lastRequest;

        private RecordingHttpServer(HttpServer server) {
            this.server = server;
        }

        private static RecordingHttpServer start() {
            try {
                HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                RecordingHttpServer recordingServer = new RecordingHttpServer(httpServer);
                httpServer.createContext("/", recordingServer::handle);
                httpServer.start();
                return recordingServer;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void reset() {
            lastRequest = null;
        }

        private void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((key, values) ->
                    headers.put(key, values.isEmpty() ? "" : values.get(0)));
            lastRequest = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    headers,
                    new String(requestBody, StandardCharsets.UTF_8)
            );
            byte[] responseBody = responseBody(exchange).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        }

        private String responseBody(HttpExchange exchange) {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/short-links")) {
                return "{\"code\":\"0\",\"message\":\"success\",\"data\":[]}";
            }
            if (path.endsWith("/events")) {
                return "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"records\":[],\"total\":0,\"pageNo\":1,\"pageSize\":10}}";
            }
            if (path.endsWith("/reviews")) {
                return "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"reviewId\":\"review-1\"}}";
            }
            if (path.endsWith("/disable")) {
                return "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"disabled\":true}}";
            }
            return "{\"code\":\"0\",\"message\":\"success\",\"data\":{}}";
        }
    }

    private record RecordedRequest(
            String method,
            String path,
            String query,
            Map<String, String> headers,
            String body
    ) {

        private String header(String name) {
            return headers.entrySet().stream()
                    .filter(each -> each.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}
