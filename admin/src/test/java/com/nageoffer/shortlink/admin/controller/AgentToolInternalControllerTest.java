package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.common.biz.user.UserInfoDTO;
import com.nageoffer.shortlink.admin.common.convention.exception.ClientException;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.dao.entity.GroupDO;
import com.nageoffer.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsAccessRecordReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkStatsReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsAccessRecordRespDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsRespDTO;
import com.nageoffer.shortlink.admin.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolInternalControllerTest {

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    @Test
    void listGroupsReturnsGroupsForCurrentTrustedUser() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkGroupRespDTO group = new ShortLinkGroupRespDTO();
        group.setGid("g1");
        group.setName("campaign");
        when(groupService.listGroup()).thenReturn(List.of(group));

        Result<List<ShortLinkGroupRespDTO>> actual = controller.listGroups();

        assertThat(actual.isSuccess()).isTrue();
        assertThat(actual.getData()).containsExactly(group);
        verify(groupService).listGroup();
    }

    @Test
    void pageShortLinksForwardsRequestToProjectApi() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkPageReqDTO request = new ShortLinkPageReqDTO();
        request.setGid("g1");
        request.setOrderTag("todayPv");
        request.setCurrent(2L);
        request.setSize(50L);
        Result<Page<ShortLinkPageRespDTO>> expected = Results.success(new Page<>());
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.pageShortLink("g1", "todayPv", 2L, 50L)).thenReturn(expected);

        Result<Page<ShortLinkPageRespDTO>> actual = controller.pageShortLinks(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).pageShortLink("g1", "todayPv", 2L, 50L);
    }

    @Test
    void shortLinkStatsForwardsRequestToProjectApi() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkStatsReqDTO request = new ShortLinkStatsReqDTO();
        request.setFullShortUrl("nurl.ink/a");
        request.setGid("g1");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        Result<ShortLinkStatsRespDTO> expected = Results.success(new ShortLinkStatsRespDTO());
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.oneShortLinkStats("nurl.ink/a", "g1", "2026-07-01", "2026-07-07"))
                .thenReturn(expected);

        Result<ShortLinkStatsRespDTO> actual = controller.shortLinkStats(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).oneShortLinkStats("nurl.ink/a", "g1", "2026-07-01", "2026-07-07");
    }

    @Test
    void groupStatsForwardsRequestToProjectApi() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkGroupStatsReqDTO request = new ShortLinkGroupStatsReqDTO();
        request.setGid("g1");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        Result<ShortLinkStatsRespDTO> expected = Results.success(new ShortLinkStatsRespDTO());
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.groupShortLinkStats("g1", "2026-07-01", "2026-07-07")).thenReturn(expected);

        Result<ShortLinkStatsRespDTO> actual = controller.groupStats(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).groupShortLinkStats("g1", "2026-07-01", "2026-07-07");
    }

    @Test
    void groupAccessRecordsForwardsPaginationToProjectApi() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkGroupStatsAccessRecordReqDTO request = new ShortLinkGroupStatsAccessRecordReqDTO();
        request.setGid("g1");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        request.setCurrent(2L);
        request.setSize(50L);
        Result<Page<ShortLinkStatsAccessRecordRespDTO>> expected = Results.success(new Page<>());
        when(groupService.count(any(Wrapper.class))).thenReturn(1L);
        when(remoteService.groupShortLinkStatsAccessRecord("g1", "2026-07-01", "2026-07-07", 2L, 50L))
                .thenReturn(expected);

        Result<Page<ShortLinkStatsAccessRecordRespDTO>> actual = controller.groupAccessRecords(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).groupShortLinkStatsAccessRecord("g1", "2026-07-01", "2026-07-07", 2L, 50L);
    }

    @Test
    void pageShortLinksRejectsGidOutsideTrustedUserGroups() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkPageReqDTO request = new ShortLinkPageReqDTO();
        request.setGid("other-user-gid");
        request.setCurrent(1L);
        request.setSize(10L);
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "Zhang San"));
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> controller.pageShortLinks(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent tool request gid is not owned by current user");
        verify(remoteService, never()).pageShortLink(any(), any(), any(), any());
    }

    @Test
    void shortLinkStatsRejectsGidOutsideTrustedUserGroups() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkStatsReqDTO request = new ShortLinkStatsReqDTO();
        request.setFullShortUrl("nurl.ink/a");
        request.setGid("other-user-gid");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "Zhang San"));
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> controller.shortLinkStats(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent tool request gid is not owned by current user");
        verify(remoteService, never()).oneShortLinkStats(any(), any(), any(), any());
    }

    @Test
    void groupStatsRejectsGidOutsideTrustedUserGroups() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkGroupStatsReqDTO request = new ShortLinkGroupStatsReqDTO();
        request.setGid("other-user-gid");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "Zhang San"));
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> controller.groupStats(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent tool request gid is not owned by current user");
        verify(remoteService, never()).groupShortLinkStats(any(), any(), any());
    }

    @Test
    void groupAccessRecordsRejectsGidOutsideTrustedUserGroups() {
        GroupService groupService = mock(GroupService.class);
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        AgentToolInternalController controller = new AgentToolInternalController(groupService, remoteService);
        ShortLinkGroupStatsAccessRecordReqDTO request = new ShortLinkGroupStatsAccessRecordReqDTO();
        request.setGid("other-user-gid");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        request.setCurrent(1L);
        request.setSize(10L);
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "Zhang San"));
        when(groupService.count(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> controller.groupAccessRecords(request))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent tool request gid is not owned by current user");
        verify(remoteService, never()).groupShortLinkStatsAccessRecord(any(), any(), any(), any(), any());
    }
}
