package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.jupiter.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.jupiter.shortlink.admin.service.GroupService;
import com.jupiter.shortlink.contract.FrozenQueryScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentToolInternalMvcTest {
    private static final String TOKEN = "test-internal-token-at-least-24-characters";
    private GroupService groups;
    private ShortLinkActualRemoteService links;
    private AgentAnalyticsFacade analytics;
    private UserMapper accounts;
    private UserDO account;
    private MockMvc mvc;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        links = mock(ShortLinkActualRemoteService.class);
        analytics = mock(AgentAnalyticsFacade.class);
        accounts = mock(UserMapper.class);
        account = new UserDO();
        account.setId(1001L);
        account.setUsername("zhangsan");
        account.setAuthVersion(7L);
        account.setDelFlag(0);
        account.setDisabled(false);
        account.setRealName("authoritative name");
        when(accounts.selectOne(any(Wrapper.class))).thenReturn(account);
        var config = new AgentAdminConfiguration();
        config.setInternalToken(TOKEN);
        // Spring Boot discovers creator parameter names; bare ObjectMapper tests miss record factory conflicts.
        json = Jackson2ObjectMapperBuilder.json()
                .modulesToInstall(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES)).build();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AgentToolInternalController(groups, links, analytics))
                        .addFilters(
                                new AgentInternalToolApiFilter(config, accounts, "system_agent"))
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return get("/internal/short-link-admin/v1/agent-tools/short-links/page")
                .header("X-Agent-Internal-Token", TOKEN)
                .header("X-Agent-Username", "zhangsan")
                .header("X-Agent-UserId", "1001")
                .header("X-Agent-Auth-Version", "7")
                .param("gid", "g1");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder currentPrincipalRequest() {
        return get("/internal/short-link-admin/v1/agent-tools/authorization/current-principal")
                .header("X-Agent-Internal-Token", TOKEN)
                .header("X-Agent-Username", "zhangsan")
                .header("X-Agent-UserId", "1001")
                .header("X-Agent-Auth-Version", "7");
    }

    @Test
    void currentPrincipalReturnsOnlyDatabaseVerifiedIdentity() throws Exception {
        mvc.perform(currentPrincipalRequest().header("X-Agent-RealName", "forged name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.tenantId").value("1001"))
                .andExpect(jsonPath("$.data.username").value("zhangsan"))
                .andExpect(jsonPath("$.data.authVersion").value(7));
        verify(accounts).selectOne(any(Wrapper.class));
        verifyNoInteractions(groups, links, analytics);
    }

    @Test
    void currentPrincipalRejectsExpiredDelegationAndSystemMode() throws Exception {
        account.setAuthVersion(8L);
        mvc.perform(currentPrincipalRequest())
                .andExpect(status().isUnauthorized());
        account.setUsername("system_agent");
        mvc.perform(get("/internal/short-link-admin/v1/agent-tools/authorization/current-principal")
                        .header("X-Agent-Internal-Token", TOKEN)
                        .header("X-Agent-Username", "system_agent")
                        .header("X-Agent-Principal-Mode", "SYSTEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"));
        verifyNoInteractions(groups, links, analytics);
    }

    @Test
    void trustedContextIncludesCurrentDbVersionAndMvcPagination() throws Exception {
        when(groups.count(any(Wrapper.class)))
                .thenAnswer(
                        i -> {
                            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
                            assertThat(UserContext.getUserId()).isEqualTo("1001");
                            assertThat(UserContext.getAuthVersion()).isEqualTo(7L);
                            assertThat(UserContext.getRealName()).isEqualTo("authoritative name");
                            return 1L;
                        });
        when(links.pageShortLink("g1", "todayPv", 2L, 50L))
                .thenReturn(Results.success(new Page<ShortLinkPageRespDTO>()));
        mvc.perform(
                        request()
                                .header("X-Agent-RealName", "forged name")
                                .param("orderTag", "todayPv")
                                .param("current", "2")
                                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        verify(links).pageShortLink("g1", "todayPv", 2L, 50L);
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getAuthVersion()).isNull();
    }

    @Test
    void missingTokenStopsBeforeControllerAndAccountQuery() throws Exception {
        mvc.perform(
                        get("/internal/short-link-admin/v1/agent-tools/short-links/page")
                                .header("X-Agent-Username", "zhangsan")
                                .param("gid", "g1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid internal token"));
        verifyNoInteractions(accounts, groups, links, analytics);
    }

    @Test
    void passwordVersionChangeInvalidatesDelegationBeforeController() throws Exception {
        account.setAuthVersion(8L);
        mvc.perform(request().param("current", "1").param("size", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Delegated account session has expired"));
        verifyNoInteractions(groups, links, analytics);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void recoveryRouteCannotBypassCurrentAccountDelegationChecks() throws Exception {
        account.setAuthVersion(8L);
        mvc.perform(post("/internal/short-link-admin/v1/agent-tools/statistics/jobs/recover-existing")
                        .header("X-Agent-Internal-Token", TOKEN).header("X-Agent-Username", "zhangsan")
                        .header("X-Agent-UserId", "1001").header("X-Agent-Auth-Version", "7")
                        .contentType("application/json").content("{\"requestId\":\"frozen-request\",\"gid\":\"g1\","
                                + "\"startDate\":\"2026-07-01\",\"endDate\":\"2026-08-01\",\"queryKind\":\"METRICS\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Delegated account session has expired"));
        verifyNoInteractions(groups, links, analytics);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void frozenSubmissionAndRecoveryDeserializeWithProductionParameterNamesAndKeepExactIdentity() throws Exception {
        FrozenQueryScope scope = frozenScope();
        when(analytics.submitFrozenJob("frozen-request", "g1", null, "2026-09-13", "2026-09-13",
                "LINK_METRICS", List.of(), List.of(), scope)).thenReturn(Map.of("status", "PENDING", "jobId", "job-1"));
        when(analytics.recoverFrozenJob("frozen-request", "g1", null, "2026-09-13", "2026-09-13",
                "LINK_METRICS", List.of(), List.of(), scope)).thenReturn(Map.of("status", "PENDING", "jobId", "job-1"));
        String body = json.writeValueAsString(frozenRequest());
        for (String route : List.of("frozen-jobs", "frozen-jobs/recover-existing")) {
            mvc.perform(statisticsPost(route).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                    .andExpect(jsonPath("$.data.jobId").value("job-1"));
        }
        verify(analytics).submitFrozenJob("frozen-request", "g1", null, "2026-09-13", "2026-09-13",
                "LINK_METRICS", List.of(), List.of(), scope);
        verify(analytics).recoverFrozenJob("frozen-request", "g1", null, "2026-09-13", "2026-09-13",
                "LINK_METRICS", List.of(), List.of(), scope);
        verifyNoMoreInteractions(analytics);

        when(analytics.authorizeSelectedScope("g1", List.of(99L), null)).thenReturn(Map.of("allowed", true));
        mvc.perform(statisticsPost("authorize-scope").content("{\"gid\":\"g1\",\"linkIds\":[99]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed").value(true));
        verify(analytics).authorizeSelectedScope("g1", List.of(99L), null);
        verifyNoInteractions(groups, links);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void frozenSubmissionAndRecoveryRejectUnknownFieldsAndCoercionBeforeDelegation() throws Exception {
        var unknown = frozenRequest(); unknown.put("values", Map.of("requestId", "replacement-request"));
        var nested = frozenRequest();
        var scope = new LinkedHashMap<>(frozenScope().asMap()); scope.put("owner", "untrusted"); nested.put("scope", scope);
        var coerced = frozenRequest(); coerced.put("requestId", 99);
        var fractional = frozenRequest();
        var fractionalScope = new LinkedHashMap<>(frozenScope().asMap());
        fractionalScope.put("linkIds", List.of(99.5)); fractional.put("scope", fractionalScope);
        for (String route : List.of("frozen-jobs", "frozen-jobs/recover-existing")) {
            for (var body : List.of(unknown, nested, coerced, fractional)) {
                mvc.perform(statisticsPost(route).content(json.writeValueAsString(body)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
            }
        }
        for (String body : List.of("{\"gid\":\"g1\",\"linkIds\":[99],\"values\":{}}",
                "{\"gid\":\"g1\",\"linkIds\":[99.5]}")) {
            mvc.perform(statisticsPost("authorize-scope").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("HTTP_400"));
        }
        verifyNoInteractions(groups, links, analytics);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder statisticsPost(String route) {
        return post("/internal/short-link-admin/v1/agent-tools/statistics/" + route)
                .header("X-Agent-Internal-Token", TOKEN).header("X-Agent-Username", "zhangsan")
                .header("X-Agent-UserId", "1001").header("X-Agent-Auth-Version", "7")
                .contentType("application/json");
    }

    private static FrozenQueryScope frozenScope() {
        String memberHash = FrozenQueryScope.memberHash(List.of(99L));
        return new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-1", memberHash,
                1, "b".repeat(64), FrozenQueryScope.shardIdFor("scope-1", 0, memberHash),
                0, 1, memberHash, List.of(99L));
    }

    private static Map<String, Object> frozenRequest() {
        var request = new LinkedHashMap<String, Object>();
        request.put("requestId", "frozen-request"); request.put("gid", "g1");
        request.put("startDate", "2026-09-13"); request.put("endDate", "2026-09-13");
        request.put("queryKind", "LINK_METRICS"); request.put("dimensions", List.of()); request.put("filters", List.of());
        request.put("scope", frozenScope().asMap());
        return request;
    }

    @Test
    void oversizedPageRemainsBusinessErrorAndNeverCallsRemote() throws Exception {
        when(groups.count(any(Wrapper.class))).thenReturn(1L);
        mvc.perform(request().param("current", "1").param("size", "501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"))
                .andExpect(jsonPath("$.message").value("Invalid link page budget"));
        verifyNoInteractions(links, analytics);
        assertThat(UserContext.getUsername()).isNull();
    }
}
