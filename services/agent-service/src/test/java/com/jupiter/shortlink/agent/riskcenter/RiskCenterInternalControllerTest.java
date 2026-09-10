package com.jupiter.shortlink.agent.riskcenter;

import static com.jupiter.shortlink.agent.riskprofile.RiskProfileTestFixture.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.harness.security.*;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskcenter.api.RiskCenterInternalController;
import com.jupiter.shortlink.agent.riskcenter.repository.*;
import com.jupiter.shortlink.agent.riskcenter.service.RiskCenterService;
import com.jupiter.shortlink.agent.riskcommon.model.*;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.model.*;
import com.jupiter.shortlink.agent.riskprofile.repository.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.*;

class RiskCenterInternalControllerTest {
    private static final String BASE = "/internal/short-link-agent/v1/risk";
    private static final String TOKEN = "risk-center-internal-component-token-32";
    private MockMvc mvc;
    private AgentAuthorityClient authority;
    private RiskPolicyService policies;
    private RiskCenterService service;
    private JdbcTemplate jdbc;
    private ShortLinkRiskProfile profile;
    private AuthorizedScope scope;

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:risk_controller_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        var links = new JdbcShortLinkRiskProfileRepository(jdbc);
        var groups = new JdbcGroupRiskProfileRepository(jdbc);
        policies = mock(RiskPolicyService.class);
        authority = mock(AgentAuthorityClient.class);
        scope =
                new AuthorizedScope(
                        "1001",
                        "3",
                        List.of(
                                Map.of(
                                        "linkId",
                                        7L,
                                        "gid",
                                        "g1",
                                        "domain",
                                        "nurl.ink",
                                        "shortUri",
                                        "abc123",
                                        "fullShortUrl",
                                        "nurl.ink/abc123")));
        when(authority.resolve(any(), any(), any(), any())).thenReturn(scope);
        profile = profile();
        saveShortLinkProfile(jdbc, links, profile);
        service =
                new RiskCenterService(
                        new JdbcRiskEventRepository(jdbc),
                        new JdbcRiskSnapshotRepository(jdbc),
                        new JdbcRiskReviewRepository(jdbc),
                        links,
                        groups,
                        policies,
                        authority);
        service.recordProfileBatchEvent(profile, "trace-1");
        service.upsertSnapshotFromProfile(profile, "event-1", "trace-1");
        var properties = new AgentProperties();
        properties.getSecurity().setInternalToken(TOKEN);
        mvc =
                MockMvcBuilders.standaloneSetup(new RiskCenterInternalController(service))
                        .addFilters(new InternalAgentApiFilter(properties))
                        .build();
    }

    @Test
    void cardsPreserveLongQualityAndNeverTreatSuggestedActionAsCurrentPolicy() throws Exception {
        mvc.perform(trusted(get(BASE + "/groups/g1/short-links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pv2h").value(3_000_000_000L))
                .andExpect(jsonPath("$.data[0].tenantId").value("1001"))
                .andExpect(jsonPath("$.data[0].statsMeta.availability").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data[0].statsMeta.provisional").value(true))
                .andExpect(jsonPath("$.data[0].latestPolicyActions[0]").value("LIMIT_RATE"))
                .andExpect(jsonPath("$.data[0].currentPolicy.state").value("UNKNOWN"));
    }

    @Test
    void detailKeepsHistorySeparateAndFiltersEventsByStableIdentity() throws Exception {
        mvc.perform(trusted(get(BASE + "/groups/g1/short-links/nurl.ink/abc123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.linkId").value(7))
                .andExpect(jsonPath("$.data.latestSnapshot.policyStatus").doesNotExist())
                .andExpect(
                        jsonPath("$.data.latestSnapshot.policyStatusSource")
                                .value("HISTORICAL_ONLY"))
                .andExpect(
                        jsonPath("$.data.recentEvents[0].evidence.statsMeta.snapshotId")
                                .value("snapshot-1"));
    }

    @Test
    void currentScopeControlsMovedLinkAndRevocationDeniesBeforeResponse() throws Exception {
        when(authority.resolve(any(), eq("g2"), any(), any()))
                .thenReturn(
                        new AuthorizedScope(
                                "1001", "4", List.of(Map.of("linkId", 7L, "gid", "g2"))));
        mvc.perform(trusted(get(BASE + "/groups/g2/short-links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].gid").value("g2"));
        when(authority.resolve(any(), eq("g1"), any(), any()))
                .thenThrow(new SecurityException("not owned"));
        mvc.perform(trusted(get(BASE + "/groups/g1/short-links")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void missingIdentityAndInvalidServiceTokenAreRejected() throws Exception {
        mvc.perform(get(BASE + "/groups/g1/short-links").header("X-Agent-Internal-Token", TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/groups/g1/short-links").header("X-Agent-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authority);
    }

    @Test
    void manualReviewSurvivesProfileRefreshAndNeverInvokesPolicyMutation() throws Exception {
        mvc.perform(
                        trusted(post(BASE + "/reviews"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(review("WATCH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewer").value("trusted-user"));
        service.upsertSnapshotFromProfile(profile, "event-2", "trace-2");
        mvc.perform(trusted(get(BASE + "/groups/g1/short-links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].watchStatus").value("WATCHING"));
        mvc.perform(
                        trusted(post(BASE + "/reviews"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(review("FALSE_POSITIVE")))
                .andExpect(status().isOk());
        mvc.perform(trusted(get(BASE + "/groups/g1/short-links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].manualReview.action").value("FALSE_POSITIVE"))
                .andExpect(jsonPath("$.data[0].watchStatus").value("WATCHING"))
                .andExpect(jsonPath("$.data[0].currentPolicy.state").value("UNKNOWN"));
        verifyNoInteractions(policies);
    }

    @Test
    void legacyAgentPolicyDisableFailsClosedWithoutClaimingSuccess() throws Exception {
        mvc.perform(
                        trusted(post(BASE + "/policies/policy-1/disable"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"gid\":\"g1\"}"))
                .andExpect(status().isGone());
        verifyNoInteractions(policies);
    }

    @Test
    void unauthorizedHistoricalEventCannotBeAttachedToReview() throws Exception {
        String body =
                review("WATCH")
                        .replace("\"reviewer\"", "\"eventId\":\"foreign-event\",\"reviewer\"");
        mvc.perform(
                        trusted(post(BASE + "/reviews"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_agent_risk_review", Long.class))
                .isZero();
    }

    @Test
    void groupReviewUsesTenantAndGidWithoutInventingALinkOrRevokingPolicy() throws Exception {
        mvc.perform(
                        trusted(post(BASE + "/reviews"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"targetType\":\"GROUP\",\"gid\":\"g1\",\"reviewAction\":\"FALSE_POSITIVE\",\"reviewer\":\"spoof\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewer").value("trusted-user"));
        var row = jdbc.queryForMap("SELECT tenant_id,link_id,gid FROM t_agent_risk_review");
        assertThat(row)
                .containsEntry("tenant_id", "1001")
                .containsEntry("gid", "g1")
                .containsEntry("link_id", null);
        assertThat(new JdbcRiskReviewRepository(jdbc).latestGroupState(scope, "g1"))
                .containsEntry("action", "FALSE_POSITIVE");
        verifyNoInteractions(policies);
    }

    private MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request) {
        return request.header("X-Agent-Internal-Token", TOKEN)
                .header("X-Agent-UserId", "1001")
                .header("X-Agent-Username", "trusted-user")
                .header("X-Agent-Auth-Version", "1");
    }

    private String review(String action) {
        return "{\"targetType\":\"SHORT_LINK\",\"gid\":\"g1\",\"domain\":\"nurl.ink\",\"shortUri\":\"abc123\",\"fullShortUrl\":\"nurl.ink/abc123\",\"reviewer\":\"spoof\",\"reviewAction\":\""
                + action
                + "\"}";
    }

    private ShortLinkRiskProfile profile() {
        var metrics =
                new ShortLinkRiskMetrics(
                        3_000_000_000L,
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
                        0.88);
        return new ShortLinkRiskProfile(
                        "g1",
                        "nurl.ink",
                        "abc123",
                        "nurl.ink/abc123",
                        LocalDateTime.of(2026, 7, 10, 0, 0),
                        LocalDateTime.of(2026, 7, 10, 2, 0),
                        metrics,
                        92,
                        92,
                        RiskLevel.HIGH,
                        Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                        RiskWatchStatus.NONE,
                        List.of("LIMIT_RATE"),
                        "")
                .withEvidence(
                        new StatsEvidence(
                                "1001",
                                7,
                                Map.of(
                                        "availability",
                                        "UNAVAILABLE",
                                        "snapshotId",
                                        "snapshot-1",
                                        "snapshotCreatedAt",
                                        1783616400000L,
                                        "effectiveEnd",
                                        1783612800000L,
                                        "provisional",
                                        true,
                                        "collectionQuality",
                                        "UNKNOWN"),
                                "rules-v1"));
    }
}
