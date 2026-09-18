package com.jupiter.shortlink.analytics.api.job;

import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.analytics.api.CanonicalBuildPredicate;
import com.jupiter.shortlink.analytics.api.QueryFailure;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ManifestPlanTest {
    private static final long START = 1_800_000_000_000L;

    @Test
    void aFull180DayPlanWithFrequentBuildChangesUsesTheSharedExactTupleSelection() {
        var windows = windows(180 * 288, 36);
        var plan = new ManifestPlan("epoch", windows, List.of("replica"));
        String predicate = plan.predicate();
        assertTrue(predicate.startsWith("(build_id,window_start) IN ("));
        assertFalse(predicate.contains(" OR "));
        assertEquals(180L * 288, Pattern.compile("\\('[^']*',[0-9]+\\)").matcher(predicate).results().count());
        assertTrue(predicate.length() <= 4 * 1024 * 1024);
        assertEquals(CanonicalBuildPredicate.predicate(windows.stream()
                .map(window -> new CanonicalBuildPredicate.Selection(window.build(), window.start())).toList()), predicate);
        assertTrue(predicate.contains("('" + windows.get(0).build() + "'," + START + ")"));
        var last = windows.get(windows.size() - 1);
        assertTrue(predicate.contains("('" + last.build() + "'," + last.start() + ")"));
    }

    @Test
    void aFull180DayPlanUsingOneBuildRetainsOneCompactRange() {
        var windows = new ArrayList<ManifestPlan.Window>();
        for (int i = 0; i < 180 * 288; i++) windows.add(window("one-build", START + i * ManifestPlan.WINDOW));
        String predicate = new ManifestPlan("epoch", windows, List.of("replica")).predicate();
        assertEquals("((build_id='one-build' AND window_start>=" + START + " AND window_start<"
                + (START + ManifestPlan.MAX_RANGE) + "))", predicate);
        assertTrue(predicate.length() < 150);
    }

    @Test
    void sevenDailyBuildsStillUseSevenCompactRanges() {
        var windows = new ArrayList<ManifestPlan.Window>();
        for (int day = 0; day < 7; day++)
            for (int index = 0; index < 288; index++)
                windows.add(window("day-" + day, START + (day * 288L + index) * ManifestPlan.WINDOW));
        String predicate = new ManifestPlan("epoch", windows, List.of("replica")).predicate();
        assertFalse(predicate.contains(" IN "));
        assertEquals(6, Pattern.compile(" OR ").matcher(predicate).results().count());
        assertTrue(predicate.length() < 1000);
    }

    @Test
    void sparseMixedBuildsDoNotSelectTheOtherBuildsVersionOrAnUnselectedGap() {
        var windows = new ArrayList<ManifestPlan.Window>();
        for (int i = 0; i < 20; i++) windows.add(window(i % 2 == 0 ? "old" : "new", i * 2L * ManifestPlan.WINDOW));
        String predicate = new ManifestPlan("epoch", windows, List.of("replica")).predicate();
        assertTrue(predicate.contains("('old',0)"));
        assertTrue(predicate.contains("('new',600000)"));
        assertFalse(predicate.contains("('new',0)"));
        assertFalse(predicate.contains("('old',600000)"));
        assertFalse(predicate.contains(",300000)"));
        assertFalse(predicate.contains(" OR "));
    }

    @Test
    void theExistingFourMiBSelectionBudgetIsStillEnforcedForLongBuildIds() {
        var windows = windows(180 * 288, 64);
        var plan = new ManifestPlan("epoch", windows, List.of("replica"));
        var failure = assertThrows(QueryFailure.class, plan::predicate);
        assertEquals("TOO_LARGE", failure.code);
        assertEquals("Frozen build selection exceeds the query budget", failure.getMessage());
    }

    private List<ManifestPlan.Window> windows(int count, int buildLength) {
        String prefix = "b".repeat(buildLength);
        var windows = new ArrayList<ManifestPlan.Window>(count);
        for (int i = 0; i < count; i++) {
            String index = Integer.toString(i);
            String build = prefix.substring(index.length()) + index;
            windows.add(window(build, START + i * ManifestPlan.WINDOW));
        }
        return windows;
    }

    private ManifestPlan.Window window(String build, long start) {
        return new ManifestPlan.Window(start, build, 1, "{}", start + ManifestPlan.WINDOW,
                "click-v1", "test", "{}");
    }
}
