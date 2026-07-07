package com.nageoffer.shortlink.agent.agent.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignInsightCardFactoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void sanitizeForPromptRemovesUserAndMasksIpInNestedDerivedCardContext() {
        CampaignInsightCardFactory factory = new CampaignInsightCardFactory();
        List<Map<String, Object>> derivedCards = List.of(Map.of(
                "type", "traffic_anomaly",
                "arguments", Map.of(
                        "gid", "g1",
                        "ip", "192.168.1.10",
                        "user", "user-001"
                ),
                "evidence", Map.of(
                        "ip", "10.0.0.8",
                        "user", "user-002",
                        "nested", List.of(Map.of("ip", "172.16.1.2", "user", "user-003"))
                )
        ));

        Object sanitized = factory.sanitizeForPrompt(derivedCards);

        Map<String, Object> card = ((List<Map<String, Object>>) sanitized).get(0);
        Map<String, Object> arguments = (Map<String, Object>) card.get("arguments");
        Map<String, Object> evidence = (Map<String, Object>) card.get("evidence");
        Map<String, Object> nested = ((List<Map<String, Object>>) evidence.get("nested")).get(0);
        assertThat(arguments)
                .containsEntry("gid", "g1")
                .containsEntry("ip", "192.168.*.*")
                .doesNotContainKey("user");
        assertThat(evidence)
                .containsEntry("ip", "10.0.*.*")
                .doesNotContainKey("user");
        assertThat(nested)
                .containsEntry("ip", "172.16.*.*")
                .doesNotContainKey("user");
        assertThat(sanitized.toString())
                .doesNotContain("192.168.1.10")
                .doesNotContain("10.0.0.8")
                .doesNotContain("172.16.1.2")
                .doesNotContain("user-001")
                .doesNotContain("user-002")
                .doesNotContain("user-003");
    }
}
