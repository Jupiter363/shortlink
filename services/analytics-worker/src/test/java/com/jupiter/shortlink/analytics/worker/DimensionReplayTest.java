package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.contract.*;
import com.jupiter.shortlink.contract.geo.GeoLookup;
import com.jupiter.shortlink.contract.geo.GeoResult;
import org.junit.jupiter.api.Test;

class DimensionReplayTest {
    private static final String KEY = "abcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    void explicitReplayOnlyCopiesGeographyToNewInterpretation() {
        var original = captured(1000, 2000);
        String immutableBytes = EventJson.write(original);
        try (var enricher = new EventEnricher(KEY, 5000, geo())) {
            var replayed = DimensionReplay.apply(original, enricher);
            assertEquals("CN", replayed.country());
            assertEquals("广东省", replayed.province());
            assertEquals("深圳市", replayed.city());
            assertEquals("联通", replayed.network());
            assertEquals("fixture-xdb-v1", replayed.geoVersion());
            assertEquals("RESOLVED", replayed.geoStatus());
            assertEquals(original.interpretation(), replayed.withGeoFrom(original.interpretation()));
            assertEquals(immutableBytes, EventJson.write(original));
        }
    }

    @Test
    void unconfiguredReplayCannotReplaceHistoryWithInventedUnknownData() {
        try (var enricher = new EventEnricher(KEY, 5000)) {
            assertEquals("DIMENSION_REPLAY_REQUIRES_VERIFIED_GEO_DATABASE",
                    assertThrows(IllegalStateException.class,
                            () -> DimensionReplay.apply(captured(1000, 2000), enricher)).getMessage());
        }
    }

    @Test
    void changedPrivacyIdentityCannotBePublishedAsDimensionOnlyReplay() {
        try (var enricher = new EventEnricher("another-key-012345678901234567890123456789", 5000, geo())) {
            assertEquals("DIMENSION_REPLAY_BASE_FACT_MISMATCH",
                    assertThrows(IllegalStateException.class,
                            () -> DimensionReplay.apply(captured(1000, 2000), enricher)).getMessage());
        }
    }

    @Test
    void invalidOriginalKeepsItsFrozenValidationAndIsNotReinterpreted() {
        var original = captured(20000, 1000);
        try (var enricher = new EventEnricher(KEY, 5000, geo())) {
            assertSame(original.interpretation(), DimensionReplay.apply(original, enricher));
            assertEquals("FUTURE_EVENT", original.verified().validationResult());
        }
    }

    private ArchivedEvent captured(long occurredAt, long receivedAt) {
        var event = new ClickEventV1(EventIdentity.bind(occurredAt, "visit"), 1, occurredAt, "p", "t", 1,
                "g", 1, "example.test", "x", 1, "u", "1.2.3.4", "Chrome/10", "", "r", "t", 1);
        var raw = new RawReceipt("c", "t", Topics.CLICK_RAW, 0, 42, receivedAt, "LogAppendTime",
                EventJson.write(event));
        return ArchivedEvent.capture(raw, new EventEnricher(KEY, 5000), KEY);
    }

    private GeoLookup geo() {
        return new GeoLookup() {
            public GeoResult lookup(String ip) {
                return new GeoResult("CN", "广东省", "深圳市", "联通", "RESOLVED", version());
            }

            public String version() {
                return "fixture-xdb-v1";
            }
        };
    }
}
