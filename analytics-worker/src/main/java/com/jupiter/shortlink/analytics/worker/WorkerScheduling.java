package com.jupiter.shortlink.analytics.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class WorkerScheduling {
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var s = new ThreadPoolTaskScheduler();
        s.setPoolSize(8);
        s.setThreadNamePrefix("analytics-worker-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        s.setRemoveOnCancelPolicy(true);
        return s;
    }
}
