package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jupiter.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.web.GlobalExceptionHandler;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.analytics.*;
import com.jupiter.shortlink.admin.service.GroupService;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

/**
 * Real AgentAnalyticsFacade participates: authority scope, quality, time bounds and pagination must
 * survive MVC.
 */
class AgentRiskInternalToolControllerTest {
    private static final String TOKEN = "test-internal-token-at-least-24-characters";
    private static final long LINK = 3_000_000_001L;
    private GroupService groups;
    private ShortLinkActualRemoteService legacy;
    private AnalyticsJsonClient client;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        groups = mock(GroupService.class);
        legacy = mock(ShortLinkActualRemoteService.class);
        client = mock(AnalyticsJsonClient.class);
        when(groups.count(any(Wrapper.class))).thenReturn(1L);
        var accounts = mock(UserMapper.class);
        var user = new UserDO();
        user.setId(1001L);
        user.setUsername("zhangsan");
        user.setAuthVersion(7L);
        user.setDisabled(false);
        user.setDelFlag(0);
        when(accounts.selectOne(any(Wrapper.class))).thenReturn(user);
        when(client.resolve(any())).thenReturn(authority());
        when(client.query(any()))
                .thenReturn(
                        envelope(
                                List.of(Map.of("linkId", LINK, "pv", 4_000_000_000L)),
                                Map.of("pv", 4_000_000_000L),
                                Map.of(
                                        "snapshotId",
                                        "snapshot-1",
                                        "completeness",
                                        "COMPLETE",
                                        "provisional",
                                        true)));
        var config = new AgentAdminConfiguration();
        config.setInternalToken(TOKEN);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new AgentToolInternalController(
                                        groups, legacy, new AgentAnalyticsFacade(client)))
                        .addFilters(
                                new AgentInternalToolApiFilter(config, accounts, "system_agent"))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
    }

    private JSONObject authority() {
        return new JSONObject(
                Map.of(
                        "tenantId",
                        "1001",
                        "links",
                        List.of(
                                Map.of(
                                        "linkId",
                                        LINK,
                                        "gid",
                                        "g1",
                                        "domain",
                                        "nurl.ink",
                                        "shortUri",
                                        "abc123",
                                        "fullShortUrl",
                                        "nurl.ink/abc123"))));
    }

    private JSONObject envelope(
            List<Map<String, Object>> items,
            Map<String, Object> metrics,
            Map<String, Object> meta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("metrics", metrics);
        data.put("meta", meta);
        return new JSONObject(Map.of("code", "0", "data", data));
    }

    private MockHttpServletRequestBuilder request(String suffix) {
        return get("/internal/short-link-admin/v1/agent-tools/" + suffix)
                .header("X-Agent-Internal-Token", TOKEN)
                .header("X-Agent-Username", "zhangsan")
                .header("X-Agent-UserId", "1001")
                .header("X-Agent-Auth-Version", "7");
    }

    private MockHttpServletRequestBuilder window() {
        return request("risk/short-link-window-stats")
                .param("gid", "g1")
                .param("fullShortUrl", "nurl.ink/abc123")
                .param("startTime", "2026-07-09T16:00:00Z")
                .param("endTime", "2026-07-09T18:00:00Z");
    }

    @Test
    void activeLinksUseAuthorizedAnalyticsEnvelopeAndLongCounts() throws Exception {
        String body =
                mvc.perform(
                                request("risk/active-short-links")
                                        .param("since", "2026-07-03T00:00:00Z")
                                        .param("endTime", "2026-07-10T00:00:00Z"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("0"))
                        .andExpect(jsonPath("$.data.items[0].gid").value("g1"))
                        .andExpect(jsonPath("$.data.items[0].linkId").value(LINK))
                        .andExpect(jsonPath("$.data.metrics.pv").value(4_000_000_000L))
                        .andExpect(jsonPath("$.data.metrics.uv").doesNotExist())
                        .andExpect(jsonPath("$.data.meta.tenantId").value("1001"))
                        .andExpect(jsonPath("$.data.meta.provisional").value(true))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).doesNotContain("\"ip\":", "\"user\":", "visitor");
        verifyNoInteractions(legacy);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void continuationUsesSnapshotCursorAndNeverScansLegacyLinkPages() throws Exception {
        mvc.perform(
                        request("risk/active-short-links")
                                .param("since", "2026-07-03T00:00:00Z")
                                .param("endTime", "2026-07-10T00:00:00Z")
                                .param("snapshotId", "snapshot-1")
                                .param("cursor", "next-2")
                                .param("pageSize", "50"))
                .andExpect(jsonPath("$.code").value("0"));
        var captured = ArgumentCaptor.forClass(AnalyticsQueryRequest.class);
        verify(client).query(captured.capture());
        assertThat(captured.getValue().snapshotId()).isEqualTo("snapshot-1");
        assertThat(captured.getValue().cursor()).isEqualTo("next-2");
        assertThat(captured.getValue().pageSize()).isEqualTo(50);
        assertThat(captured.getValue().linkIds()).containsExactly(LINK);
        assertThat(captured.getValue().endPolicy()).isEqualTo("REQUESTED");
        assertThat(captured.getValue().windows()).isNull();
        verifyNoInteractions(legacy);
    }

    @Test
    void explicitActiveLinkQueryKeepsRequestedRangeWithoutAnInvalidCommonWindowPolicy() throws Exception {
        mvc.perform(post("/internal/short-link-admin/v1/agent-tools/risk/active-link-query")
                        .header("X-Agent-Internal-Token", TOKEN).header("X-Agent-Username", "zhangsan")
                        .header("X-Agent-UserId", "1001").header("X-Agent-Auth-Version", "7")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSONObject.toJSONString(Map.of("linkIds", List.of(LINK),
                                "since", "2026-07-03T00:00:00Z", "endTime", "2026-07-10T00:00:00Z"))))
                .andExpect(jsonPath("$.code").value("0"));
        var captured = ArgumentCaptor.forClass(AnalyticsQueryRequest.class);
        verify(client).query(captured.capture());
        assertThat(captured.getValue().queryKind()).isEqualTo("ACTIVE_LINKS");
        assertThat(captured.getValue().windows()).isNull();
        assertThat(captured.getValue().endPolicy()).isEqualTo("REQUESTED");
        assertThat(captured.getValue().startInclusive()).isEqualTo(Instant.parse("2026-07-03T00:00:00Z").toEpochMilli());
        assertThat(captured.getValue().endExclusive()).isEqualTo(Instant.parse("2026-07-10T00:00:00Z").toEpochMilli());
        assertThat(captured.getValue().linkIds()).containsExactly(LINK);
    }

    @Test
    void analyticsPageFailureStaysFailureWithNoZeroOrPartialSuccess() throws Exception {
        when(client.query(any())).thenReturn(new JSONObject(Map.of("code", "SNAPSHOT_EXPIRED")));
        mvc.perform(window())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(legacy);
    }

    @Test
    void foreignGroupIsRejectedBeforeAuthorityOrAnalyticsCall() throws Exception {
        when(groups.count(any(Wrapper.class))).thenReturn(0L);
        mvc.perform(window())
                .andExpect(jsonPath("$.code").value("A000001"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Agent request requires an owned active group"));
        verifyNoInteractions(client, legacy);
    }

    @Test
    void returnedForeignLinkCannotBeEnrichedIntoTrustedScope() throws Exception {
        when(client.query(any()))
                .thenReturn(
                        envelope(
                                List.of(Map.of("linkId", LINK + 1, "pv", 2L)),
                                Map.of("pv", 2L),
                                Map.of("snapshotId", "s")));
        mvc.perform(window())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void windowTimesArePassedAsExactUtcHalfOpenBoundaries() throws Exception {
        mvc.perform(window()).andExpect(jsonPath("$.code").value("0"));
        var captured = ArgumentCaptor.forClass(AnalyticsQueryRequest.class);
        verify(client).query(captured.capture());
        assertThat(captured.getValue().startInclusive())
                .isEqualTo(Instant.parse("2026-07-09T16:00:00Z").toEpochMilli());
        assertThat(captured.getValue().endExclusive())
                .isEqualTo(Instant.parse("2026-07-09T18:00:00Z").toEpochMilli());
        assertThat(captured.getValue().authVersion()).isEqualTo(7L);
        assertThat(captured.getValue().tenantId()).isEqualTo("1001");
    }

    @Test
    void allThreeWindowsUseOneQueryAndOneCommonEnd() throws Exception {
        mvc.perform(
                        request("risk/short-link-windows")
                                .param("gid", "g1")
                                .param("fullShortUrl", "nurl.ink/abc123")
                                .param("endTime", "2026-07-10T00:00:00Z"))
                .andExpect(jsonPath("$.code").value("0"));
        var captured = ArgumentCaptor.forClass(AnalyticsQueryRequest.class);
        verify(client, times(1)).query(captured.capture());
        assertThat(captured.getValue().windows()).containsExactly("2h", "24h", "7d");
        assertThat(captured.getValue().endPolicy()).isEqualTo("COMMON_AVAILABLE_END");
        assertThat(captured.getValue().endExclusive() - captured.getValue().startInclusive())
                .isEqualTo(7L * 24 * 60 * 60 * 1000);
    }

    @Test
    void missingEnvelopeIsAnErrorRatherThanAnEmptyDataset() throws Exception {
        when(client.query(any())).thenReturn(new JSONObject(Map.of("code", "0")));
        mvc.perform(window())
                .andExpect(jsonPath("$.code").value("C000001"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unavailableMetricsRemainAbsentWhileQualityAndSnapshotRemainVisible() throws Exception {
        when(client.query(any()))
                .thenReturn(
                        envelope(
                                List.of(),
                                null,
                                Map.of(
                                        "snapshotId",
                                        "partial-snapshot",
                                        "completeness",
                                        "PARTIAL",
                                        "missingMetrics",
                                        List.of("denied"))));
        mvc.perform(window())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.metrics").doesNotExist())
                .andExpect(jsonPath("$.data.meta.completeness").value("PARTIAL"))
                .andExpect(jsonPath("$.data.meta.missingMetrics[0]").value("denied"));
    }

    @Test
    void pageSizeExceedingBudgetFailsBeforeAnalyticsAuthority() throws Exception {
        mvc.perform(
                        request("risk/active-short-links")
                                .param("since", "2026-07-03T00:00:00Z")
                                .param("endTime", "2026-07-10T00:00:00Z")
                                .param("pageSize", "501"))
                .andExpect(jsonPath("$.code").value("A000001"));
        verifyNoInteractions(client, legacy);
    }
}
