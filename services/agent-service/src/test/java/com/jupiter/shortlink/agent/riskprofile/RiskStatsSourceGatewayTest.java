package com.jupiter.shortlink.agent.riskprofile;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jupiter.shortlink.agent.StatsTestFixtures;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskprofile.source.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.*;
import java.util.*;

class RiskStatsSourceGatewayTest {
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
    private final AgentProperties props = properties();
    private final ShortLinkBusinessRiskStatsGateway gateway =
            new ShortLinkBusinessRiskStatsGateway(props, rest);

    private static AgentProperties properties() {
        var p = new AgentProperties();
        p.getBusiness().setBaseUrl("http://admin.test");
        p.getBusiness().setUsername("zhangsan");
        p.getBusiness().setInternalToken(StatsTestFixtures.SECRET);
        return p;
    }

    private void respond(Map<String, Object> envelope) {
        server.expect(anything())
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andExpect(header("X-Agent-Internal-Token", StatsTestFixtures.SECRET))
                .andExpect(header("X-Agent-Principal-Mode", "SYSTEM"))
                .andRespond(
                        withSuccess(
                                new RiskJsonCodec().toJson(Map.of("code", "0", "data", envelope)),
                                MediaType.APPLICATION_JSON));
    }

    private Map<String, Object> row(String window, long hours) {
        var r = new LinkedHashMap<String, Object>();
        r.put("gid", "g1");
        r.put("domain", "nurl.ink");
        r.put("shortUri", "abc");
        r.put("fullShortUrl", "nurl.ink/abc");
        r.put("linkId", 99L);
        r.put("pv", 3_000_000_000L);
        r.put("uv", 80);
        r.put("uip", 60);
        r.put("window", window);
        r.put("endExclusive", StatsTestFixtures.NOW);
        r.put("startInclusive", StatsTestFixtures.NOW - Duration.ofHours(hours).toMillis());
        return r;
    }

