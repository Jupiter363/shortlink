package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Opt-in local JDK adapter; no Spring registration, heartbeat, TTL, Future-based inference or kill.
 * A domain must identify the same host AND PID namespace. A hostname alone is insufficient.
 * JDK process information is a snapshot and PID values may be reused; observe does not fence work.
 * See https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.html .
 */
public final class LocalProcessLiveness implements ProcessLiveness {
    @FunctionalInterface
    interface Probe {
        Optional<Snapshot> inspect(long pid);
    }

    record Snapshot(boolean alive, Optional<Instant> startedAt) {
        Snapshot { Objects.requireNonNull(startedAt); }
    }

    private final ProcessIdentity identity;
    private final Probe probe;

    public LocalProcessLiveness(String processDomain) {
        this(processDomain, LocalProcessLiveness::inspect);
    }

    /** Narrow package-local seam for testing unavailable OS facts, never a request-configured probe. */
    LocalProcessLiveness(String processDomain, Probe probe) {
        ProcessIdentity.requireDomain(processDomain);
        this.probe = Objects.requireNonNull(probe);
        identity = currentIdentity(processDomain);
    }

    /** Stable for this adapter instance; reconstructing an adapter creates a different startup nonce. */
    public ProcessIdentity currentIdentity() { return identity; }

    @Override
    public Observation observe(ProcessIdentity expected) {
        Objects.requireNonNull(expected);
        if (!identity.processDomain().equals(expected.processDomain())) {
            return new Observation(State.UNKNOWN, PROCESS_DOMAIN_MISMATCH);
        }
        try {
            Optional<Snapshot> found = probe.inspect(expected.pid());
            if (found.isEmpty()) return new Observation(State.DEAD, PROCESS_ABSENT);
            Snapshot snapshot = found.get();
            if (!snapshot.alive()) return new Observation(State.DEAD, PROCESS_EXITED);
            if (snapshot.startedAt().isEmpty()) return new Observation(State.UNKNOWN, PROCESS_START_UNAVAILABLE);
            long startedAtMillis = snapshot.startedAt().get().toEpochMilli();
            if (startedAtMillis <= 0) return new Observation(State.UNKNOWN, PROCESS_START_UNAVAILABLE);
            return startedAtMillis == expected.startedAtMillis()
                    ? new Observation(State.ALIVE, PROCESS_ALIVE)
                    : new Observation(State.DEAD, PROCESS_ID_REUSED);
        } catch (SecurityException denied) {
            return new Observation(State.UNKNOWN, PROCESS_ACCESS_DENIED);
        } catch (UnsupportedOperationException unsupported) {
            return new Observation(State.UNKNOWN, PROCESS_PROBE_UNSUPPORTED);
        } catch (ArithmeticException invalidStart) {
            return new Observation(State.UNKNOWN, PROCESS_START_UNAVAILABLE);
        }
    }

    private static Optional<Snapshot> inspect(long pid) {
        return ProcessHandle.of(pid).map(handle -> {
            if (!handle.isAlive()) return new Snapshot(false, Optional.empty());
            return new Snapshot(true, handle.info().startInstant());
        });
    }

    private static ProcessIdentity currentIdentity(String processDomain) {
        ProcessHandle current = ProcessHandle.current();
        Instant start = current.info().startInstant()
                .orElseThrow(() -> new IllegalStateException(PROCESS_START_UNAVAILABLE));
        long startedAtMillis;
        try { startedAtMillis = start.toEpochMilli(); }
        catch (ArithmeticException invalidStart) { throw new IllegalStateException(PROCESS_START_UNAVAILABLE); }
        if (startedAtMillis <= 0) throw new IllegalStateException(PROCESS_START_UNAVAILABLE);
        return new ProcessIdentity(UUID.randomUUID().toString(), processDomain, current.pid(), startedAtMillis);
    }
}
