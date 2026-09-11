package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdminAdmissionBudgetTest {
    static RequestBudgetProperties properties(int requests, int ordinary, int batch, int total) {
        RequestBudgetProperties properties = new RequestBudgetProperties();
        properties.setMaxInFlight(requests);
        properties.setOrdinaryBodyBytes(ordinary);
        properties.setBatchBodyBytes(batch);
        properties.setTotalBodyBytes(total);
        return properties;
    }

    static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path) {
            // The container has already recognized an empty, completely framed request.
            private final ServletInputStream empty = new RequestBudgetFilterTest.ControlledInput(new byte[0]);
            @Override public ServletInputStream getInputStream() { return empty; }
        };
        request.setAsyncSupported(true);
        return request;
    }

    @Test void defaultBudgetsAndInvalidConfigurationAreExplicit() {
        RequestBudgetProperties properties = new RequestBudgetProperties();
        properties.validate();
        assertEquals(64, properties.getMaxInFlight());
        assertEquals(262144, properties.getOrdinaryBodyBytes());
        assertEquals(8388608, properties.getBatchBodyBytes());
        assertEquals(67108864, properties.getTotalBodyBytes());
        assertEquals(Duration.ofSeconds(5), properties.getBodyReadTimeout());
        properties.setMaxInFlight(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setMaxInFlight(64);
        properties.setTotalBodyBytes(100);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setTotalBodyBytes(67108864);
        properties.setBodyReadTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test void sessionAndAsyncDownstreamShareOnePermitWithNoWaitingQueue() throws Exception {
        AdminAdmissionFilter filter = new AdminAdmissionFilter(properties(1, 8, 16, 16));
        MockHttpServletRequest first = request("GET", "/api/short-link/admin/v1/group");
        AtomicReference<AsyncContext> active = new AtomicReference<>();
        filter.doFilter(first, new MockHttpServletResponse(), (raw, response) -> {
            assertEquals(0, filter.availableRequests(), "session work is already admitted");
            active.set(raw.startAsync());
        });
        assertEquals(0, filter.availableRequests(), "returning the initial chain is not completion");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/internal/short-link-admin/v1/agent-tools/check"), rejected,
                (raw, response) -> fail("internal tools must also be bounded"));
        assertEquals(429, rejected.getStatus());
        active.get().complete();
        assertEquals(1, filter.availableRequests());
        filter.doFilter(request("GET", "/next"), new MockHttpServletResponse(), (raw, response) -> {});
        assertEquals(1, filter.availableRequests());
    }

    @Test void asyncRedispatchAndRestartNeitherReacquireNorMultiplyReleases() throws Exception {
        AdminAdmissionFilter filter = new AdminAdmissionFilter(properties(1, 8, 16, 16));
        MockHttpServletRequest raw = request("GET", "/sse");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<AsyncContext> first = new AtomicReference<>();
        filter.doFilter(raw, response, (request, output) -> first.set(request.startAsync()));
        first.get().dispatch();
        raw.setDispatcherType(DispatcherType.ASYNC);
        raw.setAsyncStarted(false);
        AtomicReference<AsyncContext> second = new AtomicReference<>();
        filter.doFilter(raw, response, (request, output) -> {
            assertEquals(0, filter.availableRequests());
            second.set(request.startAsync());
        });
        assertEquals(0, filter.availableRequests());
        second.get().complete();
        ((AsyncBudgetLease) raw.getAttribute(AsyncBudgetLease.ATTRIBUTE)).finish();
        assertEquals(1, filter.availableRequests());
    }

    @Test void completionDuringInitialChainDoesNotReleaseWhileSynchronousWorkStillRuns() throws Exception {
        AdminAdmissionFilter filter = new AdminAdmissionFilter(properties(1, 8, 16, 16));
        filter.doFilter(request("GET", "/sse"), new MockHttpServletResponse(), (request, response) -> {
            request.startAsync().complete();
            assertEquals(0, filter.availableRequests());
        });
        assertEquals(1, filter.availableRequests());
    }

    @Test void asyncErrorAndTimeoutReleaseExactlyOnce() throws Exception {
        for (boolean timeout : new boolean[] {true, false}) {
            AdminAdmissionFilter filter = new AdminAdmissionFilter(properties(1, 8, 16, 16));
            MockHttpServletRequest request = request("GET", "/sse");
            filter.doFilter(request, new MockHttpServletResponse(), (raw, response) -> raw.startAsync());
            MockAsyncContext context = (MockAsyncContext) request.getAsyncContext();
            AsyncBudgetLease lease = (AsyncBudgetLease) request.getAttribute(AsyncBudgetLease.ATTRIBUTE);
            AsyncEvent event = new AsyncEvent(context, new IOException("client closed"));
            if (timeout) lease.onTimeout(event); else lease.onError(event);
            lease.onComplete(event);
            assertEquals(1, filter.availableRequests());
        }
    }

    @Test void synchronousFailuresAndOnlyExactGetHealthReleaseOrBypassPermits() throws Exception {
        AdminAdmissionFilter filter = new AdminAdmissionFilter(properties(1, 8, 16, 16));
        IllegalStateException failure = new IllegalStateException("business failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                filter.doFilter(request("GET", "/ordinary"), new MockHttpServletResponse(),
                        (raw, response) -> { throw failure; })));
        assertEquals(1, filter.availableRequests());
        AtomicReference<AsyncContext> active = new AtomicReference<>();
        filter.doFilter(request("GET", "/sse"), new MockHttpServletResponse(),
                (raw, response) -> active.set(raw.startAsync()));
        filter.doFilter(request("GET", "/actuator/health/readiness"), new MockHttpServletResponse(),
                (raw, response) -> assertNull(raw.getAttribute(AsyncBudgetLease.ATTRIBUTE)));
        for (String[] candidate : new String[][] {{"POST", "/actuator/health"},
                {"GET", "/actuator/health/anything"}, {"GET", "/actuator/prometheus"}}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(candidate[0], candidate[1]), response,
                    (raw, output) -> fail("only exact GET health paths bypass resource admission"));
            assertEquals(429, response.getStatus());
        }
        active.get().complete();
    }
}
