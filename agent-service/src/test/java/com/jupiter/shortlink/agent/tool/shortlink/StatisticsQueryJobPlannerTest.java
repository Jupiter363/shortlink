package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class StatisticsQueryJobPlannerTest {
    @Test
    void longDateRangeCreatesStableJobWhileSevenDaysRemainSynchronous() {
        Map<String, Object> request =
                new LinkedHashMap<>(
                        Map.of("gid", "g1", "startDate", "2026-07-01", "endDate", "2026-07-07"));
        assertThat(StatisticsQueryJobPlanner.longRange(request, "METRICS")).isEmpty();
        request.put("endDate", "2026-08-01");
        var first = StatisticsQueryJobPlanner.longRange(request, "METRICS").orElseThrow();
        assertThat(first.toolName()).isEqualTo("submit_statistics_query_job");
        assertThat(first)
                .isEqualTo(StatisticsQueryJobPlanner.longRange(request, "METRICS").orElseThrow());
        assertThat(first.arguments()).doesNotContainKeys("tenantId", "username", "authVersion");
    }

    @Test
    void continuationReturnsOneReadInstructionAndCannotSmuggleAPath() {
        assertThat(
                        StatisticsQueryJobPlanner.continuation("查询 jobId=job-1 pageIndex=2")
                                .orElseThrow()
                                .arguments())
                .containsEntry("jobId", "job-1")
                .containsEntry("pageIndex", 2);
        assertThat(StatisticsQueryJobPlanner.continuation("jobId=../secret")).isEmpty();
    }

    @Test
    void pendingIsExplicitAndIsNeverSummarizedAsCompletedStatistics() {
        var answer =
                StatisticsQueryJobPlanner.statusAnswer(
                                List.of(
                                        Map.of(
                                                "success",
                                                true,
                                                "data",
                                                Map.of("jobId", "job-1", "state", "QUEUED"))))
                        .orElseThrow();
        assertThat(answer).contains("jobId=job-1", "PENDING", "尚未完成").doesNotContain("PV=0");
    }
}
