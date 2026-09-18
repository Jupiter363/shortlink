package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.biz.user.*;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.dto.resp.ShortLinkGroupRespDTO;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.dto.req.*;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;
import com.jupiter.shortlink.admin.service.GroupService;

import org.junit.jupiter.api.*;

import java.util.*;

class AgentToolInternalControllerTest {
    private GroupService groups;
    private ShortLinkActualRemoteService links;
    private AgentAnalyticsFacade analytics;
    private AgentToolInternalController controller;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        links = mock(ShortLinkActualRemoteService.class);
        analytics = mock(AgentAnalyticsFacade.class);
        controller = new AgentToolInternalController(groups, links, analytics);
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "DB name", 7L));
        when(groups.count(any(Wrapper.class))).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    private StatsEnvelope envelope() {
        return new StatsEnvelope(
                Map.of("pv", 4_000_000_000L),
                List.of(),
                Map.of("snapshotId", "snapshot-1", "provisional", true, "completeness", "PARTIAL"));
    }

    private ShortLinkStatsReqDTO single() {
        var q = new ShortLinkStatsReqDTO();
        q.setGid("g1");
        q.setFullShortUrl("nurl.ink/abc123");
        q.setStartDate("2026-07-01");
        q.setEndDate("2026-07-07");
        return q;
    }

    private ShortLinkGroupStatsReqDTO group() {
        var q = new ShortLinkGroupStatsReqDTO();
        q.setGid("g1");
        q.setStartDate("2026-07-01");
        q.setEndDate("2026-07-07");
        return q;
    }

    private ShortLinkGroupStatsAccessRecordReqDTO access() {
        var q = new ShortLinkGroupStatsAccessRecordReqDTO();
        q.setGid("g1");
        q.setStartDate("2026-07-01");
        q.setEndDate("2026-07-07");
        q.setCurrent(2L);
        q.setSize(50L);
        return q;
    }

    private ShortLinkPageReqDTO page() {
        var q = new ShortLinkPageReqDTO();
        q.setGid("g1");
        q.setOrderTag("todayPv");
        q.setCurrent(2L);
        q.setSize(50L);
        return q;
    }

    @Test
    void listGroupsUsesCurrentTrustedPrincipal() {
        var group = new ShortLinkGroupRespDTO();
        group.setGid("g1");
        when(groups.listGroup()).thenReturn(List.of(group));
        assertThat(controller.listGroups().getData()).containsExactly(group);
        verify(groups).listGroup();
    }

    @Test
    void linkMetricsForwardsFrozenPaginationOnlyForAnOwnedGroup() {
        when(analytics.query("g1", null, "2026-09-01", "2026-09-07", null,
                "frozen", "next", 50, "LINK_METRICS")).thenReturn(envelope());
        assertThat(controller.linkMetrics("g1", "2026-09-01", "2026-09-07",
                "frozen", "next", 50).getData()).isEqualTo(envelope());
        verify(analytics).query("g1", null, "2026-09-01", "2026-09-07", null,
                "frozen", "next", 50, "LINK_METRICS");
        clearInvocations(analytics);
        when(groups.count(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> controller.linkMetrics("other", "2026-09-01", "2026-09-07",
                null, null, 500)).hasMessageContaining("owned");
        verifyNoInteractions(analytics);
    }

    @Test
    void dimensionBreakdownForwardsAuthorizedScopeAndExactFilters() {
        List<String> dimensions = List.of("province", "device");
        List<Map<String, Object>> filters = List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江")));
        var request = new AgentToolInternalController.DimensionRequest("g1", null,
                "2026-09-01", "2026-09-07", dimensions, filters, "snapshot", "cursor", 50);
        controller.dimensionBreakdown(request);
        verify(analytics).query("g1", null, "2026-09-01", "2026-09-07", null,
                "snapshot", "cursor", 50, "DIMENSION_BREAKDOWN", null, dimensions, filters);
        clearInvocations(analytics);
        when(groups.count(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> controller.dimensionBreakdown(request)).hasMessageContaining("owned");
        verifyNoInteractions(analytics);
    }

    @Test
    void everyEntryRejectsMissingCurrentPrincipalBeforeDelegation() {
        UserContext.removeUser();
        assertThatThrownBy(controller::listGroups)
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent request requires a current trusted principal");
        assertThatThrownBy(() -> controller.shortLinkStats(single()))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(links, analytics);
        verify(groups, never()).listGroup();
    }

    @Test
    void legacyIdentityWithoutAuthVersionIsRejected() {
        UserContext.setUser(new UserInfoDTO("1001", "zhangsan", "legacy"));
        assertThatThrownBy(() -> controller.pageShortLinks(page()))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(links, analytics);
    }

    @Test
    void pageShortLinksForwardsBoundedPageToCommand() {
        var expected = Results.success(new Page<ShortLinkPageRespDTO>());
        when(links.pageShortLink("g1", "todayPv", 2L, 50L)).thenReturn(expected);
        assertThat(controller.pageShortLinks(page())).isSameAs(expected);
        verify(links).pageShortLink("g1", "todayPv", 2L, 50L);
        verifyNoInteractions(analytics);
    }

    @Test
    void pageBudgetIsRejectedBeforeRemoteCall() {
        var q = page();
        q.setSize(501L);
        assertThatThrownBy(() -> controller.pageShortLinks(q))
                .isInstanceOf(ClientException.class)
                .hasMessage("Invalid link page budget");
        verifyNoInteractions(links);
    }

    @Test
    void singleStatsReuseEnvelopeAndPreserveQualityAndLongCounters() {
        var expected = envelope();
        when(analytics.query(
                        "g1",
                        "nurl.ink/abc123",
                        "2026-07-01",
                        "2026-07-07",
                        null,
                        null,
                        null,
                        500,
                        "METRICS"))
                .thenReturn(expected);
        var result = controller.shortLinkStats(single());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isSameAs(expected);
        assertThat(result.getData().metrics().get("pv")).isEqualTo(4_000_000_000L);
        assertThat(result.getData().metrics()).doesNotContainKey("uv");
        verifyNoInteractions(links);
    }

    @Test
    void groupStatsReuseAnalyticsInsteadOfProjectStatistics() {
        var expected = envelope();
        when(analytics.query(
                        "g1", null, "2026-07-01", "2026-07-07", null, null, null, 500, "METRICS"))
                .thenReturn(expected);
        assertThat(controller.groupStats(group()).getData()).isSameAs(expected);
        verifyNoInteractions(links);
    }

    @Test
    void accessContinuationForwardsSnapshotAndCursorTogether() {
        var expected = envelope();
        when(analytics.query(
                        "g1",
                        null,
                        "2026-07-01",
                        "2026-07-07",
                        null,
                        "snapshot-1",
                        "cursor-2",
                        50,
                        "ACCESS_RECORDS"))
                .thenReturn(expected);
        assertThat(controller.groupAccessRecords(access(), "snapshot-1", "cursor-2").getData())
                .isSameAs(expected);
        verifyNoInteractions(links);
    }

    @Test
    void accessContinuationCannotRestartSilentlyWithoutSnapshotOrCursor() {
        assertThatThrownBy(() -> controller.groupAccessRecords(access(), null, "cursor"))
                .isInstanceOf(ClientException.class)
                .hasMessage("Continuation requires snapshotId and cursor");
        assertThatThrownBy(() -> controller.groupAccessRecords(access(), "snapshot", null))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(analytics, links);
    }

    @Test
    void allGroupRoutesRejectForeignGroupBeforeAnyRemoteQuery() {
        when(groups.count(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> controller.pageShortLinks(page()))
                .isInstanceOf(ClientException.class)
                .hasMessage("Agent request requires an owned active group");
        assertThatThrownBy(() -> controller.shortLinkStats(single()))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> controller.groupStats(group()))
                .isInstanceOf(ClientException.class);
        assertThatThrownBy(() -> controller.groupAccessRecords(access(), "snapshot", "cursor"))
                .isInstanceOf(ClientException.class);
        verifyNoInteractions(links, analytics);
    }
}
