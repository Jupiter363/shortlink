package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.ProcessLiveness.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalProcessLivenessTest {
    private static final String DOMAIN = "backend-test-host-and-pid-namespace";

    @Test
    void currentJvmIdentityIsStableButAdaptersHaveDistinctNoncesAndPidStartMustMatch() {
        LocalProcessLiveness first = new LocalProcessLiveness(DOMAIN);
        LocalProcessLiveness second = new LocalProcessLiveness(DOMAIN);
        ProcessIdentity current = first.currentIdentity();
        assertSame(current, first.currentIdentity());
        assertEquals(ProcessHandle.current().pid(), current.pid());
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(), current.startedAtMillis());
        assertNotEquals(current.instanceId(), second.currentIdentity().instanceId());
        assertEquals(current.pid(), second.currentIdentity().pid());
        assertEquals(current.startedAtMillis(), second.currentIdentity().startedAtMillis());
        assertEquals(new Observation(State.ALIVE, PROCESS_ALIVE), first.observe(current));
        assertEquals(new Observation(State.ALIVE, PROCESS_ALIVE), first.observe(second.currentIdentity()),
                "A new adapter nonce is not evidence that the previous adapter's JVM died");
        ProcessIdentity reused = new ProcessIdentity(UUID.randomUUID().toString(), DOMAIN,
                current.pid(), current.startedAtMillis() - 1);
        assertEquals(new Observation(State.DEAD, PROCESS_ID_REUSED), first.observe(reused));
        assertTrue(ProcessHandle.current().isAlive(), "The currently occupying process is never killed");
        assertThrows(IllegalArgumentException.class, () -> new ProcessIdentity("not-a-uuid", DOMAIN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcessIdentity(UUID.randomUUID().toString(), " ", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcessIdentity(UUID.randomUUID().toString(), "d".repeat(257), 1, 1));
        assertDoesNotThrow(() -> new ProcessIdentity(UUID.randomUUID().toString(), "d".repeat(256), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcessIdentity(UUID.randomUUID().toString(), DOMAIN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcessIdentity(UUID.randomUUID().toString(), DOMAIN, 1, 0));
    }

    @Test
    void foreignDomainOrUnavailableFactsNeverBecomeDeathProof() {
        AtomicInteger reads = new AtomicInteger();
        LocalProcessLiveness absent = new LocalProcessLiveness(DOMAIN, pid -> {
            reads.incrementAndGet();
            return Optional.empty();
        });
        ProcessIdentity current = absent.currentIdentity();
        ProcessIdentity foreign = new ProcessIdentity(current.instanceId(), "other-pid-namespace", current.pid(), current.startedAtMillis());
        assertEquals(new Observation(State.UNKNOWN, PROCESS_DOMAIN_MISMATCH), absent.observe(foreign));
        assertEquals(0, reads.get(), "Do not inspect a coincidentally equal PID from a foreign namespace");
        assertEquals(new Observation(State.DEAD, PROCESS_ABSENT), absent.observe(current));
        assertEquals(1, reads.get());
        LocalProcessLiveness exited = new LocalProcessLiveness(DOMAIN,
                pid -> Optional.of(new LocalProcessLiveness.Snapshot(false, Optional.empty())));
        assertEquals(new Observation(State.DEAD, PROCESS_EXITED), exited.observe(current));
        LocalProcessLiveness noStart = new LocalProcessLiveness(DOMAIN,
                pid -> Optional.of(new LocalProcessLiveness.Snapshot(true, Optional.empty())));
        assertEquals(new Observation(State.UNKNOWN, PROCESS_START_UNAVAILABLE), noStart.observe(current));
        LocalProcessLiveness denied = new LocalProcessLiveness(DOMAIN, pid -> { throw new SecurityException("untrusted OS detail"); });
        assertEquals(new Observation(State.UNKNOWN, PROCESS_ACCESS_DENIED), denied.observe(current));
        LocalProcessLiveness unsupported = new LocalProcessLiveness(DOMAIN, pid -> { throw new UnsupportedOperationException(); });
        assertEquals(new Observation(State.UNKNOWN, PROCESS_PROBE_UNSUPPORTED), unsupported.observe(current));
        LocalProcessLiveness subMillis = new LocalProcessLiveness(DOMAIN, pid -> Optional.of(
                new LocalProcessLiveness.Snapshot(true, Optional.of(Instant.ofEpochMilli(current.startedAtMillis()).plusNanos(123)))));
        assertEquals(new Observation(State.ALIVE, PROCESS_ALIVE), subMillis.observe(current),
                "Both persisted and observed start times use epoch millisecond precision");
    }

    @Test
    void onlyTheTestChildJvmTransitionsFromAliveToDeadAfterStdinEof() throws Exception {
        LocalProcessLiveness liveness = new LocalProcessLiveness(DOMAIN);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classPath = Path.of(StdinChild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child = new ProcessBuilder(java, "-Xms16m", "-Xmx32m", "-cp", classPath, StdinChild.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            ProcessIdentity identity = new ProcessIdentity(UUID.randomUUID().toString(), DOMAIN, child.pid(),
                    child.toHandle().info().startInstant().orElseThrow().toEpochMilli());
            assertEquals(new Observation(State.ALIVE, PROCESS_ALIVE), liveness.observe(identity));
            child.getOutputStream().close();
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Owned child should exit naturally after stdin EOF");
            assertEquals(0, child.exitValue());
            Observation stopped = liveness.observe(identity);
            assertEquals(State.DEAD, stopped.state());
            assertTrue(Set.of(PROCESS_ABSENT, PROCESS_EXITED, PROCESS_ID_REUSED).contains(stopped.reasonCode()));
        } finally {
            try { child.getOutputStream().close(); }
            finally {
                // Cleanup is limited to this exact Process created by the test, never an observed PID.
                if (child.isAlive()) {
                    child.destroyForcibly();
                    assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned test child cleanup must complete");
                }
                child.getInputStream().close();
                child.getErrorStream().close();
            }
        }
    }

    /** Only JDK code is loaded in this child. No application, model, server, or test engine starts. */
    public static final class StdinChild {
        public static void main(String[] ignored) throws IOException {
            while (System.in.read() != -1) { /* wait only for the parent's stdin EOF */ }
        }
    }
}
