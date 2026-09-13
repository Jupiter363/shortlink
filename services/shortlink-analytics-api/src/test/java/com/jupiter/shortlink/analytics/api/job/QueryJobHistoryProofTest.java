package com.jupiter.shortlink.analytics.api.job;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.analytics.api.*;
import com.jupiter.shortlink.contract.SourceCut;
import com.jupiter.shortlink.contract.Topics;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class QueryJobHistoryProofTest {
    // Reuse the package fixture without inheriting/re-running its tests.
    QueryJobServiceTest fixture;
    final long end = Math.floorDiv(System.currentTimeMillis(), 300_000) * 300_000;
    final long start = end - 300_000;
    long proofDeadline;

    @BeforeEach
    void setup() throws Exception {
        fixture = new QueryJobServiceTest();
        fixture.setup();
        var cut = new SourceCut(List.of(new SourceCut.Range("cluster", "topic", Topics.CLICK_RAW, 0, 0, 3)));
        fixture.db.update("UPDATE analytics_manifest SET window_start=?,source_observed_at=?,source_cut=?",
                start, end, fixture.json.writeValueAsString(cut));
    }

    @ParameterizedTest
    @CsvSource({"METRICS,TOO_LARGE", "METRICS,UNAVAILABLE", "ACCESS_RECORDS,TOO_LARGE", "ACCESS_RECORDS,UNAVAILABLE"})
    void historyReadHasIndependentBudgetAndFailureKeepsPublishedFacts(String kind, String code) {
        optionalReadFails(code, null);
        var job = submit(kind);
        fixture.service.execute(fixture.service.claim("worker"));
        assertEquals("SUCCEEDED", fixture.service.status(job.jobId(), fixture.identity(0)).state());
        var page = fixture.service.page(job.jobId(), fixture.identity(0));
        var quality = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) page.get("meta")).get("dimensionQuality")).get("uvTypeStats");
        assertEquals("UNKNOWN", quality.get("status")); assertEquals(2L, ((Number) quality.get("unknownUv")).longValue());
        assertEquals("UNPROVEN", quality.get("historyProof"));
        assertEquals("TOO_LARGE".equals(code) ? "HISTORY_PROOF_BUDGET_EXCEEDED" : "HISTORY_PROOF_UNAVAILABLE", quality.get("reason"));
        if ("METRICS".equals(kind)) {
            var requested = (Map<?, ?>) ((Map<?, ?>) page.get("metrics")).get("requested");
            assertEquals(3L, ((Number) requested.get("pv")).longValue());
            assertEquals(2L, ((Number) requested.get("uv")).longValue());
            assertEquals(2L, ((Number) requested.get("uip")).longValue());
            assertEquals(List.of(), requested.get("uvTypeStats"));
        } else {
            assertEquals(2, ((List<?>) page.get("items")).size());
            for (Object item : (List<?>) page.get("items")) assertEquals("UNKNOWN", ((Map<?, ?>) item).get("uvType"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FORBIDDEN", "NOT_READY", "SNAPSHOT_EXPIRED"})
    void protectedProofFailuresNeverPublishResults(String code) {
        optionalReadFails(code, null);
        var job = submit("METRICS");
        fixture.service.execute(fixture.service.claim("worker"));
        var status = fixture.service.status(job.jobId(), fixture.identity(0));
        assertNotEquals("SUCCEEDED", status.state()); assertEquals(code, status.errorCode());
        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class));
        verify(fixture.ch, never()).query(anyString(), startsWith("SELECT toString"), anyInt(), anyLong(), any());
    }

    @Test
    void currentFactsFailureStillFailsTheJobAfterHistoryFallback() {
        optionalReadFails("TOO_LARGE", null);
        doThrow(new QueryFailure("UNAVAILABLE", "canonical facts unavailable")).when(fixture.ch)
                .query(anyString(), startsWith("SELECT toString"), anyInt(), anyLong(), any());
        var job = submit("METRICS");
        fixture.service.execute(fixture.service.claim("worker"));
        var status = fixture.service.status(job.jobId(), fixture.identity(0));
        assertNotEquals("SUCCEEDED", status.state()); assertEquals("UNAVAILABLE", status.errorCode());
        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class));
    }

    @Test
    void epochChangeAfterOptionalFailureStillDiscardsAllPages() {
        optionalReadFails("UNAVAILABLE", () -> fixture.epoch.set("changed"));
        var job = submit("METRICS");
        fixture.service.execute(fixture.service.claim("worker"));
        assertEquals("FAILED", fixture.db.queryForObject("SELECT state FROM analytics_query_job WHERE job_id=?", String.class, job.jobId()));
        assertEquals("SNAPSHOT_EXPIRED", fixture.db.queryForObject("SELECT error_code FROM analytics_query_job WHERE job_id=?", String.class, job.jobId()));
        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class));
    }

    @Test
    void initialAuthorizationFailureNeverReadsProofOrFacts() {
        var job = submit("METRICS");
        var lease = fixture.service.claim("worker");
        assertNotNull(lease);
        doThrow(new QueryFailure("FORBIDDEN", "revoked")).when(fixture.auth).authorize(any());
        fixture.service.execute(lease);
        assertEquals("FORBIDDEN", fixture.db.queryForObject("SELECT error_code FROM analytics_query_job WHERE job_id=?", String.class, job.jobId()));
        verify(fixture.ch, never()).query(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    @Test
    void interruptionPropagatesWithoutPublishingUnknownSuccess() {
        optionalReadFails("UNAVAILABLE", () -> Thread.currentThread().interrupt());
        var job = submit("METRICS");
        var lease = fixture.service.claim("worker");
        try {
            assertThrows(QueryFailure.class, () -> fixture.service.execute(lease));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNotEquals("SUCCEEDED", fixture.service.status(job.jobId(), fixture.identity(0)).state());
        assertEquals(0, fixture.db.queryForObject("SELECT COUNT(*) FROM analytics_query_page", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private void optionalReadFails(String code, Runnable beforeFailure) {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(1);
            long deadline = invocation.getArgument(3);
            if (sql.contains("uniqExact(source_offset)")) {
                proofDeadline = deadline;
                assertTrue(deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(VisitorHistory.PROOF_TIMEOUT_MILLIS));
                if (beforeFailure != null) beforeFailure.run();
                throw new QueryFailure(code, "proof failed");
            }
            assertTrue(deadline - proofDeadline > TimeUnit.MINUTES.toNanos(4), "The main query keeps its own five-minute budget");
            assertTrue(sql.contains("toUInt8(0) scope_history_known"));
            assertFalse(sql.contains("retained_history"));
            Consumer<Map<String, Object>> output = invocation.getArgument(4);
            if (sql.startsWith("SELECT event_id")) {
                output.accept(record("one", "a")); output.accept(record("two", "b"));
            } else {
                output.accept(Map.of("group_row", 1, "whole_window", 1, "pv", 3L, "uv", 2L, "uip", 2L));
            }
            return null;
        }).when(fixture.ch).query(anyString(), anyString(), anyInt(), anyLong(), any());
    }

    private QueryJobService.Status submit(String kind) {
        return fixture.service.submit(new QueryJobService.Submit("history", new QueryRequest("1", "alice", 7,
                "g1", List.of(4000000001L), start, end, null, "REQUESTED", null, null, 500, kind)));
    }

    private Map<String, Object> record(String event, String visitor) {
        return Map.of("eventId", event, "linkId", 4000000001L, "occurredAt", start + 1,
                "visitorHash", visitor, "uvType", "UNKNOWN");
    }
}
