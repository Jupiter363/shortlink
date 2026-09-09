package com.jupiter.shortlink.redirect.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class RedirectFileDescriptorWarmupTest {
    static class FakeAccess implements RedirectFileDescriptorWarmup.Access {
        String os = "Linux";
        int reads;
        int opens;
        int closes;
        int initial = 128;
        int capacity = 4096;
        int growAfter = 64;
        int failOpen = -1;
        int failStatus = -1;
        int failClose = -1;
        String limit = "Max open files 1048576 1048576 files\n";
        final List<Integer> closeOrder = new ArrayList<>();
        final List<Handle> handles = new ArrayList<>();

        public String operatingSystem() { return os; }
        public String status() throws IOException {
            if (++reads == failStatus) throw new IOException("status failure");
            return "Name:\tjava\nFDSize:\t" + (opens >= growAfter ? capacity : initial) + "\n";
        }
        public String limits() { return limit; }
        public Closeable openNull() throws IOException {
            if (opens + 1 == failOpen) throw new IOException("open failure");
            Handle handle = new Handle(++opens);
            handles.add(handle);
            return handle;
        }
        class Handle implements Closeable {
            final int id;
            boolean attempted;
            Handle(int id) { this.id = id; }
            public void close() throws IOException {
                assertFalse(attempted, "never retry closing a possibly reused descriptor");
                attempted = true;
                closes++;
                closeOrder.add(id);
                if (id == failClose) throw new IOException("close failure");
            }
        }
    }

    @Test
    void targetValidationDoesNoIoAndAcceptsOnlyBoundedProfiles() {
        var access = new FakeAccess();
        for (int value : new int[] {-1, 0, 1024, 2049, 16384, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,
                    () -> new RedirectFileDescriptorWarmup(value, access, () -> 0));
        for (int value : new int[] {2048, 4096, 8192})
            assertDoesNotThrow(() -> new RedirectFileDescriptorWarmup(value, access, () -> 0));
        assertEquals(0, access.reads);
        assertEquals(0, access.opens);
    }

    @Test
    void reachesTargetClosesOnlyOwnedHandlesInReverseAndRunsOnce() {
        var access = new FakeAccess();
        var warmup = new RedirectFileDescriptorWarmup(4096, access, () -> 0);
        var result = warmup.ensureOnce();
        assertEquals("PREALLOCATED", result.status());
        assertEquals(128, result.initialSize());
        assertEquals(4096, result.retainedSize());
        assertEquals(64, result.opened());
        assertEquals(result.opened(), result.closed());
        assertEquals(64, access.closeOrder.get(0));
        assertEquals(1, access.closeOrder.get(63));
        assertTrue(access.handles.stream().allMatch(handle -> handle.attempted));
        int reads = access.reads;
        assertSame(result, warmup.ensureOnce());
        assertEquals(reads, access.reads);
        assertEquals(64, access.opens);
    }

    @Test
    void alreadySizedTableNeedsNoTemporaryHandlesOrLimitHeadroom() {
        var access = new FakeAccess();
        access.initial = 4096;
        access.limit = "invalid and never needed";
        var result = new RedirectFileDescriptorWarmup(4096, access, () -> 0).ensureOnce();
        assertEquals("ALREADY_SATISFIED", result.status());
        assertEquals(0, result.opened());
        assertEquals(0, access.closes);
        assertEquals(1, access.reads);
    }

    @Test
    void unsupportedOsMissingProcOrLowLimitFailsBeforeAnyOpen() {
        var unsupported = new FakeAccess();
        unsupported.os = "Windows 11";
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, unsupported, () -> 0).ensureOnce());
        assertEquals(0, unsupported.reads);
        var unavailable = new FakeAccess();
        unavailable.failStatus = 1;
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, unavailable, () -> 0).ensureOnce());
        var limited = new FakeAccess();
        limited.limit = "Max open files 4351 1048576 files\n";
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, limited, () -> 0).ensureOnce());
        assertEquals(0, unsupported.opens + unavailable.opens + limited.opens);
        limited.limit = "Max open files 4352 1048576 files\n";
        assertDoesNotThrow(() -> new RedirectFileDescriptorWarmup(4096, limited, () -> 0).ensureOnce());
    }

    @Test
    void firstAndPartialOpenFailuresCleanUpAndRemainFailed() {
        for (int failAt : new int[] {1, 7, 64}) {
            var access = new FakeAccess();
            access.failOpen = failAt;
            var warmup = new RedirectFileDescriptorWarmup(4096, access, () -> 0);
            var error = assertThrows(IllegalStateException.class, warmup::ensureOnce);
            assertEquals("open failure", error.getCause().getMessage());
            assertEquals(failAt - 1, access.closes);
            assertSame(error, assertThrows(IllegalStateException.class, warmup::ensureOnce));
            assertEquals(failAt - 1, access.opens);
        }
    }

    @Test
    void cleanupFailureDoesNotSkipAnyHandleOrReplaceTheOriginalOpenFailure() {
        var access = new FakeAccess();
        access.failOpen = 7;
        access.failClose = 4;
        var error = assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, access, () -> 0).ensureOnce());
        assertEquals("open failure", error.getCause().getMessage());
        assertEquals("close failure", error.getCause().getSuppressed()[0].getMessage());
        assertEquals(List.of(6, 5, 4, 3, 2, 1), access.closeOrder);
        var closeOnly = new FakeAccess();
        closeOnly.failClose = 32;
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, closeOnly, () -> 0).ensureOnce());
        assertEquals(64, closeOnly.closes);
    }

    @Test
    void fatalOpenErrorStillClosesOwnedHandlesAndIsNeverConvertedToSuccess() {
        var fatal = new AssertionError("fatal open");
        var access = new FakeAccess() {
            @Override public Closeable openNull() throws IOException {
                if (opens == 3) throw fatal;
                return super.openNull();
            }
        };
        var warmup = new RedirectFileDescriptorWarmup(4096, access, () -> 0);
        assertSame(fatal, assertThrows(AssertionError.class, warmup::ensureOnce));
        assertEquals(3, access.closes);
        assertSame(fatal, assertThrows(AssertionError.class, warmup::ensureOnce));
    }

    @Test
    void statusFailureDuringAllocationAndAfterCleanupCannotPublishSuccess() {
        for (int failedRead : new int[] {2, 3}) {
            var access = new FakeAccess();
            access.failStatus = failedRead;
            assertThrows(IllegalStateException.class,
                    () -> new RedirectFileDescriptorWarmup(4096, access, () -> 0).ensureOnce());
            assertEquals(64, access.closes);
        }
        var shrunk = new FakeAccess() {
            @Override public String status() throws IOException {
                if (closes > 0) return "FDSize: 128\n";
                return super.status();
            }
        };
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, shrunk, () -> 0).ensureOnce());
        assertEquals(64, shrunk.closes);
    }

    @Test
    void openCountAndCooperativeTimeBudgetAreBothBounded() {
        var neverGrows = new FakeAccess();
        neverGrows.growAfter = Integer.MAX_VALUE;
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(2048, neverGrows, () -> 0).ensureOnce());
        assertEquals(2048, neverGrows.opens);
        assertEquals(2048, neverGrows.closes);
        var time = new AtomicLong();
        var slow = new FakeAccess() {
            @Override public Closeable openNull() throws IOException {
                var handle = super.openNull();
                time.set(RedirectFileDescriptorWarmup.BUDGET_NANOS);
                return handle;
            }
        };
        assertThrows(IllegalStateException.class,
                () -> new RedirectFileDescriptorWarmup(4096, slow, time::get).ensureOnce());
        assertEquals(1, slow.opens);
        assertEquals(1, slow.closes);
    }

    @Test
    void concurrentCallCannotPassTheInProgressWarmup() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var access = new FakeAccess() {
            @Override public Closeable openNull() throws IOException {
                if (opens == 0) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test latch timeout"); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                }
                return super.openNull();
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            var warmup = new RedirectFileDescriptorWarmup(4096, access, () -> 0);
            var first = executor.submit(warmup::ensureOnce);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var secondEntered = new CountDownLatch(1);
            var second = executor.submit(() -> { secondEntered.countDown(); return warmup.ensureOnce(); });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(64, access.opens);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void procParsersRejectMissingAmbiguousNegativeAndOverflowedFields() {
        assertEquals(2048, RedirectFileDescriptorWarmup.parseFdSize("Name:\tjava\nFDSize:\t2048\n"));
        for (String invalid : List.of("", "xFDSize: 2048", "FDSize: -1", "FDSize: 0",
                "FDSize: 2147483648", "FDSize: 64\nFDSize: 128", "FDSize: 1x"))
            assertThrows(IllegalStateException.class, () -> RedirectFileDescriptorWarmup.parseFdSize(invalid));
        assertEquals(Long.MAX_VALUE, RedirectFileDescriptorWarmup.parseSoftLimit("Max open files unlimited unlimited files"));
        assertEquals(4096, RedirectFileDescriptorWarmup.parseSoftLimit("Max open files 4096 unlimited files"));
        for (String invalid : List.of("", "Max open files -1 4096 files", "Max open files 4096 2048 files",
                "Max open files 0 4096 files", "Max open files 9223372036854775808 unlimited files",
                "Max open files 4096 8192 bytes", "Max open files 4096 8192 files\nMax open files 4096 8192 files"))
            assertThrows(IllegalStateException.class, () -> RedirectFileDescriptorWarmup.parseSoftLimit(invalid));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void freshLinuxJvmRetainsExpandedCapacityClosesAllOwnedFdsAndPreservesSentinel() throws Exception {
        var access = new RedirectFileDescriptorWarmup.LinuxAccess();
        Assumptions.assumeTrue(RedirectFileDescriptorWarmup.parseSoftLimit(access.limits()) >= 4352,
                "successful growth requires target plus 256 descriptor headroom");
        runLinuxProbe(List.of(), "grow", "LINUX_FD_WARMUP_VERIFIED target=4096");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void lowLimitLinuxChildFailsWithoutOpeningHandlesOrChangingParentLimit() throws Exception {
        Path prlimit = Path.of("/usr/bin/prlimit");
        Assumptions.assumeTrue(java.nio.file.Files.isExecutable(prlimit), "optional util-linux child limiter");
        var access = new RedirectFileDescriptorWarmup.LinuxAccess();
        long before = RedirectFileDescriptorWarmup.parseSoftLimit(access.limits());
        Assumptions.assumeTrue(before >= 1024, "child limit must not raise inherited limits");
        runLinuxProbe(List.of(prlimit.toString(), "--nofile=1024:1024", "--"),
                "low-limit", "LINUX_FD_WARMUP_LOW_NOFILE_REJECTED");
        assertEquals(before, RedirectFileDescriptorWarmup.parseSoftLimit(access.limits()));
    }

    private void runLinuxProbe(List<String> prefix, String mode, String expected) throws Exception {
        var command = new ArrayList<>(prefix);
        command.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("java.class.path"),
                LinuxProbe.class.getName(), mode));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "bounded Linux probe did not finish");
            String output = new String(process.getInputStream().readNBytes(65536), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(expected), output);
            output.lines().filter(line -> line.startsWith("LINUX_FD_WARMUP_")).forEach(System.out::println);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    /** Separate JVM: never consumes or changes the test runner's descriptor table/rlimit. */
    public static class LinuxProbe {
        public static void main(String[] args) throws Exception {
            var linux = new RedirectFileDescriptorWarmup.LinuxAccess();
            var handles = new FileInputStream[8192];
            int[] count = {0};
            var access = new RedirectFileDescriptorWarmup.Access() {
                public String operatingSystem() { return linux.operatingSystem(); }
                public String status() throws IOException { return linux.status(); }
                public String limits() throws IOException { return linux.limits(); }
                public Closeable openNull() throws IOException {
                    var handle = (FileInputStream) linux.openNull();
                    handles[count[0]++] = handle;
                    return handle;
                }
            };
            try (var sentinel = new FileInputStream("/dev/zero")) {
                assertTrue(sentinel.getFD().valid());
                assertEquals(0, sentinel.read());
                var warmup = new RedirectFileDescriptorWarmup(4096, access, System::nanoTime);
                if (args.length == 1 && "low-limit".equals(args[0])) {
                    assertEquals(1024, RedirectFileDescriptorWarmup.parseSoftLimit(linux.limits()));
                    var failure = assertThrows(IllegalStateException.class, warmup::ensureOnce);
                    assertEquals("FD_WARMUP_NOFILE_HEADROOM_INSUFFICIENT", failure.getMessage());
                    assertEquals(0, count[0]);
                    assertTrue(sentinel.getFD().valid());
                    assertEquals(0, sentinel.read());
                    System.out.println("LINUX_FD_WARMUP_LOW_NOFILE_REJECTED opened=0 sentinelValid=true");
                    return;
                }
                assertArrayEquals(new String[] {"grow"}, args);
                var result = warmup.ensureOnce();
                assertEquals("PREALLOCATED", result.status(), "fresh child must actually grow its table");
                assertTrue(result.initialSize() < 4096);
                assertTrue(result.retainedSize() >= 4096);
                assertTrue(count[0] > 0 && count[0] <= 4096);
                assertEquals(count[0], result.closed());
                for (int i = 0; i < count[0]; i++) assertFalse(handles[i].getFD().valid(), "owned FD leaked");
                assertTrue(sentinel.getFD().valid(), "preexisting FD was closed");
                assertEquals(0, sentinel.read());
                System.out.println("LINUX_FD_WARMUP_VERIFIED target=4096 initial=" + result.initialSize()
                        + " retained=" + result.retainedSize() + " opened=" + result.opened()
                        + " closed=" + result.closed() + " elapsedNanos=" + result.elapsedNanos());
            }
        }
    }
}
