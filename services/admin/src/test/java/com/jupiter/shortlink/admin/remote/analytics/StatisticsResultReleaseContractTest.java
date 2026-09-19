package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.controller.AgentToolInternalController;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.service.GroupService;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** In-process HTTP authority fixture: no real statistics service or query execution. */
class StatisticsResultReleaseContractTest {
    private static final String ADMIN = "/internal/short-link-admin/v1/agent-tools/statistics/";
    private static final String JOBS = "/internal/analytics/v1/jobs";
    private static final String SELECTED = "/internal/command/authorization/resolve-selected";
    private static final String RESOLVE = "/internal/command/authorization/resolve";
    private static final String RELEASE = JOBS + "/job-1/release-result";
    private static final String VERSION = "a".repeat(64);
    private static final long EXPIRES = 2_000_000_000_000L;
    private static final String HASH = FrozenQueryScope.memberHash(List.of(99L));
    private static final FrozenQueryScope SCOPE = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET",
            "scope-1", HASH, 1, "b".repeat(64), FrozenQueryScope.shardIdFor("scope-1", 0, HASH), 0, 1, HASH, List.of(99L));
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger unexpected = new AtomicInteger();
    private final AtomicReference<Reply> jobReply = new AtomicReference<>();
    private final AtomicReference<Reply> releaseReply = new AtomicReference<>();
    private HttpServer server;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
        jobReply.set(success(statusData("SUCCEEDED", "AVAILABLE")));
        releaseReply.set(success(statusData("SUCCEEDED", "RELEASED")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            JSONObject body = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(new Call(path, body, exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-username"),
                    exchange.getRequestHeaders().getFirst("x-shortlink-auth-version")));
            Reply reply;
            if (SELECTED.equals(path) || RESOLVE.equals(path)) reply = new Reply(200, JSON.toJSONString(selected()));
            else if (RELEASE.equals(path)) reply = releaseReply.get();
            else if (JOBS.equals(path) || (JOBS + "/frozen").equals(path) || (JOBS + "/job-1/status").equals(path))
                reply = jobReply.get();
            else { unexpected.incrementAndGet(); reply = new Reply(404, "unexpected path"); }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var groups = mock(GroupService.class);
        when(groups.count(any())).thenReturn(1L);
        var facade = new AgentAnalyticsFacade(new AnalyticsJsonClient(base, base, "test-internal-service-token-length-valid"));
        mvc = MockMvcBuilders.standaloneSetup(new AgentToolInternalController(groups,
                        mock(ShortLinkActualRemoteService.class), facade))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
        if (server != null) server.stop(0);
        assertThat(unexpected).as("no retry/fallback endpoint").hasValue(0);
    }

    @Test
    void releaseUsesOriginalFrozenQueryAndRequiresExactTerminalReceiptWithoutFallback() throws Exception {
        send("frozen-jobs", true).andExpect(jsonPath("$.data.resultReady").value(true));
        send("frozen-jobs/job-1/release-result", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value("job-1"))
                .andExpect(jsonPath("$.data.resultState").value("RELEASED"))
                .andExpect(jsonPath("$.data.resultReady").value(false))
                .andExpect(jsonPath("$.data.resultCode").value("RESULT_RELEASED"))
                .andExpect(jsonPath("$.data.expiresAt").value(EXPIRES));
        assertThat(calls).extracting(Call::path).containsExactly(SELECTED, JOBS + "/frozen", SELECTED, RELEASE);
        assertThat(calls.get(3).body()).isEqualTo(calls.get(1).body());
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.tenant()).isEqualTo("1001"); assertThat(call.subject()).isEqualTo("alice");
            assertThat(call.authVersion()).isEqualTo("7");
        });
        releaseReply.set(new Reply(404, "PRIVATE downstream error"));
        send("frozen-jobs/job-1/release-result", true)
                .andExpect(jsonPath("$.code").value("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Statistics result release unavailable"));
        assertThat(calls).extracting(Call::path).endsWith(SELECTED, RELEASE);
        var wrong = statusData("SUCCEEDED", "RELEASED"); wrong.put("jobId", "other-job");
        releaseReply.set(success(wrong));
        send("frozen-jobs/job-1/release-result", true)
                .andExpect(jsonPath("$.code").value("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE"));
        var invalidExpiry = statusData("SUCCEEDED", "RELEASED"); invalidExpiry.put("expiresAt", "2000000000000");
        releaseReply.set(success(invalidExpiry));
        send("frozen-jobs/job-1/release-result", true)
                .andExpect(jsonPath("$.code").value("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE"));
    }

    @Test
    void allThreeStatusConsumersKeepReleasedUnavailableAndLegacyReadyTruthful() throws Exception {
        jobReply.set(success(statusData("SUCCEEDED", "RELEASED")));
        for (boolean frozen : List.of(false, true))
            send(frozen ? "frozen-jobs" : "jobs", frozen)
                    .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.data.resultState").value("RELEASED"))
                    .andExpect(jsonPath("$.data.resultReady").value(false))
                    .andExpect(jsonPath("$.data.resultCode").value("RESULT_RELEASED"));
        mvc.perform(get(ADMIN + "jobs/job-1")).andExpect(jsonPath("$.data.resultReady").value(false))
                .andExpect(jsonPath("$.data.resultCode").value("RESULT_RELEASED"));
        jobReply.set(success(statusData("FAILED", "UNAVAILABLE")));
        mvc.perform(get(ADMIN + "jobs/job-1")).andExpect(jsonPath("$.data.resultReady").value(false));
        jobReply.set(success(Map.of("jobId", "job-1", "state", "SUCCEEDED")));
        send("jobs", false).andExpect(jsonPath("$.data.resultReady").value(true));
        send("frozen-jobs", true).andExpect(jsonPath("$.data.resultReady").value(true));
        mvc.perform(get(ADMIN + "jobs/job-1")).andExpect(jsonPath("$.data.resultReady").value(true));
        var contradictory = statusData("SUCCEEDED", "RELEASED"); contradictory.put("resultReady", true);
        jobReply.set(success(contradictory));
        mvc.perform(get(ADMIN + "jobs/job-1"))
                .andExpect(jsonPath("$.code").value("STATISTICS_READ_PROTOCOL_UNAVAILABLE"));
    }

    @Test
    void capacityKeepsOnlyTypedNotAdmittedProofAndSafeFieldsThroughTheController() throws Exception {
        for (String kind : List.of("ACTIVE_EXECUTION", "RESULT_STORAGE", "RECOVERY_IDENTITY")) {
            jobReply.set(new Reply(200, JSON.toJSONString(Map.of("code", "QUERY_CAPACITY_EXHAUSTED",
                    "message", "PRIVATE quota diagnostic", "admitted", false, "capacityKind", kind, "privateToken", "secret"))));
            String body = send("frozen-jobs", true).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("QUERY_CAPACITY_EXHAUSTED"))
                    .andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.capacityKind").value(kind))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JSON.parseObject(body)).containsOnlyKeys("code", "message", "admitted", "capacityKind")
                    .containsEntry("message", "Statistics query capacity exhausted");
        }
        send("jobs", false).andExpect(jsonPath("$.code").value("QUERY_CAPACITY_EXHAUSTED"))
                .andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.capacityKind").value("RECOVERY_IDENTITY"));
        jobReply.set(new Reply(200, JSON.toJSONString(Map.of("code", "QUERY_CAPACITY_EXHAUSTED",
                "admitted", "false", "capacityKind", "RESULT_STORAGE"))));
        send("frozen-jobs", true).andExpect(jsonPath("$.code").value("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE"))
                .andExpect(jsonPath("$.admitted").doesNotExist());
        jobReply.set(new Reply(503, JSON.toJSONString(Map.of("code", "QUERY_CAPACITY_EXHAUSTED",
                "admitted", false, "capacityKind", "RESULT_STORAGE"))));
        send("frozen-jobs", true).andExpect(jsonPath("$.code").value("REMOTE_UNAVAILABLE"))
                .andExpect(jsonPath("$.admitted").doesNotExist());
    }

    private ResultActions send(String suffix, boolean frozen) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", "original-request"); request.put("gid", "g1");
        request.put("startDate", "2026-09-01"); request.put("endDate", "2026-09-02");
        request.put("queryKind", "METRICS");
        if (frozen) request.put("scope", SCOPE.asMap());
        return mvc.perform(post(ADMIN + suffix).contentType("application/json").content(JSON.toJSONString(request)));
    }

    private static Map<String, Object> statusData(String state, String lifecycle) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", "job-1"); data.put("state", state); data.put("rowCount", 3L);
        data.put("byteCount", 100L); data.put("pageCount", 1); data.put("expiresAt", EXPIRES);
        data.put("resultState", lifecycle); data.put("resultReady", "AVAILABLE".equals(lifecycle));
        data.put("resultCode", "RELEASED".equals(lifecycle) ? "RESULT_RELEASED" : null);
        return data;
    }

    private static Map<String, Object> selected() {
        return Map.of("schemaVersion", "selected-scope/v1", "allowed", true, "tenantId", "1001", "subjectId", "alice",
                "authVersion", 7L, "gid", "g1", "linkIds", List.of(99L), "memberHash", HASH, "ownershipVersion", VERSION,
                "links", List.of(Map.of("linkId", 99L, "gid", "g1", "domain", "example.test", "shortUri", "123456789",
                        "fullShortUrl", "https://example.test/123456789", "ownershipVersion", 1L)));
    }
    private static Reply success(Object data) { return new Reply(200, JSON.toJSONString(Map.of("code", "0", "data", data))); }
    private record Reply(int status, String body) {}
    private record Call(String path, JSONObject body, String tenant, String subject, String authVersion) {}
}
