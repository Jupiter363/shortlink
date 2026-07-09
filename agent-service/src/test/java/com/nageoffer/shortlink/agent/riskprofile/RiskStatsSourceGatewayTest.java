package com.nageoffer.shortlink.agent.riskprofile;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkActiveCandidate;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkBusinessRiskStatsGateway;
import com.nageoffer.shortlink.agent.riskprofile.source.ShortLinkStatsWindow;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RiskStatsSourceGatewayTest {

    @Test
    void listActiveShortLinksRequiresConfiguredTrustedUsername() {
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://admin.test");
        ShortLinkBusinessRiskStatsGateway gateway = new ShortLinkBusinessRiskStatsGateway(
                properties,
                new RestTemplate()
        );

        assertThatThrownBy(() -> gateway.listActiveShortLinks(Instant.parse("2026-07-03T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Risk stats business username is required");
    }

    @Test
    void listActiveShortLinksCallsAdminRiskEndpointWithTrustedHeaders() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://admin.test/");
        properties.getBusiness().setInternalToken("internal-token");
        properties.getBusiness().setUsername("zhangsan");
        ShortLinkBusinessRiskStatsGateway gateway = new ShortLinkBusinessRiskStatsGateway(properties, restTemplate);

        server.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/risk/active-short-links?since=2026-07-03T00:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "data": [
                            {
                              "gid": "g1",
                              "domain": "nurl.ink",
                              "shortUri": "abc123",
                              "fullShortUrl": "nurl.ink/abc123",
                              "pv": 120,
                              "uv": 80
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<ShortLinkActiveCandidate> candidates = gateway.listActiveShortLinks(
                Instant.parse("2026-07-03T00:00:00Z"));

        assertThat(candidates).extracting(ShortLinkActiveCandidate::shortUri).containsExactly("abc123");
        assertThat(candidates.get(0).gid()).isEqualTo("g1");
        server.verify();
    }

    @Test
    void loadStatsWindowMapsSanitizedAggregateFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://admin.test");
        properties.getBusiness().setInternalToken("internal-token");
        properties.getBusiness().setUsername("zhangsan");
        ShortLinkBusinessRiskStatsGateway gateway = new ShortLinkBusinessRiskStatsGateway(properties, restTemplate);
        ShortLinkActiveCandidate candidate = new ShortLinkActiveCandidate(
                "g1",
                "nurl.ink",
                "abc123",
                "nurl.ink/abc123"
        );

        server.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats?gid=g1&fullShortUrl=nurl.ink/abc123&startTime=2026-07-10T00:00:00Z&endTime=2026-07-10T02:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "data": {
                            "gid": "g1",
                            "domain": "nurl.ink",
                            "shortUri": "abc123",
                            "fullShortUrl": "nurl.ink/abc123",
                            "pv": 120,
                            "uv": 80,
                            "uip": 60,
                            "topIpShare": 0.5,
                            "topVisitorShare": null,
                            "topRegionShare": 0.6,
                            "topDeviceShare": 0.8,
                            "topBrowserShare": 0.75,
                            "peakHourShare": 0.25,
                            "repeatVisitRatio": null
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ShortLinkStatsWindow stats = gateway.loadStatsWindow(
                candidate,
                Instant.parse("2026-07-10T00:00:00Z"),
                Instant.parse("2026-07-10T02:00:00Z")
        );

        assertThat(stats.pv()).isEqualTo(120);
        assertThat(stats.uv()).isEqualTo(80);
        assertThat(stats.topIpShare()).isLessThanOrEqualTo(1.0);
        assertThat(stats.topRegionShare()).isEqualTo(0.6);
        assertThat(stats.repeatVisitRatio()).isNull();
        server.verify();
    }
}
