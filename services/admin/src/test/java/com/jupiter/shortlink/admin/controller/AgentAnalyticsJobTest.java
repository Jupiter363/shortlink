package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest;
import com.jupiter.shortlink.admin.remote.analytics.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

class AgentAnalyticsJobTest {
    private final AnalyticsJsonClient client = mock(AnalyticsJsonClient.class);
    private final AgentAnalyticsFacade facade = new AgentAnalyticsFacade(client);

    @BeforeEach
    void principal() {
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
    }

    @AfterEach
    void clear() {
        UserContext.removeUser();
    }

    private JSONObject result(Object data) {
        var value = new JSONObject();
        value.put("code", "0");
        value.put("data", data);
        return value;
    }

    @Test
    void submissionResolvesCurrentOwnedScopeAndReturnsPendingWithoutPolling() {
        when(client.resolve(any()))
                .thenReturn(
                        new JSONObject(
                                Map.of(
                                        "tenantId",
                                        "1001",
                                        "ownershipVersion",
                                        "v1",
                                        "links",
                                        List.of(Map.of("linkId", 9_007_199_254_740_999L)))));
        when(client.createJob(any()))
                .thenReturn(result(Map.of("jobId", "job-1", "state", "QUEUED")));
        assertThat(facade.submitJob("request-1", "g1", null, "2026-07-01", "2026-08-01", "METRICS"))
                .containsEntry("status", "PENDING")
                .containsEntry("resultReady", false);
        var capture = ArgumentCaptor.forClass(Map.class);
        verify(client).createJob(capture.capture());
        var query = (AnalyticsQueryRequest) capture.getValue().get("query");
        assertThat(query.tenantId()).isEqualTo("1001");
        assertThat(query.subjectId()).isEqualTo("alice");
        assertThat(query.authVersion()).isEqualTo(7L);
        assertThat(query.linkIds()).containsExactly(9_007_199_254_740_999L);
        assertThat(query.snapshotId()).isNull();
        assertThat(query.windows()).isNull();
        verify(client, never()).job(anyString(), anyString(), any());
    }

    @Test
    void eachStatusUsesCurrentIdentityAndRevokedOwnershipFailsClosed() {
        when(client.job(eq("job-1"), eq("status"), any()))
                .thenReturn(new JSONObject(Map.of("code", "FORBIDDEN")));
        UserContext.setUser(new UserInfoDTO("1002", "bob", null, 9L));
        assertThatThrownBy(() -> facade.jobStatus("job-1")).hasMessageContaining("FORBIDDEN");
        var capture = ArgumentCaptor.forClass(Map.class);
        verify(client).job(eq("job-1"), eq("status"), capture.capture());
        assertThat(capture.getValue())
                .containsEntry("tenantId", "1002")
                .containsEntry("subjectId", "bob")
                .containsEntry("authVersion", 9L);
    }

    @Test
    void incompleteResultIsNeverConvertedToEmptyStatistics() {
        when(client.job(eq("job-1"), eq("page"), any()))
                .thenReturn(new JSONObject(Map.of("code", "NOT_READY")));
        assertThatThrownBy(() -> facade.jobPage("job-1", 0, 500)).hasMessageContaining("NOT_READY");
    }

    @Test
    void resultPreservesUnknownQualityAndLongCounts() {
        when(client.job(eq("job-1"), eq("page"), any()))
                .thenReturn(
                        result(
                                Map.of(
                                        "metrics",
                                        Map.of(),
                                        "items",
                                        List.of(Map.of("linkId", 99L, "pv", 3_000_000_000L)),
                                        "meta",
                                        Map.of(
                                                "snapshotId",
                                                "job-1",
                                                "collectionQuality",
                                                Map.of("status", "UNKNOWN"),
                                                "missingMetrics",
                                                List.of("country")))));
        Map<String, Object> page = facade.jobPage("job-1", 0, 500);
        assertThat(((Map<?, ?>) page.get("meta")).get("missingMetrics"))
                .isEqualTo(List.of("country"));
        assertThat(((Map<?, ?>) ((List<?>) page.get("items")).get(0)).get("pv"))
                .isEqualTo(3_000_000_000L);
    }

    @Test
    void oversizedScopeAndAbsentPrincipalAreRejectedBeforeSubmission() {
        when(client.resolve(any()))
                .thenReturn(
                        new JSONObject(
                                Map.of("tenantId", "1001", "links", List.of(), "nextCursor", 500)));
        assertThatThrownBy(
                        () ->
                                facade.submitJob(
                                        "request-1",
                                        "g1",
                                        null,
                                        "2026-07-01",
                                        "2026-08-01",
                                        "METRICS"))
                .hasMessageContaining("TOO_LARGE");
        UserContext.removeUser();
        assertThatThrownBy(() -> facade.jobStatus("job-1")).hasMessageContaining("authenticated");
        verify(client, never()).createJob(any());
        verify(client, never()).job(anyString(), anyString(), any());
    }
}
