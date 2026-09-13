package com.jupiter.shortlink.analytics.worker;

import com.jupiter.shortlink.contract.EnrichedRecord;
import com.jupiter.shortlink.contract.EventEnricher;

import java.util.List;
import java.util.Set;

/** Optional geography-only interpretation for a new immutable build, never an archive rewrite. */
final class DimensionReplay {
    private DimensionReplay() {}

    static EnrichedRecord apply(ArchivedEvent archived, EventEnricher enricher) {
        EnrichedRecord original = archived.verified();
        if (!original.valid() || !original.click()) return original;
        EnrichedRecord enriched = enricher.enrich(archived.raw());
        if (!base(original).equals(base(enriched)))
            throw new IllegalStateException("DIMENSION_REPLAY_BASE_FACT_MISMATCH");
        if (enriched.geoVersion().isBlank()
                || Set.of("NOT_CONFIGURED", "ERROR", "LOOKUP_ERROR").contains(enriched.geoStatus()))
            throw new IllegalStateException("DIMENSION_REPLAY_REQUIRES_VERIFIED_GEO_DATABASE");
        return original.withGeoFrom(enriched);
    }

    private static List<Object> base(EnrichedRecord e) {
        // parserVersion may acquire a geo-database suffix in the new interpreter. It is not
        // copied; the original UA parser evidence remains intact, and geoVersion records replay.
        return List.of(e.kind(), e.clusterId(), e.topicId(), e.sourceTopic(), e.sourcePartition(),
                e.sourceOffset(), e.receivedAt(), e.timestampType(), e.eventId(), e.payloadHash(),
                e.tenantId(), e.linkId(), e.occurredAt(), e.visitorHash(), e.ipHash(), e.browser(),
                e.os(), e.device(), e.refererDomain(), e.requestSource(), e.decisionStage(), e.status(),
                e.reason(), e.validationVersion(), e.validationResult(), e.detailDatasetVersion(),
                e.hashVersion());
    }
}
