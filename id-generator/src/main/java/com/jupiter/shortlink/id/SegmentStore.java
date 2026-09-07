package com.jupiter.shortlink.id;

/**
 * Implementations reserve on an independent, bounded transaction/connection. Only a confirmed
 * committed interval may be returned. Unknown commit outcomes must throw.
 */
@FunctionalInterface
public interface SegmentStore {
    IdRange reserve(int requestedSize);
}
