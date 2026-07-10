package com.nageoffer.shortlink.agent.riskanalysis;

import com.nageoffer.shortlink.agent.riskanalysis.job.JdbcRiskAnalysisJobRepository;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJob;
import com.nageoffer.shortlink.agent.riskanalysis.job.RiskAnalysisJobService;
import com.nageoffer.shortlink.agent.riskprofile.batch.RiskProfileBatchLeaseLostException;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcRiskProfileBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAnalysisJobServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static final Instant NOW_INSTANT = Instant.parse("2026-07-10T02:00:00Z");

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, SHANGHAI);

    @Test
    void reusesDeterministicJobAndSessionIdentifiersForTheSameScope() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_service_idempotency");
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        acquireBatch(batchRepository, "batch-owner", NOW, Duration.ofMinutes(10));
        RiskAnalysisJobService service = new RiskAnalysisJobService(
                new JdbcRiskAnalysisJobRepository(jdbcTemplate),
                Clock.fixed(NOW_INSTANT, SHANGHAI)
        );

        RiskAnalysisJob first = service.createIfAbsent(
                "risk-profile:batch-001",
                "gid-001",
                "batch-owner"
        );
        RiskAnalysisJob repeated = service.createIfAbsent(
                "risk-profile:batch-001",
                "gid-001",
                "batch-owner"
        );

        assertThat(repeated.jobId()).isEqualTo(first.jobId());
        assertThat(first.jobId()).startsWith("risk-job-");
        assertThat(first.graphName()).isEqualTo("security-risk-graph");
        assertThat(first.graphVersion()).isEqualTo("v1");
        assertThat(first.sessionId()).isEqualTo("risk-batch:risk-profile:batch-001:gid-001");
        assertThat(first.traceId()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isEqualTo(1L);
    }

    @Test
    void rejectsJobCreationFromAStaleBatchOwner() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_analysis_job_service_owner_fence");
        JdbcRiskProfileBatchRepository batchRepository = new JdbcRiskProfileBatchRepository(jdbcTemplate);
        acquireBatch(batchRepository, "owner-a", NOW, Duration.ofMinutes(5));
        assertThat(batchRepository.tryAcquire(
                "risk-profile:batch-001",
                NOW.minusHours(2),
                NOW,
                "owner-b",
                NOW.plusMinutes(6),
                Duration.ofMinutes(10)
        )).isTrue();
        RiskAnalysisJobService service = new RiskAnalysisJobService(
                new JdbcRiskAnalysisJobRepository(jdbcTemplate),
                Clock.fixed(NOW_INSTANT.plus(Duration.ofMinutes(6)), SHANGHAI)
        );

        assertThatThrownBy(() -> service.createIfAbsent(
                "risk-profile:batch-001",
                "gid-001",
                "owner-a"
        ))
                .isInstanceOf(RiskProfileBatchLeaseLostException.class)
                .hasMessageContaining("risk-profile:batch-001");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from t_agent_risk_analysis_job",
                Long.class
        )).isZero();
    }

    private void acquireBatch(
            JdbcRiskProfileBatchRepository repository,
            String ownerToken,
            LocalDateTime now,
            Duration leaseDuration
    ) {
        assertThat(repository.tryAcquire(
                "risk-profile:batch-001",
                NOW.minusHours(2),
                NOW,
                ownerToken,
                now,
                leaseDuration
        )).isTrue();
    }

    private JdbcTemplate jdbcTemplate(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
