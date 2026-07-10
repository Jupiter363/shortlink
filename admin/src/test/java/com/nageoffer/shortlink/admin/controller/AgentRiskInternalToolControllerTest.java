package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsBrowserRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsDeviceRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsLocaleCNRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsTopIpRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRiskInternalToolControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void activeShortLinksUseInternalFilterTrustedContextAndReturnOwnedActiveLinksOnly() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.listGroup()).thenAnswer(invocation -> {
            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
            return List.of(group("g1"), group("g2"));
        });
        when(remoteService.pageShortLink("g1", "totalPv", 1L, 500L))
                .thenReturn(Results.success(page(link("g1", "nurl.ink", "abc123", 42, 120))));
        when(remoteService.pageShortLink("g2", "totalPv", 1L, 500L))
                .thenReturn(Results.success(page(link("g2", "nurl.ink", "idle", 0, 0))));
        when(remoteService.oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-03 00:00:00", "2026-07-10 00:00:00"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder().pv(120).uv(80).uip(60).build()));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        MvcResult result = mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/active-short-links")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("since", "2026-07-03T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].gid").value("g1"))
                .andExpect(jsonPath("$.data[0].domain").value("nurl.ink"))
                .andExpect(jsonPath("$.data[0].shortUri").value("abc123"))
                .andExpect(jsonPath("$.data[0].pv").value(120))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response)
                .doesNotContain("\"ip\":")
                .doesNotContain("\"user\":")
                .doesNotContain("visitor");
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void activeShortLinksScanAllPagesForEachOwnedGroup() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.listGroup()).thenReturn(List.of(group("g1")));
        when(remoteService.pageShortLink("g1", "totalPv", 1L, 500L))
                .thenReturn(Results.success(page(1L, 500L, 501L, inactiveLinks(500))));
        when(remoteService.pageShortLink("g1", "totalPv", 2L, 500L))
                .thenReturn(Results.success(page(2L, 500L, 501L, List.of(link("g1", "nurl.ink", "late", 6, 6)))));
        when(remoteService.oneShortLinkStats("nurl.ink/late", "g1", "2026-07-03 00:00:00", "2026-07-10 00:00:00"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder().pv(6).uv(5).uip(4).build()));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/active-short-links")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("since", "2026-07-03T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].shortUri").value("late"))
                .andExpect(jsonPath("$.data.length()").value(1));

        verify(remoteService).pageShortLink("g1", "totalPv", 1L, 500L);
        verify(remoteService).pageShortLink("g1", "totalPv", 2L, 500L);
    }

    @Test
    void activeShortLinksReturnsFailureWhenProjectPageQueryFails() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.listGroup()).thenReturn(List.of(group("g1")));
        when(remoteService.pageShortLink("g1", "totalPv", 1L, 500L))
                .thenReturn(failure("B000001", "project page query failed"));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/active-short-links")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("since", "2026-07-03T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shortLinkWindowStatsRejectsGidOutsideTrustedUserGroups() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "other-gid")
                        .param("fullShortUrl", "nurl.ink/abc123")
                        .param("startTime", "2026-07-10T00:00:00")
                        .param("endTime", "2026-07-10T02:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    @Test
    void shortLinkWindowStatsReturnsSanitizedAggregatesWithoutRawIpOrUserRows() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder()
                        .pv(30)
                        .uv(24)
                        .uip(20)
                        .topIpStats(List.of(new ShortLinkStatsTopIpRespDTO(15, "source-token-1")))
                        .localeCnStats(List.of(new ShortLinkStatsLocaleCNRespDTO(18, "Shanghai", 0.60)))
                        .deviceStats(List.of(new ShortLinkStatsDeviceRespDTO(24, "Mobile", 0.80)))
                        .browserStats(List.of(new ShortLinkStatsBrowserRespDTO(23, "Chrome", 0.75)))
                        .hourStats(List.of(10, 20, 30, 20, 10))
                        .build()));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        MvcResult result = mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1")
                        .param("fullShortUrl", "nurl.ink/abc123")
                        .param("startTime", "2026-07-10T00:00:00")
                        .param("endTime", "2026-07-10T02:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pv").value(30))
                .andExpect(jsonPath("$.data.uv").value(24))
                .andExpect(jsonPath("$.data.topIpShare").value(0.5))
                .andExpect(jsonPath("$.data.topRegionShare").value(0.6))
                .andExpect(jsonPath("$.data.topDeviceShare").value(0.8))
                .andExpect(jsonPath("$.data.topBrowserShare").value(0.75))
                .andExpect(jsonPath("$.data.peakHourShare").value(0.6666666666666666))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertThat(response)
                .doesNotContain("source-token-1")
                .doesNotContain("\"ip\":")
                .doesNotContain("\"user\":")
                .doesNotContain("accessRecords")
                .doesNotContain("visitor");
        verify(remoteService).oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00");
    }

    @Test
    void shortLinkWindowStatsConvertsUtcInstantsToBusinessTimezoneBeforeCallingProjectStats() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00"))
                .thenReturn(Results.success(ShortLinkStatsRespDTO.builder().pv(30).uv(24).uip(20).build()));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1")
                        .param("fullShortUrl", "nurl.ink/abc123")
                        .param("startTime", "2026-07-09T16:00:00Z")
                        .param("endTime", "2026-07-09T18:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.pv").value(30));

        verify(remoteService).oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00");
    }

    @Test
    void shortLinkWindowStatsReturnsFailureWhenProjectBusinessResultFails() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00"))
                .thenReturn(failure("B000001", "project stats query failed"));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1")
                        .param("fullShortUrl", "nurl.ink/abc123")
                        .param("startTime", "2026-07-10T00:00:00")
                        .param("endTime", "2026-07-10T02:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shortLinkWindowStatsReturnsFailureWhenProjectDataIsMissing() throws Exception {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.oneShortLinkStats("nurl.ink/abc123", "g1", "2026-07-10 00:00:00", "2026-07-10 02:00:00"))
                .thenReturn(Results.success(null));
        MockMvc mockMvc = mockMvc(groupService, remoteService);

        mockMvc.perform(get("/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats")
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "zhangsan")
                        .param("gid", "g1")
                        .param("fullShortUrl", "nurl.ink/abc123")
                        .param("startTime", "2026-07-10T00:00:00")
                        .param("endTime", "2026-07-10T02:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private MockMvc mockMvc(GroupService groupService, ShortLinkActualRemoteService remoteService) {
        AgentAdminConfiguration configuration = new AgentAdminConfiguration();
        configuration.setInternalToken("internal-token");
        return MockMvcBuilders
                .standaloneSetup(new AgentToolInternalController(groupService, remoteService))
                .addFilters(new AgentInternalToolApiFilter(configuration))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ShortLinkGroupRespDTO group(String gid) {
        ShortLinkGroupRespDTO group = new ShortLinkGroupRespDTO();
        group.setGid(gid);
        group.setName(gid + "-name");
        return group;
    }

    private Page<ShortLinkPageRespDTO> page(ShortLinkPageRespDTO link) {
        return page(1L, 500L, 1L, List.of(link));
    }

    private Page<ShortLinkPageRespDTO> page(long current, long size, long total, List<ShortLinkPageRespDTO> links) {
        Page<ShortLinkPageRespDTO> page = new Page<>(current, size);
        page.setRecords(links);
        page.setTotal(total);
        return page;
    }

    private List<ShortLinkPageRespDTO> inactiveLinks(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> link("g1", "nurl.ink", "idle-" + index, 0, 0))
                .toList();
    }

    private ShortLinkPageRespDTO link(String gid, String domain, String shortUri, int todayPv, int totalPv) {
        ShortLinkPageRespDTO link = new ShortLinkPageRespDTO();
        link.setGid(gid);
        link.setDomain(domain);
        link.setShortUri(shortUri);
        link.setFullShortUrl(domain + "/" + shortUri);
        link.setTodayPv(todayPv);
        link.setTotalPv(totalPv);
        return link;
    }

    private <T> Result<T> failure(String code, String message) {
        return new Result<T>()
                .setCode(code)
                .setMessage(message);
    }
}
