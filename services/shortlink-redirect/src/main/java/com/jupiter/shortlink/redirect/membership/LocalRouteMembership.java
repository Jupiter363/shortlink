package com.jupiter.shortlink.redirect.membership;

import com.google.common.hash.BloomFilter;
import com.jupiter.shortlink.membership.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.SmartLifecycle;

import java.util.EnumMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Only the background worker reads the registry. Kafka can wake it but cannot establish coverage. A
 * holder atomically binds one filter, fully applied cut and exact-cut monotonic denial lease.
 */
public final class LocalRouteMembership implements RouteMembershipGuard, SmartLifecycle {
    enum UnknownReason {
        DISABLED,
        INITIALIZING,
        CONTROL_DISABLED,
        BASELINE_NOT_READY,
        CATCHING_UP,
        CAPACITY,
        LEASE_UNAVAILABLE,
        LEASE_EXPIRED,
        AUTHORITY_UNAVAILABLE,
        SHADOW
    }

    private record Holder(BloomFilter<RouteBloomKey> filter, AppliedCut cut, DenialLease lease) {}

    private record Proof(LocalRouteMembership owner, Holder holder) implements AbsentProof {}

    private final JdbcRouteMembershipStore store;
    private final RouteMembershipProperties config;
    private final RouteBloomSnapshotStore snapshots;
    private final AtomicReference<Holder> current = new AtomicReference<>();
    private final EnumMap<Outcome, Counter> checks = new EnumMap<>(Outcome.class);
    private final EnumMap<UnknownReason, Counter> unknowns = new EnumMap<>(UnknownReason.class);
    private final Counter renewFailures, snapshotFailures, shadowAbsent;
    private final AtomicBoolean hintQueued = new AtomicBoolean();
    private final java.util.concurrent.locks.ReentrantLock refreshLock =
            new java.util.concurrent.locks.ReentrantLock();
    private volatile UnknownReason reason = UnknownReason.INITIALIZING;
    private volatile boolean running;
    private volatile long lifecycle;
    private volatile BloomFilter<RouteBloomKey> shadowFilter;
    private volatile long appliedOrdinal, observedOrdinal;
    private volatile double observedFpp;
    private ScheduledExecutorService worker;
    // Worker confined. Bits are only added. A new generation always receives a fresh filter.
    private BloomFilter<RouteBloomKey> building;
    private String namespace, generation;
    private long nextOrdinal, revision, snapshotRevision = -1;
    private boolean snapshotAttempted;

    public LocalRouteMembership(
            JdbcRouteMembershipStore store,
            RouteMembershipProperties config,
            MeterRegistry meters) {
        this.store = store;
        this.config = config;
        this.snapshots = new RouteBloomSnapshotStore(config);
        for (Outcome outcome : Outcome.values())
            checks.put(
                    outcome,
                    meters.counter(
                            "shortlink.redirect.membership.checks", "outcome", outcome.name()));
        for (UnknownReason unknown : UnknownReason.values())
            unknowns.put(
                    unknown,
                    meters.counter(
                            "shortlink.redirect.membership.unknown", "reason", unknown.name()));
        renewFailures = meters.counter("shortlink.redirect.membership.lease.failures");
        snapshotFailures = meters.counter("shortlink.redirect.membership.snapshot.failures");
        shadowAbsent = meters.counter("shortlink.redirect.membership.shadow.absent");
        Gauge.builder(
                        "shortlink.redirect.membership.lease.remaining.millis",
                        this,
                        value -> {
                            Holder holder = value.current.get();
                            return holder == null
                                    ? 0
                                    : holder.lease().remainingNanos() / 1_000_000.0;
                        })
                .register(meters);
        Gauge.builder(
                        "shortlink.redirect.membership.applied.lag",
                        this,
                        value -> Math.max(0, value.observedOrdinal - value.appliedOrdinal))
                .register(meters);
        Gauge.builder(
                        "shortlink.redirect.membership.filter.bytes",
                        this,
                        value -> value.building == null ? 0 : value.config.filterBytes())
                .register(meters);
        Gauge.builder(
                        "shortlink.redirect.membership.filter.expected.fpp",
                        this,
                        value -> value.observedFpp)
                .register(meters);
    }

    @Override
    public Check check(String domain, String shortUri) {
        Holder holder = current.get();
        if (holder == null) {
            BloomFilter<RouteBloomKey> shadow = shadowFilter;
            if (reason == UnknownReason.SHADOW
                    && shadow != null
                    && !shadow.mightContain(new RouteBloomKey(domain, shortUri)))
                shadowAbsent.increment();
            return unknown(config.enabled() ? reason : UnknownReason.DISABLED);
        }
        if (!holder.lease().isValid()) return unknown(UnknownReason.LEASE_EXPIRED);
        if (holder.filter().mightContain(new RouteBloomKey(domain, shortUri))) {
            checks.get(Outcome.MAY_EXIST).increment();
            return Check.maybe();
        }
        Proof proof = new Proof(this, holder);
        if (!valid(proof)) return unknown(UnknownReason.LEASE_EXPIRED);
        checks.get(Outcome.DEFINITELY_ABSENT).increment();
        return Check.absent(proof);
    }

    @Override
    public boolean valid(AbsentProof proof) {
        return proof instanceof Proof captured
                && captured.owner() == this
                && current.get() == captured.holder()
                && captured.holder().lease().isValid();
    }

    private Check unknown(UnknownReason unknown) {
        checks.get(Outcome.UNKNOWN).increment();
        unknowns.get(unknown).increment();
        return Check.unknown();
    }

