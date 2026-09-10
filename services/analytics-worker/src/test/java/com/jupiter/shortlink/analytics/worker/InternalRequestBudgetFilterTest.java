package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import java.util.concurrent.atomic.AtomicBoolean;

class InternalRequestBudgetFilterTest {
    private WorkerSettings settings() {
        return new WorkerSettings(
                "localhost:1",
                "analytics-filter-token-1234567890",
                "analytics-integration-hash-key-12345678901234567890",
                "http://localhost:2",
                "key",
                "secret",
                "bucket",
                "http://localhost:3",
                "default",
                "",
                "test");
    }

    @Test
    void rejectsBeforeDecodeAndBoundsAuthenticatedBodies() throws Exception {
        var filter = new InternalRequestBudgetFilter(settings());
        var request =
                new MockHttpServletRequest("POST", "/internal/analytics/v1/worker/recovery/begin");
        request.setContent(new byte[16385]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (q, r) -> called.set(true));
        assertEquals(403, response.getStatus());
        assertFalse(called.get());
        request =
                new MockHttpServletRequest("POST", "/internal/analytics/v1/worker/recovery/begin");
        request.addHeader("X-Internal-Token", settings().internalToken());
        request.setContent(new byte[16385]);
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (q, r) -> called.set(true));
        assertEquals(413, response.getStatus());
        assertFalse(called.get());
    }

    @Test
    void rejectsCompressedBodiesWithoutInvokingController() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/analytics/v1/worker/rebuild");
        request.addHeader("X-Internal-Token", settings().internalToken());
        request.addHeader("Content-Encoding", "gzip");
        var response = new MockHttpServletResponse();
        new InternalRequestBudgetFilter(settings())
                .doFilter(request, response, (q, r) -> fail("must reject compressed input"));
        assertEquals(415, response.getStatus());
    }
}
