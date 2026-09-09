package com.jupiter.shortlink.admin.remote;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.analytics.LinkPageAnalyticsService;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

class LinkPageAnalyticsServiceTest {
    final ShortLinkActualRemoteService links = mock(ShortLinkActualRemoteService.class);
    final AgentAnalyticsFacade analytics = mock(AgentAnalyticsFacade.class);
    final LinkPageAnalyticsService service = new LinkPageAnalyticsService(links, analytics);

    @Test
    void rankingUsesWholeAuthorizedScopeAndPreservesLongMetricsAndSnapshot() {
        Page<ShortLinkPageRespDTO> page = new Page<>(1, 500, 3);
        page.setRecords(List.of(link(3), link(2), link(1)));
        when(links.pageShortLink("g", "createTime", 1L, 500L)).thenReturn(Results.success(page));
        when(analytics.query(
                        eq("g"),
                        isNull(),
                        anyString(),
                        anyString(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(500),
                        eq("METRICS"),
                        anyList()))
                .thenReturn(
                        new StatsEnvelope(
                                Map.of(),
                                List.of(
                                        metric(1, 3_000_000_000L),
                                        metric(2, 3_000_000_000L),
                                        metric(3, 1)),
                                Map.of("snapshotId", "fixed", "completeness", "COMPLETE")));
        var request = request("todayPv");
        request.setSize(1);
        request.setCurrent(2);
        var result = service.page(request).getData();
        assertThat(result.getRecords())
                .extracting(ShortLinkPageRespDTO::getLinkId)
                .containsExactly(2L);
        assertThat(result.getRecords().get(0).getTodayPv()).isEqualTo(3_000_000_000L);
        assertThat(result.getRecords().get(0).getTotalPv()).isNull();
        assertThat(((LinkPageAnalyticsService.AnalyticsPage) result).getStatsMeta())
                .containsEntry("snapshotId", "fixed");
    }

    @Test
    void unavailableStatisticsPreserveBusinessRowsAndDoNotInventZero() {
        Page<ShortLinkPageRespDTO> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(link(1)));
        when(links.pageShortLink("g", "createTime", 1L, 20L)).thenReturn(Results.success(page));
        when(analytics.query(
                        any(), any(), any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("fixture outage"));
        var result = service.page(request(null)).getData();
        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getTodayPv()).isNull();
        assertThat(((LinkPageAnalyticsService.AnalyticsPage) result).getStatsMeta())
                .containsEntry("availability", "UNAVAILABLE");
    }

    @Test
    void neverRanksOnlyTheFirstPageOfAnOversizedGroup() {
        Page<ShortLinkPageRespDTO> page = new Page<>(1, 500, 501);
        when(links.pageShortLink("g", "createTime", 1L, 500L)).thenReturn(Results.success(page));
        assertThatThrownBy(() -> service.page(request("todayPv")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TOO_LARGE");
        verifyNoInteractions(analytics);
    }

    @Test
    void lifetimeExceedingScanBudgetRequiresExplicitAsyncQuery() {
        Page<ShortLinkPageRespDTO> page = new Page<>(1, 500, 1);
        var row = link(1);
        row.setCreateTime(Date.from(Instant.now().minusSeconds(8 * 86400L)));
        page.setRecords(List.of(row));
        when(links.pageShortLink("g", "createTime", 1L, 500L)).thenReturn(Results.success(page));
        assertThatThrownBy(() -> service.page(request("totalPv")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ASYNC_REQUIRED");
        verifyNoInteractions(analytics);
    }

    static ShortLinkPageReqDTO request(String order) {
        var r = new ShortLinkPageReqDTO();
        r.setGid("g");
        r.setOrderTag(order);
        r.setSize(20);
        r.setCurrent(1);
        return r;
    }

    static ShortLinkPageRespDTO link(long id) {
        var r = new ShortLinkPageRespDTO();
        r.setLinkId(id);
        r.setCreateTime(Date.from(Instant.now().minusSeconds(3600)));
        return r;
    }

    static Map<String, Object> metric(long id, long pv) {
        return Map.of("linkId", id, "pv", pv, "uv", 1L, "uip", 1L);
    }
}
