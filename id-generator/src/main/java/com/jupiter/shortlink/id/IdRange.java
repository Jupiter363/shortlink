package com.jupiter.shortlink.id;

public record IdRange(long startInclusive, long endExclusive) {
    public static final long ID_LIMIT = 1L << 52;

    public IdRange {
        if (startInclusive < 1 || endExclusive <= startInclusive || endExclusive > ID_LIMIT) {
            throw new IllegalArgumentException("Range must satisfy 1 <= start < end <= 2^52");
        }
    }

    public long size() {
        return endExclusive - startInclusive;
    }
}
