package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignStatisticsQueryPlanTest {
    @Test
    void planIsStableAcrossInputOrderAndLabelsDoNotChangeIdentity() {
        var first = CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "alpha", "label", "A"), Map.of("gid", "beta")),
                List.of(period("2026-09-07", "2026-09-13", "本周")));
        var reordered = CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "beta"), Map.of("gid", "alpha", "label", "changed")),
                List.of(period("2026-09-07", "2026-09-13", "changed")));

        assertThat(first.planId()).isEqualTo(reordered.planId());
        assertThat(first.combinations()).isEqualTo(2);
        assertThat(first.queries()).extracting(CampaignStatisticsQueryPlan.Query::key)
                .containsExactly("alpha||2026-09-07|2026-09-13", "beta||2026-09-07|2026-09-13");
        assertThat(first.queries().get(0).arguments()).doesNotContainKey("label");
    }

    @Test
    void canonicalizesUrlAndKeepsQueryArgumentsImmutable() {
        var plan = CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "alpha", "fullShortUrl", "https://example.test/a")),
                List.of(period("2026-09-01", "2026-09-01"), period("2026-09-02", "2026-09-02")));
        assertThat(plan.queries().get(0).arguments()).containsEntry("fullShortUrl", "example.test/a");
        assertThatThrownBy(() -> plan.queries().get(0).arguments().put("gid", "foreign"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateAndOutOfRangePlansFailClosed() {
        assertThatThrownBy(() -> CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "alpha"), Map.of("gid", "alpha")), List.of(period("2026-09-01", "2026-09-01"))))
                .hasMessage("Duplicate comparison scope");
        assertThatThrownBy(() -> CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "alpha")), List.of(period("2026-09-01", "2026-09-01"))))
                .hasMessage("Comparison requires two to sixteen object/period combinations");
        assertThatThrownBy(() -> CampaignStatisticsQueryPlan.create(
                List.of(Map.of("gid", "alpha"), Map.of("gid", "beta")), List.of(period("2026-01-01", "2026-07-01"))))
                .hasMessage("Statistics periods must contain one to 180 inclusive days");
    }

    @Test
    void moreThanSixteenCombinationsAreRejectedBeforeQueriesAreBuilt() {
        var scopes = java.util.stream.IntStream.range(0, 5).mapToObj(i -> Map.<String, Object>of("gid", "g" + i)).toList();
        var periods = java.util.stream.IntStream.range(0, 4).mapToObj(i -> period("2026-09-0" + (i + 1), "2026-09-0" + (i + 1))).toList();
        assertThatThrownBy(() -> CampaignStatisticsQueryPlan.create(scopes, periods))
                .hasMessage("Comparison requires two to sixteen object/period combinations");
    }

    private static Map<String, Object> period(String start, String end) { return period(start, end, null); }
    private static Map<String, Object> period(String start, String end, String label) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("startDate", start); result.put("endDate", end); if (label != null) result.put("label", label); return result;
    }
}
