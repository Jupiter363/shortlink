package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CanonicalBuildPredicateTest {
    private static final long WINDOW = AnalyticsQueryService.WINDOW;

    @Test
    void aWeekWithOneBuildPerWindowUsesAnExactTupleSetWithoutBooleanExpansion() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 0; i < 2016; i++) {
            manifests.add(row("build-" + i, i * WINDOW));
            expected.add("('build-" + i + "'," + i * WINDOW + ")");
        }
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertTrue(predicate.startsWith("(build_id,window_start) IN ("));
        assertFalse(predicate.contains(" OR "));
        assertFalse(predicate.contains(" AND "));
        assertEquals(expected, tuples(predicate));
        assertTrue(predicate.length() < String.join(" OR ", AnalyticsQueryService.canonicalClauses(manifests)).length() / 2);
    }

    @Test
    void alternatingPublishedVersionsRemainBoundToTheirExactWindows() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            String build = i % 2 == 0 ? "old" : "new";
            manifests.add(row(build, i * WINDOW));
            expected.add("('" + build + "'," + i * WINDOW + ")");
        }
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertEquals(expected, tuples(predicate));
        assertFalse(predicate.contains("('old',300000)"));
        assertFalse(predicate.contains("('new',0)"));
        assertFalse(predicate.contains("build_id IN"));
        assertFalse(predicate.contains("window_start IN"));
    }

    @Test
    void gapsInOneBuildNeverBecomeAContinuousSelectedRange() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            manifests.add(row("build", i * 2L * WINDOW));
            expected.add("('build'," + i * 2L * WINDOW + ")");
        }
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertEquals(expected, tuples(predicate));
        assertFalse(predicate.contains("('build',300000)"));
        assertFalse(predicate.contains("window_start>="));
    }

    @Test
    void repeatedIdenticalSelectionsDoNotMultiplyTheTupleSet() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            manifests.add(row("build-" + i, i * WINDOW));
            manifests.add(row("build-" + i, i * WINDOW));
        }
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertEquals(20, tuples(predicate).size());
        var matcher = Pattern.compile("\\('[^']*',[0-9]+\\)").matcher(predicate);
        assertEquals(20, matcher.results().count());
    }

    @Test
    void aWeekOfDailyBuildsKeepsItsSevenCompactRanges() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int day = 0; day < 7; day++)
            for (int window = 0; window < 288; window++)
                manifests.add(row("day-" + day, (day * 288L + window) * WINDOW));
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertEquals("(" + String.join(" OR ", AnalyticsQueryService.canonicalClauses(manifests)) + ")", predicate);
        assertFalse(predicate.contains(" IN "));
        assertEquals(6, Pattern.compile(" OR ").matcher(predicate).results().count());
    }

    @Test
    void theCompactFormHasAFixedUpperBound() {
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int i = 0; i < CanonicalBuildPredicate.MAX_COMPACT_RANGES; i++)
            manifests.add(row("build-" + i, i * WINDOW));
        String compact = AnalyticsQueryService.canonicalPredicate(manifests);
        assertEquals(15, Pattern.compile(" OR ").matcher(compact).results().count());
        manifests.add(row("one-more-build", manifests.size() * WINDOW));
        String tuples = AnalyticsQueryService.canonicalPredicate(manifests);
        assertTrue(tuples.startsWith("(build_id,window_start) IN ("));
        assertFalse(tuples.contains(" OR "));
    }

    @Test
    void tupleBuildIdsUseTheSameSqlEscapingAsTheOtherClickHousePredicates() {
        String build = "build-'\\quoted";
        List<Map<String, Object>> manifests = new ArrayList<>();
        for (int i = 0; i < 20; i++) manifests.add(row(build, i * 2L * WINDOW));
        String predicate = AnalyticsQueryService.canonicalPredicate(manifests);
        assertTrue(predicate.contains("(" + ClickHouseReader.quote(build) + ",0)"));
        assertTrue(predicate.contains("(" + ClickHouseReader.quote(build) + ",11400000)"));
    }

    @Test
    void anEmptySelectionCannotAccidentallySelectAllBuilds() {
        assertEquals("0", AnalyticsQueryService.canonicalPredicate(List.of()));
    }

    private static Set<String> tuples(String predicate) {
        Set<String> selected = new LinkedHashSet<>();
        var matcher = Pattern.compile("\\('[^']*',[0-9]+\\)").matcher(predicate);
        while (matcher.find()) selected.add(matcher.group());
        return selected;
    }

    private static Map<String, Object> row(String build, long window) {
        return Map.of("build_id", build, "window_start", window);
    }
}
