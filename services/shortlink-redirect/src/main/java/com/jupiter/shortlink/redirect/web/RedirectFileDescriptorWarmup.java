package com.jupiter.shortlink.redirect.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Optional Linux startup work; never called from a request or a background timer. */
final class RedirectFileDescriptorWarmup {
    static final int DEFAULT_TARGET = 4096;
    static final int RESERVED_DESCRIPTORS = 256;
    static final long BUDGET_NANOS = 1_000_000_000L;
    private static final int CHECK_INTERVAL = 64;
    private static final Logger LOG = LoggerFactory.getLogger(RedirectFileDescriptorWarmup.class);
    private static final Pattern FD_SIZE = Pattern.compile("FDSize:[ \\t]+([0-9]+)[ \\t]*");
    private static final Pattern NOFILE = Pattern.compile(
            "Max open files[ \\t]+([0-9]+|unlimited)[ \\t]+([0-9]+|unlimited)[ \\t]+files[ \\t]*");

    interface Access {
        String operatingSystem();
        String status() throws IOException;
        String limits() throws IOException;
        Closeable openNull() throws IOException;
    }

    static final class LinuxAccess implements Access {
        private static final int MAX_PROC_BYTES = 65536;

        public String operatingSystem() { return System.getProperty("os.name"); }
        public String status() throws IOException { return read(Path.of("/proc/self/status")); }
        public String limits() throws IOException { return read(Path.of("/proc/self/limits")); }
        public Closeable openNull() throws IOException { return new FileInputStream("/dev/null"); }

        private static String read(Path path) throws IOException {
            try (var input = Files.newInputStream(path)) {
                byte[] bytes = input.readNBytes(MAX_PROC_BYTES + 1);
                if (bytes.length > MAX_PROC_BYTES) throw new IOException("FD_WARMUP_PROC_TOO_LARGE");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    record Result(int target, int initialSize, int peakSize, int retainedSize,
                  int opened, int closed, long elapsedNanos, String status) {}

    private final int target;
    private final Access access;
    private final LongSupplier clock;
    private Result result;
    private Throwable failure;

    RedirectFileDescriptorWarmup(int target) {
        this(target, new LinuxAccess(), System::nanoTime);
    }

    RedirectFileDescriptorWarmup(int target, Access access, LongSupplier clock) {
        if (target != 2048 && target != 4096 && target != 8192)
            throw new IllegalArgumentException("FD_WARMUP_TARGET_MUST_BE_2048_4096_OR_8192");
        this.target = target;
        this.access = Objects.requireNonNull(access);
        this.clock = Objects.requireNonNull(clock);
    }

    int targetSlots() { return target; }

    /** A concurrent or second server factory cannot pass an unfinished or failed warmup. */
    synchronized Result ensureOnce() {
        if (failure != null) throw propagate(failure);
        if (result != null) return result;
        try {
            result = perform();
            LOG.info("Redirect FD warmup status={} target={} initial={} peak={} retained={} opened={} closed={} elapsedNanos={}",
                    result.status(), result.target(), result.initialSize(), result.peakSize(),
                    result.retainedSize(), result.opened(), result.closed(), result.elapsedNanos());
            return result;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        }
    }

    private Result perform() {
        long started = clock.getAsLong();
        if (!"Linux".equals(access.operatingSystem()))
            throw new IllegalStateException("FD_WARMUP_REQUIRES_LINUX");
        final int initial;
        try {
            initial = parseFdSize(access.status());
            checkBudget(started);
            if (initial >= target)
                return new Result(target, initial, initial, initial, 0, 0,
                        elapsed(started), "ALREADY_SATISFIED");
            if (parseSoftLimit(access.limits()) < (long) target + RESERVED_DESCRIPTORS)
                throw new IllegalStateException("FD_WARMUP_NOFILE_HEADROOM_INSUFFICIENT");
        } catch (IOException error) {
            throw new IllegalStateException("FD_WARMUP_PROC_READ_FAILED", error);
        }

        // Allocate ownership storage before opening anything. No resizing or raw FD numbers.
        Closeable[] owned = new Closeable[target];
        int opened = 0;
        int closed = 0;
        int peak = initial;
        Throwable problem = null;
        try {
            while (peak < target && opened < owned.length) {
                checkBudget(started);
                owned[opened] = Objects.requireNonNull(access.openNull(), "FD_WARMUP_NULL_HANDLE");
                opened++;
                checkBudget(started);
                if (opened % CHECK_INTERVAL == 0 || opened == owned.length)
                    peak = Math.max(peak, parseFdSize(access.status()));
            }
            if (peak < target) throw new IllegalStateException("FD_WARMUP_TARGET_NOT_REACHED");
        } catch (Throwable error) {
            problem = error;
        } finally {
            // A failed close must not skip the other owned handles or be retried by FD number.
            for (int index = opened - 1; index >= 0; index--) {
                try {
                    owned[index].close();
                    closed++;
                } catch (Throwable error) {
                    if (problem == null) problem = error;
                    else if (error != problem) {
                        // Even failure to allocate suppressed-exception storage must not skip closes.
                        try { problem.addSuppressed(error); } catch (Throwable ignored) { }
                    }
                } finally {
                    owned[index] = null;
                }
            }
        }
        if (problem != null) throw propagate(problem);
        checkBudget(started);
        final int retained;
        try {
            retained = parseFdSize(access.status());
        } catch (IOException error) {
            throw new IllegalStateException("FD_WARMUP_POST_CLOSE_READ_FAILED", error);
        }
        checkBudget(started);
        if (retained < target) throw new IllegalStateException("FD_WARMUP_CAPACITY_NOT_RETAINED");
        return new Result(target, initial, peak, retained, opened, closed,
                elapsed(started), "PREALLOCATED");
    }

    private long elapsed(long started) { return clock.getAsLong() - started; }

    private void checkBudget(long started) {
        long elapsed = elapsed(started);
        if (elapsed < 0 || elapsed >= BUDGET_NANOS)
            throw new IllegalStateException("FD_WARMUP_STARTUP_BUDGET_EXCEEDED");
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof Error fatal) throw fatal;
        if (error instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("FD_WARMUP_IO_FAILED", error);
    }

    static int parseFdSize(String text) {
        String value = null;
        for (String line : Objects.requireNonNull(text).lines().toList()) {
            if (!line.startsWith("FDSize:")) continue;
            var match = FD_SIZE.matcher(line);
            if (value != null || !match.matches())
                throw new IllegalStateException("FD_WARMUP_INVALID_FDSIZE");
            value = match.group(1);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("FD_WARMUP_INVALID_FDSIZE", error);
        }
    }

    static long parseSoftLimit(String text) {
        String soft = null;
        String hard = null;
        for (String line : Objects.requireNonNull(text).lines().toList()) {
            if (!line.startsWith("Max open files")) continue;
            var match = NOFILE.matcher(line);
            if (soft != null || !match.matches())
                throw new IllegalStateException("FD_WARMUP_INVALID_NOFILE");
            soft = match.group(1);
            hard = match.group(2);
        }
        try {
            long parsedSoft = "unlimited".equals(soft) ? Long.MAX_VALUE : Long.parseLong(soft);
            long parsedHard = "unlimited".equals(hard) ? Long.MAX_VALUE : Long.parseLong(hard);
            if (parsedSoft < 1 || parsedHard < parsedSoft) throw new NumberFormatException();
            return parsedSoft;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("FD_WARMUP_INVALID_NOFILE", error);
        }
    }
}
