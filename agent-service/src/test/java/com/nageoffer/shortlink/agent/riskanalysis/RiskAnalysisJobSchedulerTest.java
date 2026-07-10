package com.nageoffer.shortlink.agent.riskanalysis;

import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobScheduler;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RiskAnalysisJobSchedulerTest {

    @Test
    void schedulerRunsOneAvailableJobPerTick() throws NoSuchMethodException {
        RiskAnalysisJobWorker worker = mock(RiskAnalysisJobWorker.class);
        RiskAnalysisJobScheduler scheduler = new RiskAnalysisJobScheduler(worker);

        scheduler.runNextJob();

        verify(worker).runNext();
        Method method = RiskAnalysisJobScheduler.class.getDeclaredMethod("runNextJob");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${short-link.agent.risk.analysis.worker-interval-millis:5000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${short-link.agent.risk.analysis.worker-interval-millis:5000}");
    }
}
