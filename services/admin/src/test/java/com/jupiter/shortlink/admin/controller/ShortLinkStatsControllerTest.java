package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.admin.dto.resp.analytics.StatsEnvelope;
import com.jupiter.shortlink.admin.remote.analytics.AgentAnalyticsFacade;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkGroupStatsAccessRecordReqDTO;

import org.junit.jupiter.api.Test;

import java.util.*;

class ShortLinkStatsControllerTest {
    @Test
    void continuationUsesSameAnalyticsFacadeAndPreservesQualityMetadata() {
        var analytics = mock(AgentAnalyticsFacade.class);
        var controller = new ShortLinkStatsController(analytics);
        var q = new ShortLinkGroupStatsAccessRecordReqDTO();
        q.setGid("g1");
        q.setStartDate("2026-07-01");
        q.setEndDate("2026-07-07");
        q.setCurrent(2);
        q.setSize(50);
        var envelope =
                new StatsEnvelope(
                        Map.of(),
                        List.of(Map.of("pv", 3_000_000_000L)),
                        Map.of(
                                "snapshotId",
                                "snapshot-1",
                                "collectionQuality",
                                Map.of("status", "UNKNOWN")));
        when(analytics.query(
                        "g1",
                        null,
                        "2026-07-01",
                        "2026-07-07",
                        null,
                        "snapshot-1",
                        "cursor-1",
                        50,
                        "ACCESS_RECORDS"))
                .thenReturn(envelope);
        assertThat(
                        controller
                                .groupShortLinkStatsAccessRecord(q, "snapshot-1", "cursor-1")
                                .getData())
                .isSameAs(envelope);
        assertThatThrownBy(() -> controller.groupShortLinkStatsAccessRecord(q, null, null))
                .hasMessageContaining("snapshotId");
        verify(analytics)
                .query(
                        "g1",
                        null,
                        "2026-07-01",
                        "2026-07-07",
                        null,
                        "snapshot-1",
                        "cursor-1",
                        50,
                        "ACCESS_RECORDS");
        verifyNoMoreInteractions(analytics);
    }
}
