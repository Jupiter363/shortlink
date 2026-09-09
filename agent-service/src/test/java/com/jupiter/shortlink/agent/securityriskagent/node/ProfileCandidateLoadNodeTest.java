package com.jupiter.shortlink.agent.securityriskagent.node;

import static com.jupiter.shortlink.agent.riskprofile.RiskProfileTestFixture.saveGroupProfile;
import static com.jupiter.shortlink.agent.riskprofile.RiskProfileTestFixture.saveShortLinkProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskProfileTargetRef;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

class ProfileCandidateLoadNodeTest {

    @Test
    void structuredInputIgnoresMessageGidAndLoadsOnlyTheRequestedBatchTargets() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load_structured");
        JdbcShortLinkRiskProfileRepository shortLinkRepository =
                new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository =
                new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String requestedBatchId = "risk-profile:batch-requested";
        String otherBatchId = "risk-profile:batch-other";

        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "selected", 92, endTime, List.of())
                        .withBatchId(requestedBatchId));
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "not-selected", 95, endTime, List.of())
                        .withBatchId(requestedBatchId));
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "selected", 99, endTime.plusHours(2), List.of())
                        .withBatchId(otherBatchId));
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-message", "message-target", 100, endTime.plusHours(4), List.of())
                        .withBatchId("risk-profile:batch-message"));
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-001", endTime, List.of()).withBatchId(requestedBatchId));
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-001", endTime.plusHours(2), List.of()).withBatchId(otherBatchId));
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-message", endTime.plusHours(4), List.of())
                        .withBatchId("risk-profile:batch-message"));

        ProfileCandidateLoadNode node =
                authorizedNode(jdbcTemplate, shortLinkRepository, groupRepository, 10);
        RiskAnalysisInput input =
                new RiskAnalysisInput(
                        requestedBatchId,
                        "gid-001",
                        endTime,
                        List.of(new RiskProfileTargetRef("nurl.ink", "selected")));

        ProfileRiskAnalysisContext context =
                node.load(
                        "analyze gid=gid-message",
                        input,
                        com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL);

        assertThat(context.gid()).isEqualTo("gid-001");
        assertThat(context.groupProfile()).isNotNull();
        assertThat(context.groupProfile().batchId()).isEqualTo(requestedBatchId);
        assertThat(context.shortLinkProfiles())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("selected");
        assertThat(context.shortLinkProfiles())
                .extracting(ShortLinkRiskProfile::batchId)
                .containsExactly(requestedBatchId);
    }

    @Test
    void structuredInputLimitsCandidatesToConfiguredTopSize() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load_top_size");
        JdbcShortLinkRiskProfileRepository shortLinkRepository =
                new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository =
                new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String batchId = "risk-profile:batch-top-size";
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "first", 95, endTime, List.of()).withBatchId(batchId));
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "second", 90, endTime, List.of()).withBatchId(batchId));
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "third", 85, endTime, List.of()).withBatchId(batchId));
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-001", endTime, List.of()).withBatchId(batchId));
        ProfileCandidateLoadNode node =
                authorizedNode(jdbcTemplate, shortLinkRepository, groupRepository, 2);
        RiskAnalysisInput input =
                new RiskAnalysisInput(
                        batchId,
                        "gid-001",
                        endTime,
                        List.of(
                                new RiskProfileTargetRef("nurl.ink", "first"),
                                new RiskProfileTargetRef("nurl.ink", "second"),
                                new RiskProfileTargetRef("nurl.ink", "third")));

        ProfileRiskAnalysisContext context =
                node.load(
                        "ignored", input, com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL);

        assertThat(context.shortLinkProfiles())
                .extracting(ShortLinkRiskProfile::shortUri)
                .containsExactly("first", "second");
    }

    @Test
    void structuredInputFailsWhenReferencedCandidateIsMissing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load_missing");
        JdbcShortLinkRiskProfileRepository shortLinkRepository =
                new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository =
                new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String batchId = "risk-profile:batch-missing";
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-001", endTime, List.of()).withBatchId(batchId));
        ProfileCandidateLoadNode node =
                authorizedNode(jdbcTemplate, shortLinkRepository, groupRepository, 10);
        RiskAnalysisInput input =
                new RiskAnalysisInput(
                        batchId,
                        "gid-001",
                        endTime,
                        List.of(new RiskProfileTargetRef("nurl.ink", "missing")));

        assertThatThrownBy(
                        () ->
                                node.load(
                                        "ignored",
                                        input,
                                        com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidates are unavailable");
    }

    @Test
    void structuredInputFailsWhenReferencedCandidateIsLowRisk() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load_low");
        JdbcShortLinkRiskProfileRepository shortLinkRepository =
                new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository =
                new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        String batchId = "risk-profile:batch-low";
        saveShortLinkProfile(
                jdbcTemplate,
                shortLinkRepository,
                profile("gid-001", "low", 20, endTime, List.of()).withBatchId(batchId));
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile("gid-001", endTime, List.of()).withBatchId(batchId));
        ProfileCandidateLoadNode node =
                authorizedNode(jdbcTemplate, shortLinkRepository, groupRepository, 10);
        RiskAnalysisInput input =
                new RiskAnalysisInput(
                        batchId,
                        "gid-001",
                        endTime,
                        List.of(new RiskProfileTargetRef("nurl.ink", "low")));

        assertThatThrownBy(
                        () ->
                                node.load(
                                        "ignored",
                                        input,
                                        com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("candidates are unavailable");
    }

    @Test
    void loadsGroupProfileAndTopTenShortLinkProfilesByGid() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("profile_candidate_load");
        JdbcShortLinkRiskProfileRepository shortLinkRepository =
                new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupRepository =
                new JdbcGroupRiskProfileRepository(jdbcTemplate);
        LocalDateTime endTime = LocalDateTime.of(2026, 7, 10, 2, 0);
        for (int index = 0; index < 12; index++) {
            saveShortLinkProfile(
                    jdbcTemplate,
                    shortLinkRepository,
                    profile("gid-001", "u" + index, 100 - index, endTime, List.of()));
        }
        saveGroupProfile(
                jdbcTemplate,
                groupRepository,
                groupProfile(
                        "gid-001",
                        endTime,
                        List.of(profile("gid-001", "u0", 100, endTime, List.of()))));

        ProfileCandidateLoadNode node =
                authorizedNode(jdbcTemplate, shortLinkRepository, groupRepository, 10);

        ProfileRiskAnalysisContext context =
                node.load(
                        "analyze gid=gid-001",
                        null,
                        com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL);

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

    private GroupRiskProfile groupProfile(
            String gid, LocalDateTime endTime, List<ShortLinkRiskProfile> topProfiles) {
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
                "");
    }

    private ShortLinkRiskProfile profile(
            String gid,
            String shortUri,
            int riskScore,
            LocalDateTime endTime,
            List<String> latestPolicyActions) {
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
                        "")
                .withEvidence(
                        new com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence(
                                "1001",
                                Integer.toUnsignedLong(shortUri.hashCode()) + 1L,
                                com.jupiter.shortlink.agent.StatsTestFixtures.meta(),
                                com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence
                                        .CURRENT_RULE_VERSION));
    }

    private ShortLinkRiskMetrics metrics() {
        return new ShortLinkRiskMetrics(
                600, 50, 900, 300, 2100, 1200, 8.0, 0.82, 0.78, 0.50, 0.65, 0.60, 12.0, 0.74, 0.88);
    }

    private ProfileCandidateLoadNode authorizedNode(
            JdbcTemplate jdbc,
            JdbcShortLinkRiskProfileRepository links,
            JdbcGroupRiskProfileRepository groups,
            int limit) {
        var codec = new com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec();
        for (var row : jdbc.queryForList("SELECT batch_id,gid FROM t_agent_group_risk_profile")) {
            var profiles =
                    links.findByBatchIdAndGid(
                            row.get("batch_id").toString(), row.get("gid").toString());
            if (!profiles.isEmpty())
                jdbc.update(
                        "UPDATE t_agent_group_risk_profile SET"
                            + " tenant_id=?,top_risk_short_links_json=? WHERE batch_id=? AND gid=?",
                        "1001",
                        codec.toJson(
                                profiles.stream()
                                        .map(
                                                p ->
                                                        java.util.Map.of(
                                                                "evidence", p.evidence().toMap()))
                                        .toList()),
                        row.get("batch_id"),
                        row.get("gid"));
        }
        var authority =
                org.mockito.Mockito.mock(
                        com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.class);
        org.mockito.Mockito.when(
                        authority.resolve(
                                org.mockito.ArgumentMatchers.eq(
                                        com.jupiter.shortlink.agent.StatsTestFixtures.PRINCIPAL),
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.isNull(),
                                org.mockito.ArgumentMatchers.isNull()))
                .thenAnswer(
                        call -> {
                            java.util.List<java.util.Map<String, Object>> identities =
                                    jdbc.queryForList(
                                            "SELECT DISTINCT link_id AS linkId,gid,domain,short_uri"
                                                + " AS shortUri,full_short_url AS fullShortUrl FROM"
                                                + " t_agent_short_link_risk_profile WHERE"
                                                + " tenant_id='1001' AND gid=?",
                                            call.getArgument(1, String.class));
                            return new com.jupiter.shortlink.agent.business.shortlink
                                    .AgentAuthorityClient.AuthorizedScope(
                                    "1001", "ownership-1", identities);
                        });
        return new ProfileCandidateLoadNode(links, groups, limit, authority);
    }

    @Test
    void textGidWithoutCurrentAuthorizationCannotReadStoredProfiles() {
        var links = org.mockito.Mockito.mock(JdbcShortLinkRiskProfileRepository.class);
        var groups = org.mockito.Mockito.mock(JdbcGroupRiskProfileRepository.class);
        var authority =
                org.mockito.Mockito.mock(
                        com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.class);
        var node = new ProfileCandidateLoadNode(links, groups, 10, authority);
        assertThatThrownBy(() -> node.load("gid=another-tenant"))
                .isInstanceOf(SecurityException.class);
        org.mockito.Mockito.verifyNoInteractions(links, groups, authority);
    }

    private JdbcTemplate jdbcTemplate(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
