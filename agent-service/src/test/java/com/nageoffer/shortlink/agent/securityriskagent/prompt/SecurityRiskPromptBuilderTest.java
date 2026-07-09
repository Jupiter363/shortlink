package com.nageoffer.shortlink.agent.securityriskagent.prompt;

import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRiskPromptBuilderTest {

    private final SecurityRiskPromptBuilder promptBuilder = new SecurityRiskPromptBuilder(new SecurityRiskSanitizer());

    @Test
    void systemPromptDefinesReadOnlySecurityRiskAgentBoundary() {
        String prompt = promptBuilder.systemPrompt();

        assertThat(prompt)
                .contains("Security Risk Agent")
                .contains("Do not execute write actions directly")
                .contains("Do not expose raw IP addresses");
    }

    @Test
    void userPromptIncludesOnlySanitizedMessageToolsAndRiskSignals() {
        String prompt = promptBuilder.userPrompt(
                "check ip=192.168.1.10 user=visitor-001 token=abc jdbc:mysql://127.0.0.1:3306/shortlink",
                List.of(Map.of(
                        "name", "get_group_access_records",
                        "success", true,
                        "data", Map.of(
                                "ip", "10.0.0.9",
                                "username", "admin",
                                "rawData", List.of(Map.of("ip", "172.16.1.2", "uid", "u-001"))
                        )
                )),
                List.of(Map.of(
                        "type", "risk_signal",
                        "evidence", Map.of("maskedTopIp", "192.168.*.*", "token", "risk-token")
                ))
        );

        assertThat(prompt)
                .contains("Sanitized tool context")
                .contains("Risk signal context")
                .contains("192.168.*.*")
                .contains("10.0.*.*")
                .contains("172.16.*.*")
                .contains("token=***")
                .contains("jdbc:***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("10.0.0.9")
                .doesNotContain("172.16.1.2")
                .doesNotContain("visitor-001")
                .doesNotContain("admin")
                .doesNotContain("u-001")
                .doesNotContain("risk-token")
                .doesNotContain("127.0.0.1")
                .doesNotContain("shortlink");
    }
}
