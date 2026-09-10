package com.jupiter.shortlink.analytics.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AnalyticsWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsWorkerApplication.class, args);
    }
}
