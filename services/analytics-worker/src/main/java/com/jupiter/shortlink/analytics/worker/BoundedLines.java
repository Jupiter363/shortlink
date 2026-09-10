package com.jupiter.shortlink.analytics.worker;

import java.io.*;

/**
 * A line limit is enforced while reading, before an attacker-controlled line can grow without
 * bound.
 */
final class BoundedLines {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "analytics-http-deadlines");
                        t.setDaemon(true);
                        return t;
                    });

    interface Watch extends AutoCloseable {
        void close();
    }

    static Watch watch(InputStream input, long millis) {
        var future =
                DEADLINES.schedule(
                        () -> {
                            try {
                                input.close();
                            } catch (IOException ignored) {
                            }
                        },
                        millis,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private BoundedLines() {}

    static String read(Reader input, int max) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(max, 4096));
        for (int c; (c = input.read()) != -1; ) {
            if (c == '\n') return line.toString();
            if (line.length() >= max) throw new IOException("Input line exceeds budget");
            if (c != '\r') line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }
}
