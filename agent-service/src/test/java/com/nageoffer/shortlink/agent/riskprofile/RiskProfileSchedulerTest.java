package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.ShortLinkAgentApplication;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.scheduler.RiskProfileScheduleConfiguration;
import com.nageoffer.shortlink.agent.riskprofile.scheduler.RiskProfileScheduler;
import com.nageoffer.shortlink.agent.riskprofile.service.RiskProfileBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RiskProfileSchedulerTest {

    @Test
    void schedulerRunsBatchAtClockInstant() {
        RiskProfileBatchService batchService = mock(RiskProfileBatchService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        RiskProfileScheduler scheduler = new RiskProfileScheduler(batchService, clock);

        scheduler.runRiskProfileBatch();

        verify(batchService).runOnce(Instant.parse("2026-07-10T02:00:00Z"));
    }

    @Test
    void schedulerDelayUsesConfiguredBatchIntervalMinutes() throws NoSuchMethodException {
        Method method = RiskProfileScheduler.class.getDeclaredMethod("runRiskProfileBatch");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().getProfile().setBatchIntervalMinutes(120);

        long delayMillis = new RiskProfileScheduleConfiguration(properties).riskProfileScheduleDelayMillis();

        assertThat(scheduled.fixedDelayString()).isEqualTo("#{@riskProfileScheduleDelayMillis}");
        assertThat(scheduled.initialDelayString()).isEqualTo("#{@riskProfileScheduleDelayMillis}");
        assertThat(delayMillis).isEqualTo(7_200_000L);
    }

    @Test
    void applicationEnablesScheduling() {
        assertThat(ShortLinkAgentApplication.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
    }
}
