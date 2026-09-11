package com.jupiter.shortlink.admin.common.biz.user;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.util.ArrayList;
import java.util.List;

/** One request owns its reservations across dispatches and every subsequent async cycle. */
final class AsyncBudgetLease implements AsyncListener {
    static final String ATTRIBUTE = AsyncBudgetLease.class.getName() + ".lease";
    private final List<Runnable> releases = new ArrayList<>();
    private int dispatches;
    private boolean terminal;
    private boolean released;
    private boolean dispatchPending;
    private AsyncContext listening;

    AsyncBudgetLease(Runnable releaseAdmission) { releases.add(releaseAdmission); }

    synchronized boolean enterDispatch() {
        if (terminal) return false;
        dispatches++;
        dispatchPending = false;
        return true;
    }

    synchronized void leaveDispatch(HttpServletRequest request) {
        dispatches--;
        if (!request.isAsyncStarted() && !dispatchPending) terminal = true;
        releaseIfFinished();
    }

    synchronized boolean onRelease(Runnable action) {
        if (released) {
            action.run();
            return false;
        }
        releases.add(action);
        return true;
    }

    synchronized void expectDispatch() { dispatchPending = true; }

    synchronized void listen(AsyncContext context) {
        if (context == listening) return;
        context.addListener(this);
        listening = context;
    }

    synchronized void finish() {
        terminal = true;
        dispatchPending = false;
        releaseIfFinished();
    }

    private void releaseIfFinished() {
        if (!terminal || dispatches != 0 || released) return;
        released = true;
        // These actions only release semaphores or cancel a scheduled deadline; no user callbacks.
        for (Runnable release : releases) release.run();
        releases.clear();
    }

    @Override public void onComplete(AsyncEvent event) { finish(); }
    @Override public void onTimeout(AsyncEvent event) { terminate(event.getAsyncContext()); }
    @Override public void onError(AsyncEvent event) { terminate(event.getAsyncContext()); }

    @Override public synchronized void onStartAsync(AsyncEvent event) {
        // Containers may reuse the AsyncContext object, but clear its listeners each cycle.
        listening = null;
        listen(event.getAsyncContext());
    }

    void terminate(AsyncContext context) {
        finish();
        try { context.complete(); } catch (IllegalStateException alreadyCompleted) { /* terminal */ }
    }

    Request wrap(HttpServletRequest request, HttpServletResponse response) {
        return new Request(request, response, this);
    }

    static class Request extends HttpServletRequestWrapper {
        private final HttpServletResponse response;
        final AsyncBudgetLease lease;

        Request(HttpServletRequest request, HttpServletResponse response, AsyncBudgetLease lease) {
            super(request);
            this.response = response;
            this.lease = lease;
        }

        @Override public AsyncContext startAsync() { return startAsync(this, response); }

        @Override public AsyncContext startAsync(ServletRequest request, ServletResponse response) {
            AsyncContext context = unwrap(super.startAsync(request, response));
            lease.listen(context);
            return new Context(context, lease);
        }

        @Override public AsyncContext getAsyncContext() {
            return new Context(unwrap(super.getAsyncContext()), lease);
        }
    }

    private static AsyncContext unwrap(AsyncContext context) {
        return context instanceof Context wrapped ? wrapped.delegate : context;
    }

    private static final class Context implements AsyncContext {
        private final AsyncContext delegate;
        private final AsyncBudgetLease lease;
        private Context(AsyncContext delegate, AsyncBudgetLease lease) {
            this.delegate = delegate;
            this.lease = lease;
        }
        @Override public ServletRequest getRequest() { return delegate.getRequest(); }
        @Override public ServletResponse getResponse() { return delegate.getResponse(); }
        @Override public boolean hasOriginalRequestAndResponse() { return delegate.hasOriginalRequestAndResponse(); }
        @Override public void dispatch() { lease.expectDispatch(); delegate.dispatch(); }
        @Override public void dispatch(String path) { lease.expectDispatch(); delegate.dispatch(path); }
        @Override public void dispatch(ServletContext context, String path) {
            lease.expectDispatch(); delegate.dispatch(context, path);
        }
        @Override public void complete() { delegate.complete(); }
        @Override public void start(Runnable run) { delegate.start(run); }
        @Override public void addListener(AsyncListener listener) { delegate.addListener(listener); }
        @Override public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
            delegate.addListener(listener, request, response);
        }
        @Override public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
            return delegate.createListener(clazz);
        }
        @Override public void setTimeout(long timeout) { delegate.setTimeout(timeout); }
        @Override public long getTimeout() { return delegate.getTimeout(); }
    }
}