    @Test
    void refusesMissingSystemPrincipalBeforeNetwork() {
        props.getBusiness().setUsername("");
        assertThatThrownBy(() -> gateway.listActiveShortLinks(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void candidatePagesKeepStableIdentityAndLongCounts() {
        var meta = StatsTestFixtures.meta();
        meta.put("nextCursor", "page-2");
        respond(Map.of("items", List.of(row("requested", 168)), "metrics", Map.of(), "meta", meta));
        respond(Map.of("items", List.of(), "metrics", Map.of(), "meta", StatsTestFixtures.meta()));
        var found =
                gateway.listActiveShortLinks(
                        Instant.ofEpochMilli(StatsTestFixtures.NOW).minus(Duration.ofDays(7)));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).pv()).isEqualTo(3_000_000_000L);
        assertThat(found.get(0).uv()).isNull();
        assertThat(found.get(0).tenantId()).isEqualTo("1001");
        server.verify();
    }

    @Test
    void threeWindowsAreRetrievedInOneResponseAtCommonCut() {
        respond(
                Map.of(
                        "items",
                        List.of(row("2h", 2), row("24h", 24), row("7d", 168)),
                        "metrics",
                        Map.of(),
                        "meta",
                        StatsTestFixtures.meta()));
        var windows =
                gateway.loadStatsWindows(
                        new ShortLinkActiveCandidate("g1", "nurl.ink", "abc", "nurl.ink/abc"),
                        Instant.ofEpochMilli(StatsTestFixtures.NOW));
        assertThat(windows).containsOnlyKeys("2h", "24h", "7d");
        assertThat(windows.get("2h").pv()).isEqualTo(3_000_000_000L);
        assertThat(windows.get("2h").topIpShare()).isNull();
        assertThat(windows.get("24h").meta()).isEqualTo(windows.get("7d").meta());
        server.verify();
    }

    @Test
    void dimensionsAreCopiedPerWindowWithoutReusingGlobalQualityOrPrivateFields() {
        var shortRow = row("2h", 2);
        shortRow.put("countryStats", List.of(Map.of("country", "CN", "cnt", 12L, "ratio", 1D, "ipHash", "discard")));
        shortRow.put("localeCnStats", List.of(Map.of("locale", "广东省", "cnt", 12L, "ratio", 1D)));
        shortRow.put("networkStats", List.of(Map.of("network", "电信", "cnt", 12L, "ratio", 1D)));
        shortRow.put("uvTypeStats", List.of(Map.of("uvType", "newUser", "cnt", 2L, "ratio", .25D)));
        shortRow.put("dimensionQuality", Map.of("networkStats", Map.of("status", "AVAILABLE", "semantic", "ISP"),
                "uvTypeStats", Map.of("status", "PARTIAL", "scope", "SHORT_LINK", "unknownUv", 2L)));
        shortRow.put("topIpStats", List.of(Map.of("ipHash", "discard")));
        var longRow = row("24h", 24);
        longRow.put("dimensionQuality", Map.of("networkStats", Map.of("status", "UNKNOWN", "reason", "NOT_CONFIGURED")));
        var meta = StatsTestFixtures.meta();
        meta.put("dimensionQuality", Map.of("networkStats", Map.of("status", "AVAILABLE", "semantic", "DO_NOT_COPY")));
        respond(Map.of("items", List.of(shortRow, longRow, row("7d", 168)), "metrics", Map.of(), "meta", meta));

        var windows = gateway.loadStatsWindows(new ShortLinkActiveCandidate("g1", "nurl.ink", "abc", "nurl.ink/abc"),
                Instant.ofEpochMilli(StatsTestFixtures.NOW));
        var dimensions = windows.get("2h").dimensions();
        assertThat(dimensions.networkStats().get(0).value()).isEqualTo("电信");
        assertThat(dimensions.localeCnStats().get(0).cnt()).isEqualTo(12);
        assertThat(dimensions.startInclusive()).isEqualTo(StatsTestFixtures.NOW - Duration.ofHours(2).toMillis());
        assertThat(dimensions.dimensionQuality().get("uvTypeStats")).containsEntry("scope", "SHORT_LINK");
        assertThat(windows.get("24h").dimensions().dimensionQuality().get("networkStats")).containsEntry("status", "UNKNOWN");
        assertThat(windows.get("7d").dimensions().networkStats()).isEmpty();
        assertThat(windows.get("7d").dimensions().dimensionQuality().get("networkStats")).containsEntry("status", "UNKNOWN");
        assertThat(new RiskJsonCodec().toJson(dimensions)).doesNotContain("ipHash", "topIpStats", "discard", "DO_NOT_COPY");
        assertThat(new RiskJsonCodec().toJson(windows.get("2h").meta())).isEqualTo(new RiskJsonCodec().toJson(meta));
        server.verify();
    }

    @Test
    void metadataWithoutRecordsIsNotFabricatedIntoAWindow() {
        respond(Map.of("items", List.of(), "metrics", Map.of(), "meta", StatsTestFixtures.meta()));
        assertThatThrownBy(
                        () ->
                                gateway.loadStatsWindows(
                                        new ShortLinkActiveCandidate(
                                                "g1", "nurl.ink", "abc", "nurl.ink/abc"),
                                        Instant.ofEpochMilli(StatsTestFixtures.NOW)))
                .hasMessageContaining("incomplete");
        server.verify();
    }

    @Test
    void missingCountAndUnavailableEnvelopeFailClosed() {
        var bad = row("2h", 2);
        bad.remove("pv");
        respond(
                Map.of(
                        "items",
                        List.of(bad, row("24h", 24), row("7d", 168)),
                        "metrics",
                        Map.of(),
                        "meta",
                        StatsTestFixtures.meta()));
        assertThatThrownBy(
                        () ->
                                gateway.loadStatsWindows(
                                        new ShortLinkActiveCandidate(
                                                "g1", "nurl.ink", "abc", "nurl.ink/abc"),
                                        Instant.ofEpochMilli(StatsTestFixtures.NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void changedEpochBetweenPagesIsRejected() {
        var meta = StatsTestFixtures.meta();
        meta.put("nextCursor", "page-2");
        respond(Map.of("items", List.of(row("requested", 168)), "metrics", Map.of(), "meta", meta));
        var changed = StatsTestFixtures.meta();
        changed.put("recoveryEpoch", "epoch-2");
        respond(Map.of("items", List.of(), "metrics", Map.of(), "meta", changed));
        assertThatThrownBy(
                        () ->
                                gateway.listActiveShortLinks(
                                        Instant.ofEpochMilli(StatsTestFixtures.NOW)
                                                .minus(Duration.ofDays(7))))
                .hasMessageContaining("recoveryEpoch");
        server.verify();
    }
}
