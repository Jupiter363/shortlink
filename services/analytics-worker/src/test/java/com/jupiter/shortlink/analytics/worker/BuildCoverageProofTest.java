package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Consumer;

class BuildCoverageProofTest {
    @Test
    void newProofPreservesOriginalReceiptProofAndAddsVersionedDimensions() {
        var ch = replies(2, 2);
        var proof = BuildCoverageProof.read(ch, "replica", "build");
        assertEquals(Map.of("n", 2, "digest", "raw-proof", "dimensionDigest", "geo-proof",
                "dimensionVersion", "geo-v1"), proof);
        verify(ch).query(eq("replica"), contains("country,province,city,network,geo_status,geo_version"), any());
    }

    @Test
    void sameReceiptWithConflictingDimensionsCannotBecomePublishedProof() {
        assertEquals("CONFLICTING_BUILD_DIMENSIONS",
                assertThrows(IllegalStateException.class,
                        () -> BuildCoverageProof.read(replies(1, 2), "replica", "build")).getMessage());
    }

    @Test
    void noReplicaContentResponseDoesNotBecomeAnEmptySuccessProof() {
        var ch = mock(ClickHouseStore.class);
        assertEquals("MISSING_BUILD_COVERAGE_PROOF",
                assertThrows(IllegalStateException.class,
                        () -> BuildCoverageProof.read(ch, "replica", "build")).getMessage());
    }

    @Test
    void replayRetryCannotMixDifferentPinnedDatabaseVersionsInOneBuild() {
        var ch = mock(ClickHouseStore.class);
        doAnswer(call -> {
            Consumer<Map<String, Object>> each = call.getArgument(2);
            each.accept(Map.of("mismatched", 1));
            return null;
        }).when(ch).query(anyString(), anyString(), any());
        assertEquals("DIMENSION_REPLAY_VERSION_CHANGED_REQUIRES_NEW_BUILD",
                assertThrows(IllegalStateException.class,
                        () -> BuildCoverageProof.requireGeoVersion(ch, "replica", "build", "new-xdb")).getMessage());
    }

    private ClickHouseStore replies(int receiptCount, int dimensionCount) {
        var ch = mock(ClickHouseStore.class);
        doAnswer(call -> {
            String sql = call.getArgument(1);
            Consumer<Map<String, Object>> each = call.getArgument(2);
            if (sql.contains("dimensionDigest")) each.accept(Map.of("n", dimensionCount, "dimensionDigest", "geo-proof"));
            else each.accept(Map.of("n", receiptCount, "digest", "raw-proof"));
            return null;
        }).when(ch).query(anyString(), anyString(), any());
        return ch;
    }
}
