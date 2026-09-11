package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Nonblocking, absolute body deadline. Register after authentication, with ASYNC dispatches. */
public final class RequestBudgetFilter implements Filter {
    private static final String BODY = RequestBudgetFilter.class.getName() + ".body";
    private static final String BATCH = "/api/short-link/admin/v1/create/batch";
    private final int ordinaryBytes;
    private final int batchBytes;
    private final long timeoutNanos;
    private final Semaphore bytes;
    private final ScheduledThreadPoolExecutor deadlines;

    public RequestBudgetFilter(RequestBudgetProperties properties) {
        properties.validate();
        ordinaryBytes = properties.getOrdinaryBodyBytes();
        batchBytes = properties.getBatchBodyBytes();
        bytes = new Semaphore(properties.getTotalBodyBytes());
        timeoutNanos = properties.getBodyReadTimeout().toNanos();
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "admin-body-deadline");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) raw;
        HttpServletResponse response = (HttpServletResponse) output;
        if (AdminAdmissionFilter.isHealth(request)) {
            chain.doFilter(request, response);
            return;
        }
        AsyncBudgetLease lease = (AsyncBudgetLease) request.getAttribute(AsyncBudgetLease.ATTRIBUTE);
        if (lease == null) throw new ServletException("Admin admission filter must run before the body budget");
        Body existing = (Body) request.getAttribute(BODY);
        if (existing != null) {
            if (existing.ready) chain.doFilter(existing.wrap(request, response, lease), response);
            return;
        }
        int max = "POST".equals(request.getMethod()) && BATCH.equals(request.getRequestURI())
                ? batchBytes : ordinaryBytes;
        final long declared;
        try { declared = declaredLength(request, max); }
        catch (Rejected rejected) { AdminAdmissionFilter.reject(response, rejected.status); return; }
        if (!bytes.tryAcquire(max)) { AdminAdmissionFilter.reject(response, 429); return; }
        if (!lease.onRelease(() -> bytes.release(max))) return;
        ServletInputStream input = request.getInputStream();
        if (input.isFinished()) {
            if (declared > 0) { AdminAdmissionFilter.reject(response, 400); return; }
            Body body = new Body(new byte[0]);
            body.ready = true;
            request.setAttribute(BODY, body);
            chain.doFilter(body.wrap(request, response, lease), response);
            return;
        }
        if (!request.isAsyncSupported())
            throw new ServletException("Admin body budgets require Servlet async support");
        Body body = new Body(new byte[max]);
        request.setAttribute(BODY, body);
        AsyncContext context = request.startAsync(request, response);
        Reader reader = new Reader(input, response, context, body, declared);
        lease.onRelease(reader::cancelFromLease);
        reader.start();
    }

    private static long declaredLength(HttpServletRequest request, int max) throws Rejected {
        List<String> encodings = Collections.list(request.getHeaders("Content-Encoding"));
        if (encodings.size() > 1 || (!encodings.isEmpty() && !"identity".equalsIgnoreCase(encodings.get(0))))
            throw new Rejected(415);
        List<String> lengths = Collections.list(request.getHeaders("Content-Length"));
        if (lengths.size() > 1) throw new Rejected(400);
        List<String> transfers = Collections.list(request.getHeaders("Transfer-Encoding"));
        if (transfers.size() > 1 || (!transfers.isEmpty()
                && (!lengths.isEmpty() || !"chunked".equalsIgnoreCase(transfers.get(0)))))
            throw new Rejected(400);
        if (lengths.isEmpty()) return -1;
        String value = lengths.get(0);
        if (value == null || value.isEmpty() || value.length() > 19) throw new Rejected(400);
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < '0' || value.charAt(i) > '9') throw new Rejected(400);
        final long length;
        try { length = Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new Rejected(400); }
        if (length > max) throw new Rejected(413);
        return length;
    }

    private final class Reader implements ReadListener {
        private final ServletInputStream input;
        private final HttpServletResponse response;
        private final AsyncContext context;
        private final Body body;
        private final long declared;
        private final long deadline = System.nanoTime() + timeoutNanos;
        private final long previousAsyncTimeout;
        private volatile ScheduledFuture<?> cancellation;
        private volatile boolean done;

        Reader(ServletInputStream input, HttpServletResponse response, AsyncContext context,
                Body body, long declared) {
            this.input = input;
            this.response = response;
            this.context = context;
            this.body = body;
            this.declared = declared;
            previousAsyncTimeout = context.getTimeout();
        }

        void start() throws IOException {
            // Servlet async timeout starts after initial dispatch and uses a polling cadence.
            // This absolute timer also bounds peers that continuously drip another byte.
            context.setTimeout(0);
            ScheduledFuture<?> scheduled = deadlines.schedule(this::expire,
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            cancellation = scheduled;
            synchronized (this) {
                if (done) { scheduled.cancel(false); return; }
                input.setReadListener(this);
            }
        }

        @Override public synchronized void onDataAvailable() throws IOException {
            while (!done && input.isReady() && !input.isFinished()) {
                if (System.nanoTime() - deadline >= 0) { fail(408); return; }
                int left = body.data.length - body.length;
                // isReady() prevents a blocking socket read, including the overflow probe.
                int count = left == 0 ? input.read()
                        : input.read(body.data, body.length, Math.min(left, 8192));
                if (count < 0) { completeBody(); return; }
                if (left == 0) { fail(413); return; }
                if (count == 0) return;
                body.length += count;
                if (declared >= 0 && body.length > declared) { fail(400); return; }
            }
            if (!done && input.isFinished()) completeBody();
        }

        @Override public synchronized void onAllDataRead() { completeBody(); }
        @Override public synchronized void onError(Throwable failure) { fail(400); }
        private synchronized void expire() { fail(408); }

        private void completeBody() {
            if (done) return;
            if (System.nanoTime() - deadline >= 0) { fail(408); return; }
            if (declared >= 0 && declared != body.length) { fail(400); return; }
            done = true;
            body.ready = true;
            cancelDeadline();
            try {
                context.setTimeout(previousAsyncTimeout);
                context.dispatch();
            } catch (IllegalStateException cancelled) {
                body.ready = false;
                try { context.complete(); } catch (IllegalStateException alreadyComplete) { /* terminal */ }
            }
        }

        private void fail(int status) {
            if (done) return;
            done = true;
            cancelDeadline();
            AdminAdmissionFilter.reject(response, status);
            try { context.complete(); } catch (IllegalStateException alreadyComplete) { /* terminal */ }
        }

        void cancelDeadline() {
            ScheduledFuture<?> scheduled = cancellation;
            if (scheduled != null) scheduled.cancel(false);
        }

        void cancelFromLease() {
            done = true;
            cancelDeadline();
        }
    }

    private static final class Body {
        private final byte[] data;
        private int length;
        private volatile boolean ready;
        private Body(byte[] data) { this.data = data; }

        HttpServletRequest wrap(HttpServletRequest request, HttpServletResponse response, AsyncBudgetLease lease) {
            return new AsyncBudgetLease.Request(request, response, lease) {
                @Override public int getContentLength() { return length; }
                @Override public long getContentLengthLong() { return length; }
                @Override public int getIntHeader(String name) {
                    return "Content-Length".equalsIgnoreCase(name) ? length : super.getIntHeader(name);
                }
                @Override public String getHeader(String name) {
                    if ("Content-Length".equalsIgnoreCase(name)) return Integer.toString(length);
                    if ("Transfer-Encoding".equalsIgnoreCase(name)) return null;
                    return super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    if ("Content-Length".equalsIgnoreCase(name))
                        return Collections.enumeration(List.of(Integer.toString(length)));
                    if ("Transfer-Encoding".equalsIgnoreCase(name)) return Collections.emptyEnumeration();
                    return super.getHeaders(name);
                }
                @Override public Enumeration<String> getHeaderNames() {
                    List<String> names = new ArrayList<>(Collections.list(super.getHeaderNames()));
                    names.removeIf(name -> "Content-Length".equalsIgnoreCase(name)
                            || "Transfer-Encoding".equalsIgnoreCase(name));
                    names.add("Content-Length");
                    return Collections.enumeration(names);
                }
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream replay = new ByteArrayInputStream(data, 0, length);
                    return new ServletInputStream() {
                        private boolean listenerSet;
                        @Override public int read() { return replay.read(); }
                        @Override public int read(byte[] target, int offset, int count) {
                            return replay.read(target, offset, count);
                        }
                        @Override public boolean isFinished() { return replay.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) {
                            Objects.requireNonNull(listener);
                            if (listenerSet || !isAsyncStarted()) throw new IllegalStateException("Async reader required");
                            listenerSet = true;
                            getAsyncContext().start(() -> {
                                try {
                                    if (!isFinished()) listener.onDataAvailable();
                                    if (isFinished()) listener.onAllDataRead();
                                } catch (IOException | RuntimeException failure) { listener.onError(failure); }
                            });
                        }
                    };
                }
                @Override public BufferedReader getReader() throws IOException {
                    String encoding = getCharacterEncoding();
                    return new BufferedReader(new InputStreamReader(getInputStream(),
                            encoding == null ? StandardCharsets.UTF_8.name() : encoding));
                }
            };
        }
    }

    private static final class Rejected extends Exception {
        private final int status;
        private Rejected(int status) { this.status = status; }
    }

    int availableBodyBytes() { return bytes.availablePermits(); }
    @Override public void destroy() { deadlines.shutdownNow(); }
}
