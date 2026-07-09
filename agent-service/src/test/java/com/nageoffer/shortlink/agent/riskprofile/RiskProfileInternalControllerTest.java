package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.harness.security.InternalAgentApiFilter;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.api.RiskProfileInternalController;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchResult;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

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
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        when(batchService.runOnce(batchNow)).thenReturn(new RiskProfileBatchResult(
                batchNow,
                3,
                3,
                Map.of()
        ));
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        properties.getSecurity().setInternalTokenDevMode(true);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new RiskProfileInternalController(
                        batchService,
                        Clock.fixed(batchNow, ZoneId.of("Asia/Shanghai"))
                ))
                .addFilters(new InternalAgentApiFilter(properties))
                .build();

        mockMvc.perform(post("/internal/short-link-agent/v1/risk/profiles/run-once")
                        .header("X-Agent-Username", "e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scannedShortLinks").value(3))
                .andExpect(jsonPath("$.data.generatedProfiles").value(3));

        ArgumentCaptor<Instant> batchNowCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(batchService).runOnce(batchNowCaptor.capture());
        assertThat(batchNowCaptor.getValue()).isEqualTo(batchNow);
    }
}
