package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import org.junit.jupiter.api.*;
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
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AgentToolInternalController(groups, links, analytics))
                        .addFilters(
                                new AgentInternalToolApiFilter(config, accounts, "system_agent"))
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
