package com.nageoffer.shortlink.agent.riskpolicy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisValueCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicyRedisValueCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RiskPolicyRedisValueCodec codec = new RiskPolicyRedisValueCodec(objectMapper);

    @Test
    void enrichesExistingPayloadWithPolicyMetadataInStableOrder() throws Exception {
        String value = codec.encode(
                "policy-1",
                3L,
                "{\"windowSeconds\":60,\"limit\":30,\"action\":\"LIMIT_RATE\"}"
        );

        assertThat(value).isEqualTo(
                "{\"action\":\"LIMIT_RATE\",\"limit\":30,\"policyId\":\"policy-1\",\"policyVersion\":3,\"windowSeconds\":60}"
        );
        assertThat(objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
        })).containsEntry("policyId", "policy-1")
                .containsEntry("policyVersion", 3)
                .containsEntry("action", "LIMIT_RATE")
                .containsEntry("limit", 30)
                .containsEntry("windowSeconds", 60);
    }

    @Test
    void overridesUntrustedMetadataAndCanonicalizesEquivalentPayloads() {
        String first = codec.encode(
                "policy-current",
                7L,
                "{\"policyVersion\":99,\"action\":\"LIMIT_RATE\",\"policyId\":\"spoofed\",\"limit\":10}"
        );
        String second = codec.encode(
                "policy-current",
                7L,
                "{\"limit\":10,\"policyId\":\"ignored\",\"action\":\"LIMIT_RATE\",\"policyVersion\":1}"
        );

        assertThat(first).isEqualTo(second)
                .contains("\"policyId\":\"policy-current\"")
                .contains("\"policyVersion\":7")
                .doesNotContain("spoofed", "99");
    }

    @Test
    void rejectsInvalidIdentityAndNonObjectPayloadWithoutEchoingPayload() {
        assertThatThrownBy(() -> codec.encode(" ", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("policyId must not be blank");
        assertThatThrownBy(() -> codec.encode("policy-1", 0L, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("policyVersion must be positive");
        assertThatThrownBy(() -> codec.encode("policy-1", 1L, "[\"sensitive-value\"]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy payload must be a JSON object")
                .hasMessageNotContaining("sensitive-value");
        assertThatThrownBy(() -> codec.encode("policy-1", 1L, "{not-json}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy payload must be a JSON object")
                .hasMessageNotContaining("not-json");
    }
}
