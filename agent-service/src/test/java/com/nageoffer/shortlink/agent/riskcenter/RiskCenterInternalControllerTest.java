package com.nageoffer.shortlink.agent.riskcenter;

import com.nageoffer.shortlink.agent.harness.security.InternalAgentApiFilter;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcenter.api.RiskCenterInternalController;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskEvent;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcenter.service.RiskCenterService;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskEventSource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskCenterInternalControllerTest {

    private MockMvc mockMvc;

    private RiskPolicyService riskPolicyService;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = jdbcTemplate("risk_center_controller_" + System.nanoTime());
        JdbcShortLinkRiskProfileRepository shortLinkProfileRepository = new JdbcShortLinkRiskProfileRepository(jdbcTemplate);
        JdbcGroupRiskProfileRepository groupProfileRepository = new JdbcGroupRiskProfileRepository(jdbcTemplate);
        JdbcRiskEventRepository eventRepository = new JdbcRiskEventRepository(jdbcTemplate);
        JdbcRiskSnapshotRepository snapshotRepository = new JdbcRiskSnapshotRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        riskPolicyService = mock(RiskPolicyService.class);

        ShortLinkRiskProfile shortLinkProfile = shortLinkProfile("gid-001", "nurl.ink", "abc123", 92);
        shortLinkProfileRepository.save(shortLinkProfile);
        groupProfileRepository.save(groupProfile(shortLinkProfile));
        eventRepository.saveEvent(event("event-001", 92));
        snapshotRepository.upsertSnapshot(snapshot("event-001", 92));

        RiskCenterService riskCenterService = new RiskCenterService(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkProfileRepository,
                groupProfileRepository,
                riskPolicyService
        );
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("");
        properties.getSecurity().setInternalTokenDevMode(true);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RiskCenterInternalController(riskCenterService))
                .addFilters(new InternalAgentApiFilter(properties))
                .build();
    }

    @Test
    void exposesGroupOverviewShortLinkCardsAndDetailWithoutSensitiveFields() throws Exception {
        MvcResult overview = mockMvc.perform(get("/internal/short-link-agent/v1/risk/groups/gid-001/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.gid").value("gid-001"))
                .andExpect(jsonPath("$.data.groupRiskScore").value(92))
                .andReturn();

        MvcResult cards = mockMvc.perform(get("/internal/short-link-agent/v1/risk/groups/gid-001/short-links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].shortUri").value("abc123"))
                .andExpect(jsonPath("$.data[0].riskScore").value(92))
                .andReturn();

        MvcResult detail = mockMvc.perform(get("/internal/short-link-agent/v1/risk/groups/gid-001/short-links/nurl.ink/abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.shortUri").value("abc123"))
                .andExpect(jsonPath("$.data.latestSnapshot.lastEventId").value("event-001"))
                .andReturn();

        assertNoSensitiveFields(overview.getResponse().getContentAsString());
        assertNoSensitiveFields(cards.getResponse().getContentAsString());
        assertNoSensitiveFields(detail.getResponse().getContentAsString());
    }

    @Test
    void exposesEventsReviewsAndPolicyDisableWithoutSensitiveFields() throws Exception {
        MvcResult events = mockMvc.perform(get("/internal/short-link-agent/v1/risk/events")
                        .param("gid", "gid-001")
                        .param("targetType", "SHORT_LINK")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].eventId").value("event-001"))
                .andReturn();

        String reviewBody = """
                {
                  "eventId": "event-001",
                  "targetType": "SHORT_LINK",
                  "gid": "gid-001",
                  "domain": "nurl.ink",
                  "shortUri": "abc123",
                  "fullShortUrl": "nurl.ink/abc123",
                  "reviewAction": "WATCH",
                  "reviewer": "risk-admin",
                  "reviewNote": "watch this short link"
                }
                """;
        MvcResult review = mockMvc.perform(post("/internal/short-link-agent/v1/risk/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewAction").value("WATCH"))
                .andExpect(jsonPath("$.data.reviewer").value("risk-admin"))
                .andReturn();

        String disableBody = """
                {
                  "gid": "gid-001",
                  "reviewer": "risk-admin",
                  "reason": "manual revoke after review",
                  "traceId": "trace-disable"
                }
                """;
        MvcResult disable = mockMvc.perform(post("/internal/short-link-agent/v1/risk/policies/policy-001/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disableBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        ArgumentCaptor<RiskPolicyDisableCommand> commandCaptor = ArgumentCaptor.forClass(RiskPolicyDisableCommand.class);
        verify(riskPolicyService).disablePolicy(commandCaptor.capture());
        assertThat(commandCaptor.getValue().policyId()).isEqualTo("policy-001");
        assertThat(commandCaptor.getValue().gid()).isEqualTo("gid-001");
        assertThat(commandCaptor.getValue().executor()).isEqualTo("risk-admin");

        assertNoSensitiveFields(events.getResponse().getContentAsString());
        assertNoSensitiveFields(review.getResponse().getContentAsString());
        assertNoSensitiveFields(disable.getResponse().getContentAsString());
    }

    private void assertNoSensitiveFields(String responseBody) {
        assertThat(responseBody)
                .doesNotContain("raw" + "Ip")
                .doesNotContain("ip" + "Address")
                .doesNotContain("user" + "Id")
                .doesNotContain("visitor" + "Id")
                .doesNotContain("access_records." + "rawData");
    }

    private ShortLinkRiskProfile shortLinkProfile(String gid, String domain, String shortUri, int riskScore) {
        ShortLinkRiskMetrics metrics = new ShortLinkRiskMetrics(
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
        return new ShortLinkRiskProfile(
                gid,
                domain,
                shortUri,
                domain + "/" + shortUri,
                LocalDateTime.of(2026, 7, 10, 0, 0),
                LocalDateTime.of(2026, 7, 10, 2, 0),
                metrics,
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                RiskWatchStatus.NONE,
                List.of("LIMIT_RATE"),
                ""
        );
    }

    private GroupRiskProfile groupProfile(ShortLinkRiskProfile topRiskProfile) {
        return new GroupRiskProfile(
                topRiskProfile.gid(),
                topRiskProfile.profileWindowStart(),
                topRiskProfile.profileWindowEnd(),
                1,
                0,
                0,
                1,
                0,
                0,
                92.0,
                92,
                92,
                RiskLevel.HIGH,
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                List.of(topRiskProfile),
                List.of(new RiskTrendPoint(topRiskProfile.profileWindowEnd().toLocalDate(), 92, RiskLevel.HIGH)),
                "group risk summary"
        );
    }

    private RiskEvent event(String eventId, int score) {
        return new RiskEvent(
                eventId,
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "nurl.ink/abc123",
                score,
                RiskLevel.fromScore(score),
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                Map.of("pv2h", 600, "topShare", 0.82),
                List.of("LIMIT_RATE"),
                "traffic spike and concentrated access",
                "trace-" + eventId,
                "session-001",
                RiskEventSource.PROFILE_BATCH,
                LocalDateTime.of(2026, 7, 10, 2, 0)
        );
    }

    private RiskSnapshot snapshot(String eventId, int score) {
        return new RiskSnapshot(
                RiskTargetType.SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "nurl.ink/abc123",
                score,
                RiskLevel.fromScore(score),
                List.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION),
                List.of(Map.of("type", "traffic", "value", 600)),
                RiskWatchStatus.NONE,
                "ACTIVE",
                eventId,
                "trace-" + eventId,
                LocalDateTime.of(2026, 7, 10, 2, 0)
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
