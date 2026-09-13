package com.jupiter.shortlink.analytics.api.job;

import com.jupiter.shortlink.analytics.api.QueryFailure;
import com.jupiter.shortlink.analytics.api.RecordDimensionSummary;
import com.jupiter.shortlink.analytics.api.VisitorHistory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordSummaryBudgetTest {
    @Test
    void accessRecordSummarySupportsTheEntireExportBudgetAndRejectsOnlyOverflow() {
        var summary = new RecordDimensionSummary(QueryJobService.MAX_ROWS);
        var row = Map.<String, Object>of("country", "CN", "province", "广东省", "network", "电信",
                "geoStatus", "RESOLVED", "geoVersion", "pinned-version", "visitorHash", "one-visitor",
                "uvType", "newUser");
        for (int i = 0; i < QueryJobService.MAX_ROWS; i++) summary.add(row);
        var history = new VisitorHistory.Plan(true, "bounded", 1, 2, "");
        var result = summary.materialize(history);
        var quality = (Map<?, ?>) result.get("dimensionQuality");
        assertEquals((long) QueryJobService.MAX_ROWS, ((Map<?, ?>) quality.get("country")).get("knownCount"));
        assertEquals(1L, ((Map<?, ?>) quality.get("uvTypeStats")).get("newUv"));
        assertThrows(QueryFailure.class, () -> summary.add(row));
        assertEquals(result, summary.materialize(history), "Rejected overflow must not alter the accepted summary");
    }
}
