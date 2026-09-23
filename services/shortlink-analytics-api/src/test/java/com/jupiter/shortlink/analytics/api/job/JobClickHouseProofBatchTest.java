package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.ApiSettings;
import com.jupiter.shortlink.analytics.api.QueryFailure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class JobClickHouseProofBatchTest {
    private static final String REPLICA = "http://replica.test:8123";
    private static final String GEO_FIELDS =
            "receipt_id,payload_hash,validation_result,country,province,city,network,geo_status,geo_version";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void twoDaySelectionVerifies560BuildsIn18BoundedReadsIncludingEmptyAndRepeatedBuilds() throws Exception {
        var windows = new ArrayList<ManifestPlan.Window>();
        for (int i = 0; i < 560; i++)
            windows.add(window("build-" + i, proof(i % 10 == 0 ? 0 : 3,
                    i % 10 == 0 ? "0" : "7", i % 10 == 0 ? "0" : "9")));
        windows.add(windows.get(0));
        var rawBuilds = new ArrayList<String>();
        var dimensionBuilds = new ArrayList<String>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (var stream = spy(new JobClickHouseStream(settings(), json))) {
            doAnswer(call -> {
                String sql = call.getArgument(1);
                List<String> ids = ids(sql);
                boolean dimensions = sql.contains("dimensionDigest");
                assertThat(ids).isNotEmpty().hasSizeLessThanOrEqualTo(64);
                assertThat((int) call.getArgument(2)).isEqualTo(ids.size() + 1);
                assertThat((long) call.getArgument(3)).isEqualTo(deadline);
                assertThat(sql).doesNotContain("window_start", "tenant_id")
                        .contains("GROUP BY build_id,receipt_id,payload_hash,validation_result")
                        .endsWith(") GROUP BY build_id");
                assertThat(sql.length()).isLessThan(12_000);
                if (dimensions) assertThat(sql).contains(GEO_FIELDS);
                (dimensions ? dimensionBuilds : rawBuilds).addAll(ids);
                Consumer<Map<String, Object>> consumer = call.getArgument(4);
                for (String id : ids)
                    if (Integer.parseInt(id.substring(6)) % 10 != 0)
                        consumer.accept(Map.of("build_id", id, "n", 3,
                                dimensions ? "dimensionDigest" : "digest", dimensions ? "9" : "7"));
                return null;
            }).when(stream).query(eq(REPLICA), anyString(), anyInt(), anyLong(), any());

            assertThat(stream.verify(new ManifestPlan("epoch", windows, List.of(REPLICA)), deadline))
                    .isEqualTo(REPLICA);
            assertThat(rawBuilds).hasSize(560).doesNotHaveDuplicates();
            assertThat(dimensionBuilds).containsExactlyElementsOf(rawBuilds);
            verify(stream, times(18)).query(eq(REPLICA), anyString(), anyInt(), eq(deadline), any());
        }
    }

    @Test
    void missingDuplicateUnselectedAndMismatchedProofRowsStillFailClosed() throws Exception {
        for (String phase : List.of("raw", "dimension")) {
            for (String defect : List.of("missing", "duplicate", "unselected", "digest", "count")) {
                try (var stream = spy(new JobClickHouseStream(settings(), json))) {
                    doAnswer(call -> {
                        boolean dimensions = ((String) call.getArgument(1)).contains("dimensionDigest");
                        boolean broken = dimensions == phase.equals("dimension");
                        Consumer<Map<String, Object>> consumer = call.getArgument(4);
                        if (broken && defect.equals("missing")) return null;
                        Map<String, Object> row = Map.of(
                                "build_id", broken && defect.equals("unselected") ? "other-build" : "build-1",
                                "n", broken && defect.equals("count") ? 4 : 3,
                                dimensions ? "dimensionDigest" : "digest",
                                broken && defect.equals("digest") ? "wrong" : dimensions ? "9" : "7");
                        consumer.accept(row);
                        if (broken && defect.equals("duplicate")) consumer.accept(row);
                        return null;
                    }).when(stream).query(eq(REPLICA), anyString(), anyInt(), anyLong(), any());
                    var plan = new ManifestPlan("epoch", List.of(window("build-1", proof(3, "7", "9"))), List.of(REPLICA));
                    QueryFailure failure = org.junit.jupiter.api.Assertions.assertThrows(QueryFailure.class,
                            () -> stream.verify(plan, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)), phase + ":" + defect);
                    assertThat(failure.code).as(phase + ":" + defect).isEqualTo("NOT_READY");
                }
            }
        }
    }

    @Test
    void malformedConflictingAndOversizedSelectionsFailBeforeRemoteReads() throws Exception {
        try (var stream = spy(new JobClickHouseStream(settings(), json))) {
            var good = window("build-1", proof(3, "7", "9"));
            List<List<ManifestPlan.Window>> invalid = List.of(
                    List.of(window("build-1", Map.of())),
                    List.of(window("build-1", Map.of("n", 3))),
                    List.of(window("b".repeat(65), proof(3, "7", "9"))),
                    List.of(good, window("build-1", proof(3, "7", "different"))));
            for (var windows : invalid) {
                QueryFailure failure = org.junit.jupiter.api.Assertions.assertThrows(QueryFailure.class,
                        () -> stream.verify(new ManifestPlan("epoch", windows, List.of(REPLICA)),
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
                assertThat(failure.code).isEqualTo("NOT_READY");
            }
            var oversized = Collections.nCopies((int) (ManifestPlan.MAX_RANGE / ManifestPlan.WINDOW + 2), good);
            QueryFailure failure = org.junit.jupiter.api.Assertions.assertThrows(QueryFailure.class,
                    () -> stream.verify(new ManifestPlan("epoch", oversized, List.of(REPLICA)),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertThat(failure.code).isEqualTo("TOO_LARGE");
            verify(stream, never()).query(anyString(), anyString(), anyInt(), anyLong(), any());
        }
    }

    private Map<String, Object> proof(int count, String digest, String dimensionDigest) {
        return new LinkedHashMap<>(Map.of("n", count, "digest", digest,
                "dimensionVersion", "geo-v1", "dimensionDigest", dimensionDigest));
    }

    private ManifestPlan.Window window(String build, Map<String, Object> proof) throws Exception {
        return new ManifestPlan.Window(0, build, 1, "{}", 300_000, "v1", "v1", json.writeValueAsString(proof));
    }

    private List<String> ids(String sql) {
        var ids = new ArrayList<String>();
        var matcher = Pattern.compile("'(build-[0-9]+)'").matcher(sql);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private ApiSettings settings() {
        return new ApiSettings("test-token-long-enough-for-analytics", "http://localhost:1",
                "http://localhost:2", REPLICA, "default", "", "test");
    }
}
