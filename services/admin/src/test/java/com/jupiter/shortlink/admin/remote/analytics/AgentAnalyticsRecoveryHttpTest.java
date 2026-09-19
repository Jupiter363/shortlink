package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.controller.AgentToolInternalController;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual Admin route -> facade -> HTTP client; the upstream fixture counts every create request. */
class AgentAnalyticsRecoveryHttpTest {
    private static final String ROUTE = "/internal/short-link-admin/v1/agent-tools/statistics/jobs/recover-existing";
    private static final String BODY = "{\"requestId\":\"frozen-request\",\"gid\":\"g1\",\"fullShortUrl\":\"example.test/a\","
            + "\"startDate\":\"2026-07-01\",\"endDate\":\"2026-08-01\",\"queryKind\":\"DIMENSION_BREAKDOWN\","
            + "\"dimensions\":[\"province\",\"device\"],\"filters\":[{\"dimension\":\"province\",\"operator\":\"IN\",\"values\":[\"Zhejiang\"]}]}";
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger recoveries = new AtomicInteger();
    private final AtomicInteger resolves = new AtomicInteger();
    private final AtomicReference<JSONObject> recoveryBody = new AtomicReference<>();
    private final AtomicReference<JSONObject> resolveBody = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> identity = new AtomicReference<>();
    private int recoveryStatus;
    private String recoveryResponse;
    private HttpServer server;
    private GroupService groups;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        recoveryStatus = 200;
        recoveryResponse = JSON.toJSONString(Map.of("code", "0", "data", Map.of("jobId", "original-job", "state", "RUNNING")));
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int responseStatus = 200;
            String response;
            String path = exchange.getRequestURI().getPath();
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (path.equals("/internal/command/authorization/resolve")) {
                resolves.incrementAndGet();
                resolveBody.set(request);
                response = JSON.toJSONString(Map.of("tenantId", "1001", "links", List.of(Map.of("linkId", 99L))));
            } else if (path.equals("/internal/analytics/v1/jobs/recover-existing")) {
                recoveries.incrementAndGet();
                recoveryBody.set(request);
                identity.set(Map.of("method", exchange.getRequestMethod(),
                        "tenant", exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"),
                        "subject", exchange.getRequestHeaders().getFirst("x-shortlink-username"),
                        "authVersion", exchange.getRequestHeaders().getFirst("x-shortlink-auth-version")));
                responseStatus = recoveryStatus;
                response = recoveryResponse;
            } else if (path.equals("/internal/analytics/v1/jobs")) {
                creates.incrementAndGet();
                response = JSON.toJSONString(Map.of("code", "0", "data", Map.of("jobId", "unexpected-new-job", "state", "QUEUED")));
            } else {
                responseStatus = 404;
                response = "unexpected path";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        AnalyticsJsonClient client = new AnalyticsJsonClient(url, url, "local-fixture-internal-token-length-valid");
        groups = mock(GroupService.class);
        when(groups.count(any(Wrapper.class))).thenReturn(1L);
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(groups,
                        mock(ShortLinkActualRemoteService.class), new AgentAnalyticsFacade(client)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (server != null) server.stop(0);
    }

    @Test
    void dedicatedRouteForwardsTheExactIdentityScopeAndQueryWithoutSubmitting() throws Exception {
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.jobId").value("original-job"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(identity.get()).containsEntry("method", "POST").containsEntry("tenant", "1001")
                .containsEntry("subject", "alice").containsEntry("authVersion", "7");
        assertThat(resolveBody.get()).containsEntry("gid", "g1").containsEntry("fullShortUrl", "example.test/a");
        assertThat(recoveryBody.get()).containsOnlyKeys("requestId", "query").containsEntry("requestId", "frozen-request");
        JSONObject query = recoveryBody.get().getJSONObject("query");
        assertThat(query).containsEntry("tenantId", "1001").containsEntry("subjectId", "alice")
                .containsEntry("gid", "g1").containsEntry("queryKind", "DIMENSION_BREAKDOWN");
        assertThat(query.getLong("authVersion")).isEqualTo(7L);
        assertThat(query.getList("linkIds", Long.class)).containsExactly(99L);
        assertThat(query.getList("dimensions", String.class)).containsExactly("province", "device");
        assertThat(query.getJSONArray("filters").getJSONObject(0).getJSONArray("values").getString(0)).isEqualTo("Zhejiang");
        assertNoCreation();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 405, 501})
    void unsupportedOldServicesAreProtocolFailuresEvenIfTheirBodyLooksSuccessful(int statusCode) throws Exception {
        recoveryStatus = statusCode;
        mvc.perform(request()).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("RECOVERY_PROTOCOL_UNAVAILABLE"));
        assertNoCreation();
    }

    @ParameterizedTest
    @CsvSource({"200,REPLAY_UNAVAILABLE", "200,CONFLICT", "403,FORBIDDEN"})
    void typedFailuresSurviveTheEntireProxyWithoutResponseBodyOrMessageLeakage(int statusCode, String code) throws Exception {
        recoveryStatus = statusCode;
        recoveryResponse = JSON.toJSONString(Map.of("code", code, "message", "PRIVATE SQL OR TOKEN MUST NOT LEAK"));
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value("Statistics job recovery unavailable"));
        assertNoCreation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{}", "{\"code\":\"0\",\"data\":[]}", "{\"code\":\"0\",\"data\":{\"state\":\"RUNNING\"}}",
            "{\"code\":\"0\",\"data\":{\"jobId\":\"old-job\",\"state\":\"UNKNOWN\"}}"})
    void malformedOrIncompatibleSuccessNeverTriggersFreshSubmission(String response) throws Exception {
        recoveryResponse = response;
        mvc.perform(request()).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("RECOVERY_PROTOCOL_UNAVAILABLE"));
        assertNoCreation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCurrentPrincipalOrRevokedGroupStopsBeforeAnyRemoteCall(boolean missingPrincipal) throws Exception {
        if (missingPrincipal) UserContext.removeUser();
        else when(groups.count(any(Wrapper.class))).thenReturn(0L);
        mvc.perform(request()).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        assertThat(resolves).hasValue(0);
        assertThat(recoveries).hasValue(0);
        assertThat(creates).hasValue(0);
    }

    private MockHttpServletRequestBuilder request() { return post(ROUTE).contentType("application/json").content(BODY); }

    private void assertNoCreation() {
        assertThat(resolves).hasValue(1);
        assertThat(recoveries).hasValue(1);
        assertThat(creates).hasValue(0);
    }
}
