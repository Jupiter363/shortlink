package com.jupiter.shortlink.analytics.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.*;
import java.net.http.*;

class WorkerApplicationIntegrationTest {
    @Test
    void productionContextBindsRealInfrastructureAndAuthenticatesBeforeJson() throws Exception {
        var settings =
                new WorkerSettings(
                        "localhost:19092",
                        "analytics-boot-it-token-1234567890",
                        "analytics-integration-hash-key-12345678901234567890",
                        "http://127.0.0.1:19000",
                        "shortlink-it",
                        "shortlink-it-only",
                        "shortlink-analytics-identity-v2-it",
                        "http://127.0.0.1:18123",
                        "shortlink_it",
                        "shortlink-it-only",
                        "shortlink_analytics_it");
        var minio =
                MinioClient.builder()
                        .endpoint(settings.objectEndpoint())
                        .credentials(settings.objectAccessKey(), settings.objectSecretKey())
                        .build();
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(settings.bucket()).build()))
            minio.makeBucket(MakeBucketArgs.builder().bucket(settings.bucket()).build());
        var ds =
                new DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "root",
                        "shortlink-it-only");
        new ControlLedger(
                        new JdbcTemplate(ds),
                        new DataSourceTransactionManager(ds),
                        new ObjectArchive(settings))
                .beginRecovery();
        try (var context =
                SpringApplication.run(
                        AnalyticsWorkerApplication.class,
                        "--spring.profiles.active=production",
                        "--spring.config.location=classpath:application-production.properties",
                        "--server.port=0",
                        "--management.server.port=0",
                        "--analytics.internal-token=analytics-boot-it-token-1234567890",
                        "--analytics.hash-key=analytics-integration-hash-key-12345678901234567890",
                        "--analytics.kafka.bootstrap=localhost:19092",
                        "--analytics.s3.endpoint=http://127.0.0.1:19000",
                        "--analytics.s3.access-key=shortlink-it",
                        "--analytics.s3.secret-key=shortlink-it-only",
                        "--analytics.s3.bucket=shortlink-analytics-identity-v2-it",
                        "--analytics.clickhouse.urls=http://127.0.0.1:18123",
                        "--analytics.clickhouse.user=shortlink_it",
                        "--analytics.clickhouse.password=shortlink-it-only",
                        "--analytics.clickhouse.database=shortlink_analytics_it",
                        "--analytics.producer-quality.urls=",
                        "--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/shortlink_analytics_control_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "--spring.datasource.username=root",
                        "--spring.datasource.password=shortlink-it-only",
                        "--analytics.archive.poll-delay=3600000",
                        "--analytics.repair.poll-delay=3600000",
                        "--analytics.rebuild.poll-delay=3600000",
                        "--analytics.recovery.poll-delay=3600000")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newHttpClient();
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + port
                                                    + "/internal/analytics/v1/worker/readiness"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("invalid json"))
                            .build();
            assertEquals(
                    403, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertNotNull(context.getBean(ArchiveConsumer.class));
            assertNotNull(context.getBean(ArchiveRecovery.class));
            assertNotNull(context.getBean(RebuildExecutor.class));
            assertEquals(
                    "http://127.0.0.1:19000",
                    context.getBean(WorkerSettings.class).objectEndpoint());
            assertNotNull(context.getBean(ObjectArchive.class).activeEpoch());
            assertEquals(
                    1,
                    context.getBean(org.springframework.jdbc.core.JdbcTemplate.class)
                            .queryForObject("SELECT 1", Integer.class));
        }
    }
}
