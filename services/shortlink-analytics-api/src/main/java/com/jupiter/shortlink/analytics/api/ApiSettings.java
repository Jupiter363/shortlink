package com.jupiter.shortlink.analytics.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ApiSettings(
        @Value("${analytics.internal-token}") String token,
        @Value("${analytics.command.url:http://localhost:8003}") String commandUrl,
        @Value("${analytics.worker.url:http://localhost:8012}") String workerUrl,
        @Value("${analytics.clickhouse.urls:http://localhost:8123}") String clickHouseUrls,
        @Value("${analytics.clickhouse.user:default}") String clickHouseUser,
        @Value("${analytics.clickhouse.password:}") String clickHousePassword,
        @Value("${analytics.clickhouse.database:shortlink_analytics}") String clickHouseDatabase) {
    public ApiSettings {
        if (token == null || token.length() < 24)
            throw new IllegalArgumentException(
                    "A configured internal token of at least 24 characters is required");
    }
}
