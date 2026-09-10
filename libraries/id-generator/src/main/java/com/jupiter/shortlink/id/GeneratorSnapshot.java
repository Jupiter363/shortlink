package com.jupiter.shortlink.id;

public record GeneratorSnapshot(
        long issuedIds,
        long reservedRanges,
        long discardedIds,
        long databaseAttempts,
        long refillFailures,
        long executorRejections,
        int requestedStep,
        long stepChanges,
        long currentRemaining,
        boolean nextReady,
        boolean refillRunning,
        boolean closed) {}
