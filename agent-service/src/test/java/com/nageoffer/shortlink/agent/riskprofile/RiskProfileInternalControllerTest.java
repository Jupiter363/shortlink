package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.harness.security.InternalAgentApiFilter;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.api.RiskProfileInternalController;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatch;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchCoordinator;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchFailure;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskProfileInternalControllerTest {

    @Test
    void runOnceEndpointTriggersRiskProfileBatchWithInternalAuth() throws Exception {
        Instant batchNow = Instant.parse("2026-07-10T02:00:00Z");
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        when(coordinator.runOnce(batchNow)).thenReturn(new RiskProfileBatch(
                "risk-profile:" + batchNow.getEpochSecond(),
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                RiskProfileBatchStatus.SUCCEEDED,
                "",
                null,
                3,
                3,
                0,
                1,
                List.of(),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 1)
        ));
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        properties.getSecurity().setInternalTokenDevMode(true);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RiskProfileInternalController(
                        coordinator,
                        Clock.fixed(batchNow, ZoneId.of("Asia/Shanghai"))
                ))
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(post("/internal/short-link-agent/v1/risk/profiles/run-once")
                        .header("X-Agent-Username", "e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.scannedCount").value(3))
                .andExpect(jsonPath("$.data.generatedCount").value(3))
                .andExpect(jsonPath("$.data.scannedShortLinks").value(3))
                .andExpect(jsonPath("$.data.generatedProfiles").value(3))
                .andExpect(jsonPath("$.data.batchTime").exists())
                .andExpect(jsonPath("$.data.analysisJobCount").value(1));

        ArgumentCaptor<Instant> batchNowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(coordinator).runOnce(batchNowCaptor.capture());
        assertThat(batchNowCaptor.getValue()).isEqualTo(batchNow);
    }

    @Test
    void runOnceEndpointReturnsBusinessFailureForFailedBatch() throws Exception {
        Instant batchNow = Instant.parse("2026-07-10T02:00:00Z");
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        when(coordinator.runOnce(batchNow)).thenReturn(new RiskProfileBatch(
                "risk-profile:" + batchNow.getEpochSecond(),
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                RiskProfileBatchStatus.FAILED,
                "",
                null,
                3,
                0,
                1,
                0,
                List.of(new RiskProfileBatchFailure(
                        "nurl.ink/abc123",
                        "STATS_SOURCE_FAILED",
                        "Risk stats API request failed"
                )),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 1)
        ));
        MockMvc mockMvc = mockMvc(coordinator, batchNow);

        mockMvc.perform(post("/internal/short-link-agent/v1/risk/profiles/run-once")
                        .header("X-Agent-Username", "e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RISK_PROFILE_BATCH_FAILED"))
                .andExpect(jsonPath("$.message").value("Risk profile batch failed"))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.failures[0].errorCode").value("STATS_SOURCE_FAILED"));
    }

    @Test
    void runOnceEndpointReportsPartialSuccessWithoutDiscardingBatchData() throws Exception {
        Instant batchNow = Instant.parse("2026-07-10T02:00:00Z");
        RiskProfileBatchCoordinator coordinator = mock(RiskProfileBatchCoordinator.class);
        when(coordinator.runOnce(batchNow)).thenReturn(new RiskProfileBatch(
                "risk-profile:" + batchNow.getEpochSecond(),
                LocalDateTime.of(2026, 7, 10, 8, 0),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                RiskProfileBatchStatus.PARTIAL_SUCCESS,
                "",
                null,
                3,
                2,
                1,
                1,
                List.of(new RiskProfileBatchFailure(
                        "nurl.ink/failed",
                        "STATS_SOURCE_FAILED",
                        "Risk stats API request failed"
                )),
                LocalDateTime.of(2026, 7, 10, 10, 0),
                LocalDateTime.of(2026, 7, 10, 10, 1)
        ));
        MockMvc mockMvc = mockMvc(coordinator, batchNow);

        mockMvc.perform(post("/internal/short-link-agent/v1/risk/profiles/run-once")
                        .header("X-Agent-Username", "e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.message").value("Risk profile batch completed with partial failures"))
                .andExpect(jsonPath("$.data.status").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.data.scannedCount").value(3))
                .andExpect(jsonPath("$.data.generatedCount").value(2))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.analysisJobCount").value(1))
                .andExpect(jsonPath("$.data.failures.length()").value(1))
                .andExpect(jsonPath("$.data.failures[0].errorCode").value("STATS_SOURCE_FAILED"));
    }

    private MockMvc mockMvc(RiskProfileBatchCoordinator coordinator, Instant batchNow) {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        properties.getSecurity().setInternalTokenDevMode(true);
        return MockMvcBuilders
                .standaloneSetup(new RiskProfileInternalController(
                        coordinator,
                        Clock.fixed(batchNow, ZoneId.of("Asia/Shanghai"))
                ))
                .addFilters(new InternalAgentApiFilter(properties))
                .build();
    }
}
