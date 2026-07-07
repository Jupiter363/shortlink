package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentToolInternalMvcTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void pageShortLinksUsesInternalFilterTrustedContextAndMvcParameterBinding() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
            return 1L;
        });
        when(remoteService.pageShortLink("g1", "todayPv", 2L, 50L))
                .thenReturn(Results.success(new Page<ShortLinkPageRespDTO>()));
        MockMvc mockMvc = mockMvc(groupService, remoteService, "internal-token", false);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/short-links/page")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1")
                        .param("orderTag", "todayPv")
                        .param("current", "2")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        verify(remoteService).pageShortLink("g1", "todayPv", 2L, 50L);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void internalToolRouteRejectsMissingTokenBeforeControllerRuns() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        MockMvc mockMvc = mockMvc(groupService, remoteService, "internal-token", false);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/short-links/page")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid internal token"));
    }

    private MockMvc mockMvc(
            GroupService groupService,
            ShortLinkActualRemoteService remoteService,
            String internalToken,
            boolean devMode) {
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken(internalToken);
        configuration.setInternalTokenDevMode(devMode);
        return MockMvcBuilders
                .standaloneSetup(new AgentToolInternalController(groupService, remoteService))
                .addFilters(new AgentInternalToolApiFilter(configuration))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
