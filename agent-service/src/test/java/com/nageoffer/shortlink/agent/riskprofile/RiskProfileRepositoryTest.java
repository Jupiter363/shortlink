package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RiskProfileRepositoryTest {

    @Test
    void savesAndQueriesShortLinkRiskProfiles() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_short_link_profile_repository");
        JdbcShortLinkRiskProfileRepository repository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);

        repository.save(highRiskProfile("gid-001", "nurl.ink", "abc123", 92, 600, LocalDateTime.of(2026, 7, 10, 2, 0)));
        repository.save(highRiskProfile("gid-001", "nurl.ink", "def456", 70, 220, LocalDateTime.of(2026, 7, 10, 2, 0)));
        repository.save(highRiskProfile("gid-001", "nurl.ink", "abc123", 81, 300, LocalDateTime.of(2026, 7, 9, 2, 0)));

        assertThat(repository.findLatest("nurl.ink", "abc123"))
                .isPresent()
                .get()
                .extracting(ShortLinkRiskProfile::riskScore)
                .isEqualTo(92);
        assertThat(repository.findLatestByGid("gid-001"))
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("abc123", "def456");
        assertThat(repository.findTopRiskByGid("gid-001", 10))
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("abc123", "def456");
    }

    @Test
    void preservesProfileSnapshotPolicyActionsAndSummaryWhenQuerying() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_short_link_profile_snapshot_repository");
        JdbcShortLinkRiskProfileRepository repository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        ShortLinkRiskProfile profile = riskProfile(
                "gid-001",
                "nurl.ink",
                "manual1",
                94,
                620,
                LocalDateTime.of(2026, 7, 10, 2, 0),
                RiskWatchStatus.WATCHING,
                List.of("DISABLE_SHORT_LINK", "LIMIT_TIME_WINDOW"),
                "agent summary without raw identifiers"
        );

        repository.save(profile);

        assertThat(repository.findLatest("nurl.ink", "manual1"))
                .isPresent()
                .get()
                .satisfies(saved -> {
                    assertThat(saved.watchStatus()).isEqualTo(RiskWatchStatus.WATCHING);
                    assertThat(saved.latestPolicyActions()).containsExactly("DISABLE_SHORT_LINK", "LIMIT_TIME_WINDOW");
                    assertThat(saved.latestAgentSummary()).isEqualTo("agent summary without raw identifiers");
                });
    }

    @Test
    void savesAndQueriesGroupRiskProfilesAndTrend() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_group_profile_repository");
        JdbcGroupRiskProfileRepository repository = new JdbcGroupRiskProfileRepository(jdbcTemplate);

        repository.save(groupProfile("gid-001", LocalDateTime.of(2026, 7, 8, 2, 0), 55, RiskLevel.MEDIUM));
        repository.save(groupProfile("gid-001", LocalDateTime.of(2026, 7, 9, 2, 0), 74, RiskLevel.HIGH));
        repository.save(groupProfile("gid-001", LocalDateTime.of(2026, 7, 10, 2, 0), 82, RiskLevel.HIGH));

        assertThat(repository.findLatestByGid("gid-001"))
                .isPresent()
                .get()
                .extracting(GroupRiskProfile::groupRiskScore)
                .isEqualTo(82);
        assertThat(repository.findTrend7d("gid-001", LocalDate.of(2026, 7, 10)))
                .extracting(RiskTrendPoint::date)
                .containsExactly(
                        LocalDate.of(2026, 7, 8),
                        LocalDate.of(2026, 7, 9),
                        LocalDate.of(2026, 7, 10)
                );
    }

    private ShortLinkRiskProfile highRiskProfile(
            String gid,
            String domain,
            String shortUri,
            int riskScore,
            int pv2h,
            LocalDateTime profileWindowEnd
    ) {
        return riskProfile(
                gid,
                domain,
                shortUri,
                riskScore,
                pv2h,
                profileWindowEnd,
                RiskWatchStatus.NONE,
                List.of("LIMIT_RATE"),
                ""
        );
    }

    private ShortLinkRiskProfile riskProfile(
            String gid,
            String domain,
            String shortUri,
            int riskScore,
            int pv2h,
            LocalDateTime profileWindowEnd,
            RiskWatchStatus watchStatus,
            List<String> latestPolicyActions,
            String latestAgentSummary
    ) {
        ShortLinkRiskMetrics metrics = new ShortLinkRiskMetrics(
                pv2h,
                50,
                900,
                300,
                2100,
                1200,
                8.0,
                0.82,
                0.78,
                0.50,
                0.65,
                0.60,
                12.0,
                0.74,
                0.88
        );
        return new ShortLinkRiskProfile(
                gid,
                domain,
                shortUri,
                domain + "/" + shortUri,
                profileWindowEnd.minusHours(2),
                profileWindowEnd,
                metrics,
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                watchStatus,
                latestPolicyActions,
                latestAgentSummary
        );
    }

    private GroupRiskProfile groupProfile(
            String gid,
            LocalDateTime profileWindowEnd,
            int score,
            RiskLevel level
    ) {
        return new GroupRiskProfile(
                gid,
                profileWindowEnd.minusHours(2),
                profileWindowEnd,
                12,
                5,
                4,
                3,
                1,
                1,
                58.5,
                92,
                score,
                level,
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                List.of(highRiskProfile(gid, "nurl.ink", "abc123", 92, 600, profileWindowEnd)),
                List.of(new RiskTrendPoint(profileWindowEnd.toLocalDate(), score, level)),
                ""
        );
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
