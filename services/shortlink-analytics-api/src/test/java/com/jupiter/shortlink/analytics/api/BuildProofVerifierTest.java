package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildProofVerifierTest {
    private static final String REPLICA = "http://replica.test:8123";
    private final ObjectMapper json = new ObjectMapper();
    private final ClickHouseReader ch = mock(ClickHouseReader.class);
    private final BuildProofVerifier verifier = new BuildProofVerifier(ch, json);

    @Test
    void twoThousandBuildsVerifyBothProofsInSixteenBoundedReadsIncludingEmptyBuilds() throws Exception {
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int i = 0; i < 2000; i++) manifests.add(manifest("build-" + i,
                proof(i % 10 == 0 ? 0 : 3, i % 10 == 0 ? "0" : "7", i % 10 == 0 ? "0" : "9")));
        Set<String> rawBuilds = new HashSet<>(), dimensionBuilds = new HashSet<>();
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            String sql = call.getArgument(1);
            List<String> builds = ids(sql);
            boolean dimensions = sql.contains("dimensionDigest");
            assertTrue(builds.size() <= BuildProofVerifier.BATCH_SIZE);
            assertEquals(builds.size() + 1, (int) call.getArgument(2));
            assertTrue(sql.startsWith("SELECT build_id,count() n,toString(groupBitXor(cityHash64("));
            assertTrue(sql.endsWith(") GROUP BY build_id"));
            assertFalse(sql.contains("window_start"), "The proof must cover the whole immutable build");
            assertFalse(sql.contains("tenant_id"), "The proof must precede authorization-scoped facts");
            assertTrue(sql.contains("GROUP BY build_id,receipt_id,payload_hash,validation_result"));
            if (dimensions) {
                assertTrue(sql.contains("receipt_id,payload_hash,validation_result,country,province,city,network,geo_status,geo_version"));
                dimensionBuilds.addAll(builds);
            } else rawBuilds.addAll(builds);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String build : builds)
                if (Integer.parseInt(build.substring(6)) % 10 != 0)
                    rows.add(row(build, 3, dimensions ? "dimensionDigest" : "digest", dimensions ? "9" : "7"));
            return rows;
        });
        verifier.verify(REPLICA, manifests);
        assertEquals(2000, rawBuilds.size());
        assertEquals(rawBuilds, dimensionBuilds);
        verify(ch, times(16)).query(eq(REPLICA), anyString(), anyInt());
    }

    @Test
    void legacyProofsNeedOnlyOneReadPerBatch() throws Exception {
        var manifests = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2000; i++) manifests.add(manifest("build-" + i, Map.of("n", 0, "digest", "0")));
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(List.of());
        verifier.verify(REPLICA, manifests);
        verify(ch, times(8)).query(eq(REPLICA), anyString(), anyInt());
        verify(ch, never()).query(anyString(), contains("dimensionDigest"), anyInt());
    }

    @Test
    void repeatedWindowsOfOneBuildShareVerificationOnlyWhenTheirProofsAgree() throws Exception {
        var first = manifest("build-1", proof(3, "7", "9"));
        var second = new LinkedHashMap<>(first);
        first.put("window_start", 0L);
        second.put("window_start", 300_000L);
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            assertEquals(List.of("build-1"), ids(call.getArgument(1)));
            return List.of(((String) call.getArgument(1)).contains("dimensionDigest")
                    ? row("build-1", 3L, "dimensionDigest", "9") : row("build-1", 3L, "digest", "7"));
        });
        verifier.verify(REPLICA, List.of(first, second));
        verify(ch, times(2)).query(eq(REPLICA), anyString(), eq(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"n", "digest", "dimensionDigest", "dimensionVersion"})
    void conflictingDuplicateBuildProofsFailBeforeAnyRead(String field) throws Exception {
        var original = proof(3, "7", "9");
        var changed = new LinkedHashMap<>(original);
        changed.put(field, field.equals("n") ? 4 : "different");
        var manifests = List.of(manifest("build-1", original), manifest("build-1", changed));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
        verifyNoInteractions(ch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"n\":0}", "{\"digest\":\"0\"}"})
    void malformedOrIncompleteManifestProofsFailClosed(String raw) {
        var manifests = List.of(Map.<String, Object>of("build_id", "build-1", "coverage_proof", raw));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
        verifyNoInteractions(ch);
    }

    @Test
    void emptyBuildsWithNoGroupedRowsStillVerifyBothZeroProofs() throws Exception {
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(List.of());
        verifier.verify(REPLICA, List.of(manifest("build-1", proof(0, "0", "0"))));
        verify(ch, times(2)).query(eq(REPLICA), anyString(), eq(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "dimension"})
    void zeroRowCountsStillRequireTheExpectedZeroDigest(String kind) throws Exception {
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(List.of());
        var manifests = List.of(manifest("build-1", proof(0, kind.equals("raw") ? "wrong" : "0",
                kind.equals("dimension") ? "wrong" : "0")));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "count", "digest"})
    void missingOrIncorrectRawProofsAreRejected(String defect) throws Exception {
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(defect.equals("missing") ? List.of()
                : List.of(row("build-1", defect.equals("count") ? 4 : 3, "digest", defect.equals("digest") ? "8" : "7")));
        var manifests = List.of(manifest("build-1", proof(3, "7", "9")));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
        verify(ch, never()).query(anyString(), contains("dimensionDigest"), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "count", "digest", "version"})
    void matchingRawProofNeverExcusesMissingOrIncorrectDimensionProofs(String defect) throws Exception {
        var proof = proof(3, "7", "9");
        if (defect.equals("version")) proof.put("dimensionVersion", "geo-v2");
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            if (!((String) call.getArgument(1)).contains("dimensionDigest")) return List.of(row("build-1", 3, "digest", "7"));
            return defect.equals("missing") ? List.of() : List.of(row("build-1", defect.equals("count") ? 4 : 3,
                    "dimensionDigest", defect.equals("digest") ? "wrong" : "9"));
        });
        var manifests = List.of(manifest("build-1", proof));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dimensionDigest", "dimensionVersion"})
    void aPartiallyVersionedProofCannotFallBackToLegacyVerification(String missing) throws Exception {
        var proof = proof(0, "0", "0");
        proof.remove(missing);
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(List.of());
        var manifests = List.of(manifest("build-1", proof));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
        verify(ch).query(eq(REPLICA), contains("dimensionDigest"), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"raw", "dimension"})
    void theLastBuildInTheLastBatchIsVerified(String corruptedProof) throws Exception {
        var manifests = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 2000; i++) manifests.add(manifest("build-" + i, proof(3, "7", "9")));
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            String sql = call.getArgument(1);
            boolean dimensions = sql.contains("dimensionDigest");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String build : ids(sql)) {
                boolean corrupt = build.equals("build-1999") && dimensions == corruptedProof.equals("dimension");
                rows.add(row(build, 3, dimensions ? "dimensionDigest" : "digest", corrupt ? "wrong" : dimensions ? "9" : "7"));
            }
            return rows;
        });
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
        verify(ch, times(corruptedProof.equals("raw") ? 15 : 16)).query(eq(REPLICA), anyString(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "no-id", "null-row", "extra-row"})
    void rawResponsesMustContainAtMostOneKnownRowPerBuild(String defect) throws Exception {
        var manifests = List.of(manifest("build-1", proof(3, "7", "9")), manifest("build-2", proof(0, "0", "0")));
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenReturn(invalidRows(defect, "digest"));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "no-id", "null-row", "extra-row"})
    void dimensionResponsesMustContainAtMostOneKnownRowPerBuild(String defect) throws Exception {
        var manifests = List.of(manifest("build-1", proof(3, "7", "9")), manifest("build-2", proof(0, "0", "0")));
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call ->
                ((String) call.getArgument(1)).contains("dimensionDigest") ? invalidRows(defect, "dimensionDigest")
                        : List.of(row("build-1", 3, "digest", "7")));
        assertEquals("NOT_READY", assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)).code);
    }

    @Test
    void dimensionBatchesIncludeOnlyBuildsRequiringVersionedProof() throws Exception {
        var manifests = List.of(manifest("build-1", Map.of("n", 0, "digest", "0")), manifest("build-2", proof(0, "0", "0")));
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            String sql = call.getArgument(1);
            assertEquals(sql.contains("dimensionDigest") ? List.of("build-2") : List.of("build-1", "build-2"), ids(sql));
            return List.of();
        });
        verifier.verify(REPLICA, manifests);
        verify(ch, times(2)).query(eq(REPLICA), anyString(), anyInt());
    }

    @Test
    void buildIdsRemainSqlQuotedAndProofQueriesHaveNoSelectedWindowPredicate() throws Exception {
        String build = "build-'\\unsafe";
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenAnswer(call -> {
            String sql = call.getArgument(1);
            assertTrue(sql.contains("build_id IN (" + ClickHouseReader.quote(build) + ")"));
            assertFalse(sql.contains("window_start"));
            return List.of();
        });
        verifier.verify(REPLICA, List.of(manifest(build, Map.of("n", 0, "digest", "0"))));
    }

    @Test
    void transportFailuresAreNotReplacedByEmptyBuildProofs() throws Exception {
        var failure = new QueryFailure("UNAVAILABLE", "ClickHouse memory capacity exceeded");
        when(ch.query(eq(REPLICA), anyString(), anyInt())).thenThrow(failure);
        var manifests = List.of(manifest("build-1", Map.of("n", 0, "digest", "0")));
        assertSame(failure, assertThrows(QueryFailure.class, () -> verifier.verify(REPLICA, manifests)));
    }

    private List<Map<String, Object>> invalidRows(String defect, String digest) {
        Map<String, Object> good = row("build-1", 3, digest, digest.equals("digest") ? "7" : "9");
        return switch (defect) {
            case "duplicate" -> List.of(good, good);
            case "unknown" -> List.of(good, row("not-selected", 0, digest, "0"));
            case "no-id" -> List.of(Map.of("n", 3, digest, "7"));
            case "null-row" -> java.util.Arrays.asList(good, null);
            case "extra-row" -> List.of(good, row("build-2", 0, digest, "0"), row("not-selected", 0, digest, "0"));
            default -> throw new IllegalArgumentException();
        };
    }

    private Map<String, Object> manifest(String build, Map<String, Object> proof) throws Exception {
        return new LinkedHashMap<>(Map.of("build_id", build, "coverage_proof", json.writeValueAsString(proof)));
    }

    private Map<String, Object> proof(int count, String digest, String dimensionDigest) {
        return new LinkedHashMap<>(Map.of("n", count, "digest", digest, "dimensionVersion", "geo-v1", "dimensionDigest", dimensionDigest));
    }

    private Map<String, Object> row(String build, Object count, String digestKey, String digest) {
        return Map.of("build_id", build, "n", count, digestKey, digest);
    }

    private List<String> ids(String sql) {
        var ids = new ArrayList<String>();
        var matcher = Pattern.compile("'(build-[0-9]+)'").matcher(sql);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }
}
