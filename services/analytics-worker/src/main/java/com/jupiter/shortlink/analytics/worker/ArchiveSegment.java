package com.jupiter.shortlink.analytics.worker;

public record ArchiveSegment(
        String clusterId,
        String topicId,
        String topic,
        int partition,
        long start,
        long end,
        String objectKey,
        String checksum,
        int recordCount,
        long minReceivedAt,
        long maxReceivedAt) {}
