package com.jupiter.shortlink.command.membership;

import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.contract.Topics;
import com.jupiter.shortlink.membership.JdbcRouteMembershipStore;
import com.jupiter.shortlink.membership.RegistrationPermit;
import com.jupiter.shortlink.membership.RouteAddress;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded pipeline: timers retain permits, never DB connections or waiting worker threads. */
@Component
public class RoutePublicationCoordinator implements RoutePublication, AutoCloseable {
    private final JdbcRouteMembershipStore store;
    private final BusinessOutbox outbox;
    private final Semaphore slots;
    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor timer;
    private final long deadlineNanos;

    private record Guard(CompletableFuture<?> result, long deadline) {}

    private final ConcurrentHashMap<RegistrationPermit, Guard> guards = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();

    @Autowired
    public RoutePublicationCoordinator(
            JdbcTemplate jdbc,
            PlatformTransactionManager manager,
            BusinessOutbox outbox,
            @Value("${shortlink.membership.publication.max-pending:64}") int maxPending,
            @Value("${shortlink.membership.publication.workers:4}") int workers,
            @Value("${shortlink.membership.publication.deadline-millis:4000}")
                    long deadlineMillis) {
        this(
                new JdbcRouteMembershipStore(jdbc, manager),
                outbox,
                maxPending,
                workers,
                deadlineMillis);
    }

    RoutePublicationCoordinator(
            JdbcRouteMembershipStore store,
            BusinessOutbox outbox,
            int maxPending,
            int workers,
            long deadlineMillis) {
        if (maxPending < 1
                || maxPending > 512
                || workers < 1
                || workers > 16
                || workers > maxPending
                || deadlineMillis < 2000
                || deadlineMillis > 30000)
            throw new IllegalArgumentException("Invalid bounded route publication configuration");
        this.store = store;
        this.outbox = outbox;
        this.slots = new Semaphore(maxPending);
        this.deadlineNanos = TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        this.executor =
                new ThreadPoolExecutor(
                        workers,
                        workers,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(maxPending),
                        r -> daemon(r, "route-publication"),
                        new ThreadPoolExecutor.AbortPolicy());
        this.timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "route-publication-timer"));
        this.timer.setRemoveOnCancelPolicy(true);
        this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public <T> CompletableFuture<T> publish(Supplier<Prepared<T>> preparation) {
        if (!slots.tryAcquire())
            return CompletableFuture.failedFuture(unavailable("Route publication busy"));
        CompletableFuture<T> result = new CompletableFuture<>();
        long deadline = System.nanoTime() + deadlineNanos;
        pending.add(result);
        final ScheduledFuture<?> expiry;
        try {
            expiry =
                    timer.schedule(
                            () ->
                                    result.completeExceptionally(
                                            unavailable("Route publication deadline exceeded")),
                            deadlineNanos,
                            TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException failure) {
            pending.remove(result);
            slots.release();
            return CompletableFuture.failedFuture(unavailable("Route publication stopping"));
        }
        result.whenComplete(
                (value, error) -> {
                    expiry.cancel(false);
                    pending.remove(result);
                    slots.release();
                });
        dispatch(
                result,
                deadline,
                () -> {
                    Prepared<T> prepared = preparation.get();
                    if (expired(result, deadline)) return;
                    if (prepared.addresses().isEmpty()) {
                        result.complete(prepared.commit().apply(null));
                        return;
                    }
                    RegistrationPermit permit =
                            store.register(
                                    prepared.addresses(),
                                    notice ->
                                            outbox.append(
                                                    UUID.randomUUID().toString(),
                                                    Topics.ROUTE_MEMBERSHIP,
                                                    "membership",
                                                    notice));
                    guards.put(permit, new Guard(result, deadline));
                    result.whenComplete((value, error) -> guards.remove(permit));
                    schedulePublication(result, deadline, prepared, permit);
                });
        return result;
    }

    private <T> void schedulePublication(
            CompletableFuture<T> result,
            long deadline,
            Prepared<T> prepared,
            RegistrationPermit permit) {
        if (expired(result, deadline)) return;
        long delay = store.publicationDelayNanos(permit);
        if (delay >= deadline - System.nanoTime()) {
            result.completeExceptionally(unavailable("Route publication deadline exceeded"));
            return;
        }
        if (delay > 0) {
            ScheduledFuture<?> wakeup =
                    timer.schedule(
                            () ->
                                    dispatch(
                                            result,
                                            deadline,
                                            () ->
                                                    schedulePublication(
                                                            result, deadline, prepared, permit)),
                            delay,
                            TimeUnit.NANOSECONDS);
            result.whenComplete((value, error) -> wakeup.cancel(false));
            return;
        }
        result.complete(prepared.commit().apply(permit));
    }

    private <T> void dispatch(CompletableFuture<T> result, long deadline, Runnable action) {
        if (expired(result, deadline)) return;
        try {
            executor.execute(
                    () -> {
                        if (expired(result, deadline)) return;
                        try {
                            action.run();
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(unavailable("Route publication busy"));
        }
    }

    private boolean expired(CompletableFuture<?> result, long deadline) {
        if (result.isDone()) return true;
        if (deadline - System.nanoTime() > 0) return false;
        result.completeExceptionally(unavailable("Route publication deadline exceeded"));
        return true;
    }

    @Override
    public void verify(RegistrationPermit permit, List<RouteAddress> addresses) {
        assertOpen(permit);
        store.verifyForPublication(permit, addresses);
        assertOpen(permit);
    }

    @Override
    public void assertOpen(RegistrationPermit permit) {
        Guard guard = permit == null ? null : guards.get(permit);
        if (guard == null || expired(guard.result(), guard.deadline()))
            throw unavailable("Route publication cancelled or expired");
    }

    private static ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    @Override
    @PreDestroy
    public void close() {
        for (CompletableFuture<?> result : pending)
            result.completeExceptionally(unavailable("Route publication stopping"));
        timer.shutdownNow();
        executor.shutdownNow();
    }
}
