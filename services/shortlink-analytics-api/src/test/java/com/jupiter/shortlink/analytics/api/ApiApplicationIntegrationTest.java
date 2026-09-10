package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.*;
import java.net.http.*;

class ApiApplicationIntegrationTest {
    @Test
    void productionContextUsesRealJdbcAndClickHouseAndAuthenticatesBeforeJson() throws Exception {
        try (var context =
                SpringApplication.run(
                        AnalyticsApiApplication.class,
                        "--spring.profiles.active=production",
                        "--spring.config.location=classpath:application-production.properties",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--analytics.internal-token=analytics-api-boot-token-1234567890",
                        "--analytics.clickhouse.urls=http://127.0.0.1:18123",
                        "--analytics.clickhouse.user=shortlink_it",
                        "--analytics.clickhouse.password=shortlink-it-only",
                        "--analytics.clickhouse.database=shortlink_analytics_it",
                        "--analytics.producer-quality.urls=",
                        "--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "--spring.datasource.username=root",
                        "--spring.datasource.password=shortlink-it-only")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/internal/analytics/v1/query"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("invalid json"))
                            .build();
            assertEquals(
                    403,
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.discarding())
                            .statusCode());
            assertEquals(
                    1,
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                            .queryForObject("SELECT 1", Integer.class));
            assertEquals(
                    1,
                    context.getBean(ClickHouseReader.class)
                            .query("http://127.0.0.1:18123", "SELECT 1 value", 1)
                            .size());
            assertNotNull(
                    context.getBean(com.jupiter.shortlink.analytics.api.job.QueryJobRuntime.class));
        }
    }
}
