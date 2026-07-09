package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileCandidateLoadNodeTest {

    @Test
    void loadsGroupProfileAndTopTenShortLinkProfilesByGid() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load");
        JdbcShortLinkRiskProfileRepository shortLinkRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        for (int index = 0; index < 12; index++) {
            shortLinkRepository.save(profile("gid-001", "u" + index, 100 - index, endTime, List.of()));
        }
        groupRepository.save(groupProfile("gid-001", endTime, List.of(profile("gid-001", "u0", 100, endTime, List.of()))));

        ProfileCandidateLoadNode node = new ProfileCandidateLoadNode(shortLinkRepository, groupRepository, 10);

        ProfileRiskAnalysisContext context = node.load("analyze gid=gid-001");

        assertThat(context.gid()).isEqualTo("gid-001");
        assertThat(context.groupProfile()).isNotNull();
        assertThat(context.shortLinkProfiles()).hasSize(10);
        assertThat(context.shortLinkProfiles())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("u0", "u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8", "u9");
        assertThat(context.toDataSource().toString())
                .contains("risk_profile")
                .contains("gid-001")
                .doesNotContain("raw" + "Ip")
                .doesNotContain("visitor" + "Id");
    }

    private GroupRiskProfile groupProfile(String gid, LocalDateTime endTime, List<ShortLinkRiskProfile> topProfiles) {
        return new GroupRiskProfile(
                gid,
                endTime.minusHours(2),
                endTime,
                12,
                2,
                3,
                7,
                1,
                0,
                74.0,
                100,
                95,
                RiskLevel.HIGH,
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                topProfiles,
                List.of(new RiskTrendPoint(endTime.toLocalDate(), 95, RiskLevel.HIGH)),
                ""
        );
    }

    private ShortLinkRiskProfile profile(
            String gid,
            String shortUri,
            int riskScore,
            LocalDateTime endTime,
            List<String> latestPolicyActions
    ) {
        return new ShortLinkRiskProfile(
                gid,
                "nurl.ink",
                shortUri,
                "nurl.ink/" + shortUri,
                endTime.minusHours(2),
                endTime,
                metrics(),
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                RiskWatchStatus.NONE,
                latestPolicyActions,
                ""
        );
    }

    private ShortLinkRiskMetrics metrics() {
        return new ShortLinkRiskMetrics(
                600,
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
