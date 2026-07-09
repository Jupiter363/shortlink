package com.nageoffer.shortlink.agent.securityriskagent.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRiskModelTest {

    @Test
    void riskAnalysisContextCopiesCollectionsForStableInternalState() {
        RiskAnalysisContext context = new RiskAnalysisContext(
                "session-1",
                "zhangsan",
                "risk message",
                List.of(Map.of("name", "get_group_stats")),
                List.of()
        );

        assertThat(context.sessionId()).isEqualTo("session-1");
        assertThat(context.toolExecutions()).containsExactly(Map.of("name", "get_group_stats"));
    }

    @Test
    void riskFeatureSnapshotKeepsSourceToolArgumentsAndStats() {
        RiskFeatureSnapshot snapshot = new RiskFeatureSnapshot(
                "get_group_stats",
                Map.of("gid", "g1"),
                Map.of("pv", 100)
        );

        assertThat(snapshot.sourceTool()).isEqualTo("get_group_stats");
        assertThat(snapshot.arguments()).containsEntry("gid", "g1");
        assertThat(snapshot.stats()).containsEntry("pv", 100);
    }
}
