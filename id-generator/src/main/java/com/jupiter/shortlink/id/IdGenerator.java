package com.jupiter.shortlink.id;

import java.util.List;

/** One global allocator. A failed reservation may burn IDs, but never returns a partial result. */
public interface IdGenerator extends AutoCloseable {
    long nextId();

    /**
     * Exclusive, immutable ranges; their combined size is exactly count. They need not be adjacent.
     */
    List<IdRange> reserveRanges(int count);

    @Override
    void close();
}
