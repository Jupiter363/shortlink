package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class AnalyticsQueryServiceTest {
    @Test
    void rejectsOversizedPageBeforeAuthorization() {
        var a = mock(AuthorizationClient.class);
        var q =
                new AnalyticsQueryService(
                        mock(JdbcTemplate.class),
                        new ObjectMapper(),
                        a,
                        mock(ClickHouseReader.class),
                        settings());
        var request =
                new QueryRequest(
                        "1",
                        "alice",
                        1,
                        null,
                        List.of(1L),
                        0L,
                        100L,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        501,
                        "METRICS");
        assertEquals("TOO_LARGE", assertThrows(QueryFailure.class, () -> q.query(request)).code);
        verifyNoInteractions(a);
    }

    @Test
    void longCountsNeverNarrowToInt() {
        assertEquals(4_294_967_296L, AnalyticsQueryService.number("4294967296"));
    }

    @Test
    void unauthorizedScopeNeverReadsStatistics() {
        var auth = mock(AuthorizationClient.class);
        var ch = mock(ClickHouseReader.class);
        var db = mock(JdbcTemplate.class);
        var request =
                new QueryRequest(
                        "1",
                        "alice",
                        1,
                        null,
                        List.of(1L),
                        0L,
                        100L,
                        null,
                        "REQUESTED",
                        null,
                        null,
                        10,
                        "METRICS");
        when(auth.authorize(request)).thenThrow(new QueryFailure("FORBIDDEN", "denied"));
        assertThrows(
                QueryFailure.class,
                () ->
                        new AnalyticsQueryService(db, new ObjectMapper(), auth, ch, settings())
                                .query(request));
        verifyNoInteractions(ch, db);
    }

    private ApiSettings settings() {
        return new ApiSettings(
                "test-internal-token-123456789",
                "http://localhost:1",
                "http://localhost:2",
                "http://localhost:3",
                "default",
                "",
                "test");
    }
}
