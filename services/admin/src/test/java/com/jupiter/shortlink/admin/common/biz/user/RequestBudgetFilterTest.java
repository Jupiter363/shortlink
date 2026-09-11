package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.jupiter.shortlink.admin.common.biz.user.AdminAdmissionBudgetTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestBudgetFilterTest {
    private static final String BATCH = "/api/short-link/admin/v1/create/batch";

    @Test void malformedAndDuplicateHeadersAreRejectedBeforeBodyConsumption() throws Exception {
        Object[][] cases = {{"Content-Encoding", new String[] {"gzip"}, 415},
                {"Content-Encoding", new String[] {"identity", "identity"}, 415},
                {"Content-Encoding", new String[] {"identity, identity"}, 415},
                {"Content-Length", new String[] {"1", "1"}, 400},
                {"Content-Length", new String[] {"1,1"}, 400},
                {"Content-Length", new String[] {"-1"}, 400},
                {"Content-Length", new String[] {"+1"}, 400},
                {"Content-Length", new String[] {"9223372036854775808"}, 400},
                {"Content-Length", new String[] {"9"}, 413}};
        RequestBudgetProperties properties = properties(1, 8, 16, 16);
        AdminAdmissionFilter admission = new AdminAdmissionFilter(properties);
        RequestBudgetFilter body = new RequestBudgetFilter(properties);
        try {
            for (Object[] test : cases) {
                AtomicInteger reads = new AtomicInteger();
                MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ordinary") {
                    @Override public ServletInputStream getInputStream() {
                        reads.incrementAndGet();
                        throw new AssertionError("header rejection must precede any body access");
                    }
                };
                request.setAsyncSupported(true);
                for (String value : (String[]) test[1]) request.addHeader((String) test[0], value);
                MockHttpServletResponse response = new MockHttpServletResponse();
                admission.doFilter(request, response, (raw, output) ->
                        body.doFilter(raw, output, (next, target) -> fail("invalid headers reached business")));
                assertEquals(test[2], response.getStatus());
                assertEquals(0, reads.get());
                assertEquals(1, admission.availableRequests());
                assertEquals(16, body.availableBodyBytes());
            }
        } finally { body.destroy(); }
    }

    @Test void onlyExactPostBatchReceivesTheLargeReservation() throws Exception {
        RequestBudgetProperties properties = properties(2, 8, 16, 16);
        AdminAdmissionFilter admission = new AdminAdmissionFilter(properties);
        RequestBudgetFilter body = new RequestBudgetFilter(properties);
        try {
            for (String[] route : new String[][] {{"PUT", BATCH}, {"POST", BATCH + "/"},
                    {"POST", "/unrelated/create/batch"}, {"POST", "/internal/command/batches"},
                    {"GET", BATCH}}) {
                MockHttpServletRequest request = request(route[0], route[1]);
                request.addHeader("Content-Length", "9");
                MockHttpServletResponse response = new MockHttpServletResponse();
                admission.doFilter(request, response, (raw, output) ->
                        body.doFilter(raw, output, (next, target) -> fail("not the exact batch endpoint")));
                assertEquals(413, response.getStatus());
            }
            MockHttpServletRequest request = request("POST", BATCH);
            request.setContent(new byte[0]);
            AtomicReference<AsyncContext> held = new AtomicReference<>();
            admission.doFilter(request, new MockHttpServletResponse(), (raw, output) ->
                    body.doFilter(raw, output, (next, target) -> held.set(next.startAsync())));
            assertEquals(0, body.availableBodyBytes(), "reserve the endpoint maximum, even for an empty body");
            MockHttpServletResponse rejected = new MockHttpServletResponse();
            admission.doFilter(request("GET", "/ordinary"), rejected, (raw, output) ->
                    body.doFilter(raw, output, (next, target) -> fail("body reservation has no queue")));
            assertEquals(429, rejected.getStatus());
            held.get().complete();
            assertEquals(16, body.availableBodyBytes());
            assertEquals(2, admission.availableRequests());
        } finally { body.destroy(); }
    }

    @Test void actualShortOrLongBodiesCannotLieAboutContentLength() throws Exception {
        for (int declared : new int[] {2, 4}) {
            assertBodyRejected(declared, new byte[3], 400);
        }
        assertBodyRejected(-1, new byte[9], 413);
    }

    private static void assertBodyRejected(int declared, byte[] content, int status) throws Exception {
        RequestBudgetProperties properties = properties(1, 8, 16, 16);
        AdminAdmissionFilter admission = new AdminAdmissionFilter(properties);
        RequestBudgetFilter body = new RequestBudgetFilter(properties);
        ControlledInput input = new ControlledInput(content);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ordinary") {
            @Override public ServletInputStream getInputStream() { return input; }
        };
        request.setAsyncSupported(true);
        if (declared >= 0) request.addHeader("Content-Length", Integer.toString(declared));
        else request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            admission.doFilter(request, response, (raw, output) ->
                    body.doFilter(raw, output, (next, target) -> fail("invalid body reached business")));
            input.listener.onDataAvailable();
            assertEquals(status, response.getStatus());
            assertEquals(1, admission.availableRequests());
            assertEquals(16, body.availableBodyBytes());
        } finally { body.destroy(); }
    }

    /** Manually deliver only the Servlet read callback; framing assertions use real bytes. */
    static final class ControlledInput extends ServletInputStream {
        final ByteArrayInputStream input;
        ReadListener listener;
        ControlledInput(byte[] bytes) { input = new ByteArrayInputStream(bytes); }
        @Override public int read() { return input.read(); }
        @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { this.listener = listener; }
    }
}
