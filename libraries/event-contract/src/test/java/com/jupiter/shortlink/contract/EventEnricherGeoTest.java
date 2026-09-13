package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jupiter.shortlink.contract.geo.GeoLookup;
import com.jupiter.shortlink.contract.geo.GeoLookupException;
import com.jupiter.shortlink.contract.geo.GeoResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventEnricherGeoTest {
    private static final String KEY = "test-only-analytics-hash-key-32-bytes";

    @Test
    void offlineDimensionsDoNotChangeRawPayloadHashOrBusinessIdentity() {
        RawReceipt receipt = receipt();
        var original = new EventEnricher(KEY, 5000).enrich(receipt);
        var lookup = new TestLookup();
        var enricher = new EventEnricher(KEY, 5000, lookup);
        var enriched = enricher.enrich(receipt);
        assertEquals(enriched, enricher.enrich(receipt));
        assertEquals("CN", enriched.country());
        assertEquals("广东省", enriched.province());
        assertEquals("深圳市", enriched.city());
        assertEquals("电信", enriched.network());
        assertEquals("RESOLVED", enriched.geoStatus());
        assertEquals(lookup.version(), enriched.geoVersion());
        assertTrue(enriched.parserVersion().contains(lookup.version()));
        assertEquals("detail-v1", enriched.detailDatasetVersion());
        assertEquals(original.payloadHash(), enriched.payloadHash());
        assertEquals(original.eventId(), enriched.eventId());
        assertEquals(original.visitorHash(), enriched.visitorHash());
        assertEquals(original.ipHash(), enriched.ipHash());
        assertEquals(original.validationResult(), enriched.validationResult());
        assertTrue(enriched.valid());
        String json = EventJson.write(enriched);
        assertFalse(json.contains("114.114.114.114"));
        assertFalse(json.contains("visitor-cookie"));
        enricher.close();
        assertTrue(lookup.closed);
    }

    @Test
    void missingLegacyFieldsReadAsUnknownAndGeoCopyPreservesFrozenBusinessFields() throws Exception {
        var base = new EventEnricher(KEY, 5000).enrich(receipt());
        ObjectNode tree = (ObjectNode) new ObjectMapper().readTree(EventJson.write(base));
        tree.remove(List.of("province", "city", "network", "geoStatus", "geoVersion"));
        var restored = EventJson.read(tree.toString(), EnrichedRecord.class);
        assertEquals("UNKNOWN", restored.province());
        assertEquals("UNKNOWN", restored.city());
        assertEquals("UNKNOWN", restored.network());
        assertEquals("UNKNOWN", restored.geoStatus());
        assertEquals("", restored.geoVersion());
        var rich = new EventEnricher(KEY, 5000, new TestLookup()).enrich(receipt());
        var copy = restored.withGeoFrom(rich);
        assertEquals(restored, copy.withGeoFrom(restored));
        assertEquals(restored.parserVersion(), copy.parserVersion());
        assertEquals(rich.geoVersion(), copy.geoVersion());
        assertEquals(rich.city(), copy.city());
    }

    @Test
    void geoInfrastructureFailureEscapesInsteadOfBecomingInvalidSchema() {
        var lookup = new TestLookup() {
            @Override public GeoResult lookup(String ip) { throw new GeoLookupException("GEO_LOOKUP_FAILED"); }
        };
        var enricher = new EventEnricher(KEY, 5000, lookup);
        assertEquals("GEO_LOOKUP_FAILED", assertThrows(GeoLookupException.class,
                () -> enricher.enrich(receipt())).getMessage());
        // Only decode failures retain the legacy INVALID_SCHEMA classification.
        var malformed = new RawReceipt("cluster", "topic-id", Topics.CLICK_RAW, 0, 1, 1000,
                "LogAppendTime", "{}");
        assertFalse(enricher.enrich(malformed).valid());
        assertEquals("NOT_APPLICABLE", enricher.enrich(malformed).geoStatus());
    }

    private RawReceipt receipt() {
        var event = new ClickEventV1(EventIdentity.bind(1000, "geo-event"), 1, 1000, "producer", "tenant",
                42, "group", 1, "s.example", "a", 1, "visitor-cookie", "114.114.114.114",
                "Mozilla/5.0 Chrome/100.0 Windows", "https://ref.example/page", "request", "trace", 1);
        return new RawReceipt("cluster", "topic-id", Topics.CLICK_RAW, 0, 1, 1000,
                "LogAppendTime", EventJson.write(event));
    }

    private static class TestLookup implements GeoLookup {
        boolean closed;
        @Override public GeoResult lookup(String ip) {
            return new GeoResult("CN", "广东省", "深圳市", "电信", "RESOLVED", version());
        }
        @Override public String version() { return "ip2r-test-immutable"; }
        @Override public void close() { closed = true; }
    }
}
