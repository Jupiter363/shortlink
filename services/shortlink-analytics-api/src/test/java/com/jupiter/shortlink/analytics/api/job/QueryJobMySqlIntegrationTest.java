package com.jupiter.shortlink.analytics.api.job;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicitly authorized, isolated native MySQL database; never touches a development schema. */
class QueryJobMySqlIntegrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void realMySqlSchemaLeaseRecoveryAndPublishedPageIsolation() throws Exception {
        String url = System.getenv("SHORTLINK_QUERY_TEST_JDBC_URL");
        assertThat(url)
                .as(
                        "Explicit isolated MySQL URL required; this integration fixture must not"
                                + " silently skip")
                .isNotNull();
        assertThat(url)
                .matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_analytics_control_it(?:\\?.*)?");
        assertThat(System.getenv("SHORTLINK_QUERY_TEST_ALLOW_INIT")).isEqualTo("true");
        var data =
                new DriverManagerDataSource(
                        url,
                        System.getenv("SHORTLINK_QUERY_TEST_USER"),
                        System.getenv("SHORTLINK_QUERY_TEST_PASSWORD"));
        var db = new JdbcTemplate(data);
        Path schema = com.jupiter.shortlink.analytics.api.support.RepositoryFiles
                .analyticsControlSchema(Path.of(""));
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(schema));
        }
        String tenant = "job-it-" + UUID.randomUUID(), epoch = "job-epoch-" + UUID.randomUUID();
        long start = 300000L, end = 600000L;
        // This isolated fixture starts empty; refuse to overwrite any existing epoch or window.
        assertThat(db.queryForObject("SELECT COUNT(*) FROM analytics_epoch", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM analytics_manifest WHERE window_start=?",
                                Integer.class,
                                start))
                .isZero();
        db.update(
                "INSERT INTO analytics_epoch(singleton,recovery_epoch,mode,command_ack)"
                        + " VALUES(1,?,'ACTIVE',TRUE)",
                epoch);
        db.update(
                "INSERT INTO"
                    + " analytics_manifest(window_start,recovery_epoch,build_id,manifest_revision,source_cut,source_observed_at,metric_version,dataset_version,finalized,replica_ids,coverage_proof)"
                    + " VALUES(?,?,?,1,'{}',?,'click-v1','detail-v1',TRUE,'[\"http://localhost:8123\"]','{\"n\":1,\"digest\":\"7\"}')",
                start,
                epoch,
                "job-build-it",
                end);
        try {
            var auth = mock(AuthorizationClient.class);
            var ch = mock(JobClickHouseStream.class);
            when(auth.authorize(any()))
                    .thenReturn(
                            new AuthorizationClient.Scope(
                                    tenant, List.of(4000000001L), "ownership-1"));
            when(auth.activeEpoch()).thenReturn(epoch);
            when(ch.verify(any(), anyLong())).thenReturn("http://localhost:8123");
            doAnswer(
                            i -> {
                                Consumer<Map<String, Object>> c = i.getArgument(4);
                                for (int n = 0; n < 501; n++)
                                    c.accept(
                                            Map.of(
                                                    "day",
                                                    "1970-01-01",
                                                    "linkId",
                                                    "4000000001",
                                                    "pv",
                                                    "4294967296"));
                                return null;
                            })
                    .when(ch)
                    .query(anyString(), anyString(), anyInt(), anyLong(), any());
            var settings =
                    new ApiSettings(
                            "native-it-token-long-enough-12345",
                            "http://localhost:1",
                            "http://localhost:2",
                            "http://localhost:8123",
                            "default",
                            "",
                            "test");
            var service =
                    new QueryJobService(
                            db,
                            new ObjectMapper(),
                            auth,
                            settings,
                            ch,
                            new DataSourceTransactionManager(data));
            var request =
                    new QueryRequest(
                            tenant,
                            "alice",
                            7,
                            "g1",
                            List.of(4000000001L),
                            start,
                            end,
                            null,
                            "REQUESTED",
                            null,
                            null,
                            500,
                            "METRICS");
            var identity = new QueryJobService.Identity(tenant, "alice", 8, 0, 500);
            var job = service.submit(new QueryJobService.Submit("r1", request));
            assertThat(service.submit(new QueryJobService.Submit("r1", request)).jobId())
                    .isEqualTo(job.jobId());
            var old = service.claim("old-process");
            db.update("UPDATE analytics_query_job SET lease_until=0 WHERE job_id=?", job.jobId());
            var replacement = service.claim("new-process");
            assertThat(replacement.token()).isEqualTo(old.token() + 1);
            service.execute(old);
            assertThat(service.status(job.jobId(), identity).state()).isEqualTo("RUNNING");
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM analytics_query_page WHERE job_id=?",
                                    Integer.class,
                                    job.jobId()))
                    .isZero();
            service.execute(replacement);
            assertThat(service.status(job.jobId(), identity).state()).isEqualTo("SUCCEEDED");
            var page =
                    service.page(
                            job.jobId(), new QueryJobService.Identity(tenant, "alice", 8, 1, 500));
            var rows = (List<Map<String, Object>>) page.get("items");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("pv")).isEqualTo(4294967296L);
            when(auth.authorize(any()))
                    .thenReturn(
                            new AuthorizationClient.Scope(
                                    tenant, List.of(4000000001L), "ownership-2"));
            assertThatThrownBy(() -> service.page(job.jobId(), identity))
                    .hasMessageContaining("scope changed");
        } finally {
            for (String id :
                    db.queryForList(
                            "SELECT job_id FROM analytics_query_job WHERE tenant_id=?",
                            String.class,
                            tenant))
                db.update("DELETE FROM analytics_query_page WHERE job_id=?", id);
            db.update("DELETE FROM analytics_query_job WHERE tenant_id=?", tenant);
            db.update(
                    "DELETE FROM analytics_manifest WHERE window_start=? AND recovery_epoch=?",
                    start,
                    epoch);
            db.update("DELETE FROM analytics_epoch WHERE singleton=1 AND recovery_epoch=?", epoch);
        }
    }
}
