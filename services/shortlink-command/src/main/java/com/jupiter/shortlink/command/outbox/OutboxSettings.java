package com.jupiter.shortlink.command.outbox;

/** One capacity slot covers the claim, send queue, broker wait and database acknowledgement. */
public record OutboxSettings(
        int batchSize,
        int maxInFlight,
        int sendWorkers,
        int ackWorkers,
        long ackTimeoutMillis,
        long leaseMillis,
        long shutdownMillis,
        int databaseTimeoutSeconds,
        int ackBatchSize,
        long ackBatchLingerMillis) {
    /** Source-compatible constructor; the production batch defaults apply here too. */
    public OutboxSettings(
            int batchSize,
            int maxInFlight,
            int sendWorkers,
            int ackWorkers,
            long ackTimeoutMillis,
            long leaseMillis,
            long shutdownMillis,
            int databaseTimeoutSeconds) {
        this(
                batchSize,
                maxInFlight,
                sendWorkers,
                ackWorkers,
                ackTimeoutMillis,
                leaseMillis,
                shutdownMillis,
                databaseTimeoutSeconds,
                Math.min(16, maxInFlight),
                2);
    }

    public OutboxSettings {
        if (batchSize < 1
                || batchSize > 256
                || maxInFlight < batchSize
                || maxInFlight > 256
                || sendWorkers < 1
                || sendWorkers > 16
                || sendWorkers > maxInFlight
                || ackWorkers < 1
                || ackWorkers > 16
                || ackWorkers > maxInFlight
                || ackTimeoutMillis < 100
                || ackTimeoutMillis > 20000
                || leaseMillis < ackTimeoutMillis + 1000
                || leaseMillis > 300000
                || shutdownMillis < 0
                || shutdownMillis > 10000
                || databaseTimeoutSeconds < 1
                || databaseTimeoutSeconds > 5
                || ackBatchSize < 1
                || ackBatchSize > Math.min(64, maxInFlight)
                || ackBatchLingerMillis < 0
                || ackBatchLingerMillis > 20) {
            throw new IllegalArgumentException("Invalid bounded Outbox settings");
        }
    }

    public static OutboxSettings defaults() {
        return new OutboxSettings(64, 64, 4, 4, 12000, 30000, 5000, 3);
    }
}
