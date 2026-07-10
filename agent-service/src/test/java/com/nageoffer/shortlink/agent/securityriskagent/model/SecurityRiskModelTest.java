package com.nageoffer.shortlink.agent.securityriskagent.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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

    @Test
    void riskAnalysisInputRoundTripsThroughGraphSafeMapState() {
        RiskAnalysisInput input = new RiskAnalysisInput(
                "risk-profile:batch-001",
                "gid-001",
                LocalDateTime.of(2026, 7, 10, 10, 0),
                List.of(new RiskProfileTargetRef("nurl.ink", "abc123"))
        );

        Map<String, Object> stateValue = input.toStateValue();

        assertThat(stateValue)
                .containsEntry("batchId", "risk-profile:batch-001")
                .containsEntry("gid", "gid-001")
                .containsEntry("profileWindowEnd", "2026-07-10T10:00");
        assertThat(stateValue.get("candidates")).isInstanceOf(List.class);
        assertThat(RiskAnalysisInput.fromStateValue(stateValue)).contains(input);
    }
}
