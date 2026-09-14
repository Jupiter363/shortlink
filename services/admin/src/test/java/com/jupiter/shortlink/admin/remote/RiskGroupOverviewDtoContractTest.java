package com.jupiter.shortlink.admin.remote;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.dto.resp.RiskGroupOverviewRespDTO;
import org.junit.jupiter.api.Test;

class RiskGroupOverviewDtoContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unevaluatedAgentEnvelopeKeepsNullMetricsThroughAdminSerialization() throws Exception {
        Result<RiskGroupOverviewRespDTO> result = mapper.readValue("""
                {"code":"0","data":{
                  "gid":"g1","profileStatus":"NOT_EVALUATED",
                  "totalShortLinksScanned":null,"lowRiskCount":null,
                  "mediumRiskCount":null,"highRiskCount":null,
                  "watchingCount":1,"disabledCount":null,"avgRiskScore":null,
                  "maxRiskScore":null,"groupRiskScore":null,"groupRiskLevel":"UNKNOWN",
                  "groupReasonCodes":[],"topRiskShortLinks":[],"riskTrend7d":[],
                  "agentSummary":null,"manualReview":{"action":"FALSE_POSITIVE"}
                }}
                """, new TypeReference<>() {});

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getProfileStatus()).isEqualTo("NOT_EVALUATED");
        JsonNode data = mapper.readTree(mapper.writeValueAsString(result)).path("data");
        for (String field : new String[] {
                "totalShortLinksScanned", "lowRiskCount", "mediumRiskCount", "highRiskCount",
                "disabledCount", "avgRiskScore", "maxRiskScore", "groupRiskScore"
        }) {
            assertThat(data.has(field)).as("%s is serialized explicitly", field).isTrue();
            assertThat(data.path(field).isNull()).as("%s stays unknown", field).isTrue();
        }
        assertThat(data.path("groupRiskLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(data.path("watchingCount").asLong()).isEqualTo(1);
        assertThat(data.path("manualReview").path("action").asText()).isEqualTo("FALSE_POSITIVE");
    }

    @Test
    void readyProfileRetainsRealZeroScoreAndLongCounters() throws Exception {
        RiskGroupOverviewRespDTO dto = mapper.readValue("""
                {"gid":"g1","profileStatus":"READY","totalShortLinksScanned":3000000000,
                 "lowRiskCount":3000000000,"mediumRiskCount":0,"highRiskCount":0,
                 "avgRiskScore":0.0,"maxRiskScore":0,"groupRiskScore":0,
                 "groupRiskLevel":"LOW"}
                """, RiskGroupOverviewRespDTO.class);

        assertThat(dto.getProfileStatus()).isEqualTo("READY");
        assertThat(dto.getTotalShortLinksScanned()).isEqualTo(3_000_000_000L);
        assertThat(dto.getLowRiskCount()).isEqualTo(3_000_000_000L);
        assertThat(dto.getAvgRiskScore()).isZero();
        assertThat(dto.getMaxRiskScore()).isZero();
        assertThat(dto.getGroupRiskScore()).isZero();
        assertThat(dto.getGroupRiskLevel()).isEqualTo("LOW");
    }

    @Test
    void omittedMetricsDoNotAcquirePrimitiveZeroDefaults() throws Exception {
        RiskGroupOverviewRespDTO dto = mapper.readValue("{\"gid\":\"g1\"}", RiskGroupOverviewRespDTO.class);

        assertThat(dto.getTotalShortLinksScanned()).isNull();
        assertThat(dto.getLowRiskCount()).isNull();
        assertThat(dto.getMediumRiskCount()).isNull();
        assertThat(dto.getHighRiskCount()).isNull();
        assertThat(dto.getWatchingCount()).isNull();
        assertThat(dto.getAvgRiskScore()).isNull();
        assertThat(dto.getMaxRiskScore()).isNull();
        assertThat(dto.getGroupRiskScore()).isNull();
    }
}
