package com.jupiter.shortlink.analytics.api;

import static com.jupiter.shortlink.analytics.api.ClickHouseReader.quote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** SQL selection shared by synchronous snapshots and frozen asynchronous query plans. */
public final class CanonicalBuildPredicate {
    static final int MAX_COMPACT_RANGES = 16;
    private static final long WINDOW = 300_000L;

    private CanonicalBuildPredicate() {}

    public record Selection(String buildId, long windowStart) {}

    /** Keep short coalesced ranges cheap; use an exact set instead of an unbounded boolean tree. */
    public static String predicate(List<Selection> selected) {
        if (selected.isEmpty()) return "0";
        List<String> ranges = ranges(selected);
        if (ranges.size() <= MAX_COMPACT_RANGES) return "(" + String.join(" OR ", ranges) + ")";
        Set<String> tuples = new LinkedHashSet<>();
        for (Selection selection : selected)
            tuples.add("(" + quote(selection.buildId()) + "," + selection.windowStart() + ")");
        return "(build_id,window_start) IN (" + String.join(",", tuples) + ")";
    }

    /** Coalesce adjacent windows of the same published build, leaving revisions and gaps intact. */
    static List<String> ranges(List<Selection> selected) {
        Map<String, SortedSet<Long>> builds = new LinkedHashMap<>();
        for (Selection selection : selected)
            builds.computeIfAbsent(selection.buildId(), ignored -> new TreeSet<>()).add(selection.windowStart());
        List<String> ranges = new ArrayList<>();
        builds.forEach((build, windows) -> {
            long first = -1, previous = -1;
            for (long window : windows) {
                if (first >= 0 && window != previous + WINDOW) {
                    ranges.add(range(build, first, previous + WINDOW));
                    first = window;
                } else if (first < 0) first = window;
                previous = window;
            }
            if (first >= 0) ranges.add(range(build, first, previous + WINDOW));
        });
        return ranges;
    }

    private static String range(String build, long start, long end) {
        return "(build_id=" + quote(build) + " AND window_start>=" + start + " AND window_start<" + end + ")";
    }
}
