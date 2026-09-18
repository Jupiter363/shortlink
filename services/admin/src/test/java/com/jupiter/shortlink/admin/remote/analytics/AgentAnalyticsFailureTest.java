package com.jupiter.shortlink.admin.remote.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.exception.RemoteException;
import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

class AgentAnalyticsFailureTest {
    private final AnalyticsJsonClient client = mock(AnalyticsJsonClient.class);
    private final AgentAnalyticsFacade facade = new AgentAnalyticsFacade(client);

    @BeforeEach
    void currentOwnedScope() {
        UserContext.setUser(new UserInfoDTO("1001", "alice", null, 7L));
        when(client.resolve(any()))
                .thenReturn(new JSONObject(Map.of(
                        "tenantId", "1001",
                        "links", List.of(Map.of(
                                "linkId", 99L,
                                "gid", "g1",
                                "domain", "example.test",
                                "shortUri", "campaign",
                                "fullShortUrl", "example.test/campaign")))));
    }

    @AfterEach
    void clearPrincipal() {
        UserContext.removeUser();
    }

    @Test
    void reportsNormalizedMemoryCapacityFailure() {
        when(client.query(any())).thenReturn(new JSONObject(Map.of(
                "code", "UNAVAILABLE", "message", "ClickHouse memory capacity exceeded")));

        assertThatThrownBy(this::query)
                .isInstanceOf(RemoteException.class)
                .hasMessage("Analytics query unavailable: UNAVAILABLE (ClickHouse memory capacity exceeded)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM private_analytics WHERE token='example-sensitive-value'",
        "ClickHouse memory capacity exceeded: SELECT * FROM private_analytics",
        ""
    })
    void doesNotExposeUnrecognizedOrExtendedUpstreamMessages(String unsafeMessage) {
        when(client.query(any())).thenReturn(new JSONObject(Map.of(
                "code", "UNAVAILABLE", "message", unsafeMessage)));

        assertThatThrownBy(this::query)
                .isInstanceOf(RemoteException.class)
                .hasMessage("Analytics query unavailable: UNAVAILABLE");
    }

    @Test
    void doesNotClassifyOtherCodesAsCapacityFailures() {
        when(client.query(any())).thenReturn(new JSONObject(Map.of(
                "code", "FORBIDDEN", "message", "ClickHouse memory capacity exceeded")));

        assertThatThrownBy(this::query)
                .isInstanceOf(RemoteException.class)
                .hasMessage("Analytics query unavailable: FORBIDDEN");
    }

    @Test
    void preservesSuccessfulEnvelopeMetricsQualityAndOwnedIdentity() {
        Map<String, Object> metrics = Map.of("requested", Map.of("pv", 3_000_000_000L));
        Map<String, Object> quality = Map.of(
                "snapshotId", "snapshot-1", "completeness", "PARTIAL", "freshness", "UNKNOWN");
        when(client.query(any())).thenReturn(new JSONObject(Map.of(
                "code", "0",
                "data", new JSONObject(Map.of(
                        "metrics", metrics,
                        "items", List.of(Map.of("linkId", 99L, "pv", 3_000_000_000L)),
                        "meta", quality)))));

        StatsEnvelope envelope = query();

        assertThat(envelope.metrics()).isEqualTo(metrics);
        assertThat(envelope.meta()).containsAllEntriesOf(quality).containsEntry("tenantId", "1001");
        assertThat(envelope.items()).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("linkId", 99L)
                .containsEntry("pv", 3_000_000_000L)
                .containsEntry("gid", "g1")
                .containsEntry("fullShortUrl", "example.test/campaign"));
    }

    private StatsEnvelope query() {
        return facade.query("g1", null, "2026-09-09", "2026-09-15", null, null, null, 500, "METRICS");
    }

    @Test
    void dimensionRowsAreNotMistakenForUnauthorizedLinkRows() {
        var dimensions = List.of("province", "device");
        List<Map<String, Object>> filters = List.of(Map.of("dimension", "province", "operator", "IN", "values", List.of("浙江")));
        var bucket = Map.of("dimensions", Map.of("province", Map.of("value", "浙江", "state", "KNOWN")), "pv", 5L, "uv", 2L);
        when(client.query(any())).thenReturn(new JSONObject(Map.of("code", "0", "data", new JSONObject(Map.of(
                "items", List.of(bucket), "metrics", Map.of("requested", Map.of("pv", 5L, "uv", 2L)),
                "meta", Map.of("dimensions", dimensions, "filters", filters, "completeness", "PARTIAL"))))));
        var response = facade.query("g1", null, "2026-09-01", "2026-09-07", null, null, null, 500,
                "DIMENSION_BREAKDOWN", null, dimensions, filters);
        assertThat(response.items()).containsExactly(bucket);
        assertThat(response.meta()).containsEntry("completeness", "PARTIAL");
        var request = org.mockito.ArgumentCaptor.forClass(com.jupiter.shortlink.admin.dto.req.analytics.AnalyticsQueryRequest.class);
        org.mockito.Mockito.verify(client).query(request.capture());
        assertThat(request.getValue().dimensions()).isEqualTo(dimensions);
        assertThat(request.getValue().filters()).isEqualTo(filters);
        assertThat(request.getValue().linkIds()).containsExactly(99L);
    }
}