    @Override
    public synchronized void start() {
        if (running || !config.enabled()) return;
        lifecycle++;
        running = true;
        worker =
                Executors.newSingleThreadScheduledExecutor(
                        task -> {
                            Thread thread = new Thread(task, "redirect-membership-sync");
                            thread.setDaemon(true);
                            return thread;
                        });
        worker.scheduleWithFixedDelay(
                this::refreshSafely, 0, config.refreshMillis(), TimeUnit.MILLISECONDS);
    }

    /** Hints are coalesced to at most one queued wake-up, regardless of topic traffic. */
    public void hint() {
        ScheduledExecutorService executor = worker;
        if (!running || executor == null || !hintQueued.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            refreshSafely();
                        } finally {
                            hintQueued.set(false);
                        }
                    });
        } catch (RejectedExecutionException stopped) {
            hintQueued.set(false);
        }
    }

    private void refreshSafely() {
        if (!running) return;
        try {
            refresh();
        } catch (RuntimeException unavailable) {
            current.set(null);
            reason = UnknownReason.AUTHORITY_UNAVAILABLE;
            renewFailures.increment();
        }
    }

    /** Package-visible deterministic seam; production invokes this only on its single worker. */
    void refresh() {
        if (!refreshLock.tryLock()) return;
        try {
            refreshOnce();
        } finally {
            refreshLock.unlock();
        }
    }

    private void refreshOnce() {
        long expectedLifecycle = lifecycle;
        if (!config.enabled()) {
            current.set(null);
            reason = UnknownReason.DISABLED;
            return;
        }
        long began = System.nanoTime();
        ControlSnapshot cut = store.readControl();
        observedOrdinal = cut.memberCount();
        if (cut.mode() == Mode.OFF || cut.mode() == Mode.DRAINING) {
            current.set(null);
            reason = UnknownReason.CONTROL_DISABLED;
            return;
        }
        if (!cut.baselineReady()) {
            current.set(null);
            reason = UnknownReason.BASELINE_NOT_READY;
            return;
        }
        if (cut.memberCount() > config.expectedInsertions()) {
            current.set(null);
            reason = UnknownReason.CAPACITY;
            return;
        }
        restoreSnapshotOnce();
        if (building == null
                || !cut.namespace().equals(namespace)
                || !cut.generation().equals(generation)
                || nextOrdinal > cut.memberCount()
                || revision > cut.revision()) {
            current.set(null);
            building =
                    BloomFilter.create(
                            RouteAddressFunnel.INSTANCE,
                            config.expectedInsertions(),
                            config.falsePositiveProbability());
            namespace = cut.namespace();
            generation = cut.generation();
            nextOrdinal = 0;
            revision = 0;
            snapshotRevision = -1;
        }
        int pages = 0;
        while (nextOrdinal < cut.memberCount()) {
            if (++pages > config.maximumPagesPerRefresh()
                    || System.nanoTime() - began
                            >= TimeUnit.MILLISECONDS.toNanos(config.refreshBudgetMillis())) {
                current.set(null);
                reason = UnknownReason.CATCHING_UP;
                return;
            }
            RegistryPage page = store.readPage(cut, nextOrdinal, config.pageSize());
            if (!page.control().equals(cut) || page.entries().isEmpty())
                throw new IllegalStateException("Membership page lost its cut");
            for (RegistryEntry entry : page.entries()) {
                if (entry.ordinal() != nextOrdinal + 1
                        || entry.ordinal() > cut.memberCount()
                        || entry.registrationRevision() < revision
                        || entry.registrationRevision() > cut.revision())
                    throw new IllegalStateException("Membership registry gap");
                // Complete all bit writes before advancing any published revision/lease.
                building.put(
                        new RouteBloomKey(
                                entry.address().domainNorm(), entry.address().shortUri()));
                nextOrdinal = entry.ordinal();
                revision = entry.registrationRevision();
            }
            if (page.nextOrdinal() != nextOrdinal
                    || page.complete() != (nextOrdinal == cut.memberCount()))
                throw new IllegalStateException("Membership page has an invalid boundary");
            appliedOrdinal = nextOrdinal;
        }
        revision = cut.revision();
        appliedOrdinal = nextOrdinal;
        observedFpp = building.expectedFpp();
        if (observedFpp > config.maximumFalsePositiveProbability()) {
            current.set(null);
            reason = UnknownReason.CAPACITY;
            return;
        }
        if (snapshotRevision != revision) {
            try {
                snapshots.write(
                        new RouteBloomSnapshotStore.Snapshot(namespace, cut.cut(), building));
                snapshotRevision = revision;
            } catch (java.io.IOException unavailable) {
                snapshotFailures.increment();
            }
        }
        if (cut.mode() == Mode.SHADOW) {
            current.set(null);
            shadowFilter = building;
            reason = UnknownReason.SHADOW;
            return;
        }
        var lease = store.acquireLease(cut.cut());
        if (lease.isEmpty() || !lease.get().isValid() || !lease.get().cut().equals(cut.cut())) {
            current.set(null);
            reason = UnknownReason.LEASE_UNAVAILABLE;
            renewFailures.increment();
            return;
        }
        synchronized (this) {
            if (lifecycle != expectedLifecycle) return;
            shadowFilter = null;
            current.set(new Holder(building, cut.cut(), lease.get()));
        }
    }

    private void restoreSnapshotOnce() {
        if (snapshotAttempted) return;
        snapshotAttempted = true;
        try {
            var snapshot = snapshots.read();
            if (snapshot == null) return;
            building = snapshot.filter();
            namespace = snapshot.namespace();
            generation = snapshot.cut().generation();
            revision = snapshot.cut().revision();
            nextOrdinal = snapshot.cut().memberCount();
            snapshotRevision = revision;
        } catch (java.io.IOException invalid) {
            snapshotFailures.increment();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        lifecycle++;
        shadowFilter = null;
        current.set(null);
        if (worker != null) worker.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
