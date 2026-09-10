package com.jupiter.shortlink.contract;

import java.io.Serializable;

/** Broker metadata is attached by the consumer, never accepted from producer headers. */
public record RawReceipt(
        String clusterId,
        String topicId,
        String topic,
        int partition,
        long offset,
        long receivedAt,
        String timestampType,
        String payload)
        implements Serializable {
    public String identity() {
        return clusterId + "/" + topicId + "/" + partition + "/" + offset;
    }
}
