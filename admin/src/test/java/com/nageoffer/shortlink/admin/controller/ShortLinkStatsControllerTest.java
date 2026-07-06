package com.nageoffer.shortlink.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.shortlink.admin.common.convention.result.Result;
import com.nageoffer.shortlink.admin.common.convention.result.Results;
import com.nageoffer.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.nageoffer.shortlink.admin.remote.dto.req.ShortLinkGroupStatsAccessRecordReqDTO;
import com.nageoffer.shortlink.admin.remote.dto.resp.ShortLinkStatsAccessRecordRespDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShortLinkStatsControllerTest {

    @Test
    void groupAccessRecordsForwardsPaginationToProjectApi() {
        ShortLinkActualRemoteService remoteService = mock(ShortLinkActualRemoteService.class);
        ShortLinkStatsController controller = new ShortLinkStatsController(remoteService);
        ShortLinkGroupStatsAccessRecordReqDTO request = new ShortLinkGroupStatsAccessRecordReqDTO();
        request.setGid("g1");
        request.setStartDate("2026-07-01");
        request.setEndDate("2026-07-07");
        request.setCurrent(2L);
        request.setSize(50L);
        Result<Page<ShortLinkStatsAccessRecordRespDTO>> expected = Results.success(new Page<>());

        when(remoteService.groupShortLinkStatsAccessRecord("g1", "2026-07-01", "2026-07-07", 2L, 50L))
                .thenReturn(expected);

        Result<Page<ShortLinkStatsAccessRecordRespDTO>> actual = controller.groupShortLinkStatsAccessRecord(request);

        assertThat(actual).isSameAs(expected);
        verify(remoteService).groupShortLinkStatsAccessRecord("g1", "2026-07-01", "2026-07-07", 2L, 50L);
    }
}
