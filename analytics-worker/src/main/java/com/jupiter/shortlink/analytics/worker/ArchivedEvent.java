package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.*;

/**
 * The interpretation is frozen at archival intake, including broker-time validation and privacy
 * hash version.
 */
public record ArchivedEvent(
        int archiveVersion,
        RawReceipt raw,
        EnrichedRecord interpretation,
        String hashKeyFingerprint) {
    public static ArchivedEvent capture(RawReceipt raw, EventEnricher enricher, String key) {
        return new ArchivedEvent(1, raw, enricher.enrich(raw), EventEnricher.sha256(key));
    }

    public EnrichedRecord verified() {
        if (archiveVersion != 1
                || !raw.clusterId().equals(interpretation.clusterId())
                || !raw.topicId().equals(interpretation.topicId())
                || raw.partition() != interpretation.sourcePartition()
                || raw.offset() != interpretation.sourceOffset()
                || raw.receivedAt() != interpretation.receivedAt())
            throw new IllegalStateException("ARCHIVE_INTERPRETATION_MISMATCH");
        if (!TimeValidation.VERSION.equals(interpretation.validationVersion())
                || !EventEnricher.DATASET.equals(interpretation.detailDatasetVersion()))
            throw new IllegalStateException("UNSUPPORTED_ARCHIVE_INTERPRETATION");
        return interpretation;
    }
}
