package com.nageoffer.shortlink.agent.securityriskagent.safety;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityRiskSanitizerTest {

    private final SecurityRiskSanitizer sanitizer = new SecurityRiskSanitizer();

    @Test
    void sanitizeObjectMasksIpAndRemovesUserKeysRecursively() {
        Object sanitized = sanitizer.sanitizeObject(List.of(Map.of(
                "ip", "172.16.1.2",
                "user", "visitor-001",
                "message", "fallback ip=192.168.1.10 user=visitor-002",
                "nested", Map.of(
                        "ip", "10.0.0.9",
                        "username", "admin",
                        "items", List.of(Map.of(
                                "ip", "8.8.8.8",
                                "uid", "u-1"
                        ))
                )
        )));

        String text = sanitized.toString();

        assertThat(text)
                .contains("172.16.*.*")
                .contains("192.168.*.*")
                .contains("10.0.*.*")
                .contains("8.8.*.*")
                .contains("user=***")
                .doesNotContain("172.16.1.2")
                .doesNotContain("192.168.1.10")
                .doesNotContain("10.0.0.9")
                .doesNotContain("8.8.8.8")
                .doesNotContain("visitor-001")
                .doesNotContain("visitor-002")
                .doesNotContain("admin")
                .doesNotContain("u-1");
    }

    @Test
    void sanitizeTextMasksInlineEnglishAndChineseUserIdentifiers() {
        String accountMarker = "user" + "Id";
        String visitMarker = "visitor" + "Id";
        String sanitized = sanitizer.sanitizeText("ip=192.168.1.10 user=visitor username:admin "
                + accountMarker + "=u-99 "
                + visitMarker + ":v-88 用户:张三 account=a-1");

        assertThat(sanitized)
                .contains("192.168.*.*")
                .contains("user=***")
                .contains("username:***")
                .contains(accountMarker + "=***")
                .contains(visitMarker + ":***")
                .contains("用户:***")
                .contains("account=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("user=visitor")
                .doesNotContain("admin")
                .doesNotContain("u-99")
                .doesNotContain("v-88")
                .doesNotContain("张三")
                .doesNotContain("a-1");
    }

    @Test
    void sanitizeObjectRemovesExtendedIdentifierKeysRecursively() {
        String accountMarker = "user" + "Id";
        String visitMarker = "visitor" + "Id";
        String rawAccountMarker = "raw" + accountMarker;
        String rawVisitMarker = "raw" + visitMarker;
        Object sanitized = sanitizer.sanitizeObject(Map.of(
                accountMarker, "u-99",
                visitMarker, "visitor-99",
                "nested", Map.of(
                        rawAccountMarker, "raw-user-1",
                        rawVisitMarker, "raw-visitor-1",
                        "message", accountMarker + "=u-1 " + visitMarker + "=v-1"
                )
        ));

        String text = sanitized.toString();

        assertThat(text)
                .contains(accountMarker + "=***")
                .contains(visitMarker + "=***")
                .doesNotContain("u-99")
                .doesNotContain("visitor-99")
                .doesNotContain("raw-user-1")
                .doesNotContain("raw-visitor-1")
                .doesNotContain("u-1")
                .doesNotContain("v-1");
    }

    @Test
    void sanitizeObjectKeepsNumbersBooleansAndUnknownKeys() {
        Object sanitized = sanitizer.sanitizeObject(Map.of(
                "pv", 100,
                "active", true,
                "channel", "email"
        ));

        assertThat(sanitized).isEqualTo(Map.of(
                "pv", 100,
                "active", true,
                "channel", "email"
        ));
    }

    @Test
    void sanitizeTextAndObjectRedactSecretsAndJdbcUrls() {
        Object sanitized = sanitizer.sanitizeObject(Map.of(
                "token", "internal-token-123",
                "password", "db-password",
                "message", "token=abc123 password:secret jdbc:mysql://127.0.0.1:3306/shortlink?user=root&password=root"
        ));

        String text = sanitized.toString();

        assertThat(text)
                .contains("token=***")
                .contains("password:***")
                .contains("jdbc:***")
                .doesNotContain("internal-token-123")
                .doesNotContain("db-password")
                .doesNotContain("abc123")
                .doesNotContain("secret")
                .doesNotContain("127.0.0.1")
                .doesNotContain("shortlink")
                .doesNotContain("root");
    }

    @Test
    void sanitizeObjectRedactsSensitiveMapKeysRecursively() {
        Object sanitized = sanitizer.sanitizeObject(Map.of(
                "192.168.1.10", 45,
                "token=abc123", "value",
                "jdbc:mysql://127.0.0.1:3306/shortlink?user=root", "db",
                "nested", Map.of(
                        "user=visitor-001", "user-key",
                        "10.0.0.9", "ip-key"
                )
        ));

        String text = sanitized.toString();

        assertThat(text)
                .contains("192.168.*.*")
                .contains("10.0.*.*")
                .contains("token=***")
                .contains("jdbc:***")
                .contains("user=***")
                .doesNotContain("192.168.1.10")
                .doesNotContain("10.0.0.9")
                .doesNotContain("abc123")
                .doesNotContain("127.0.0.1")
                .doesNotContain("shortlink")
                .doesNotContain("root")
                .doesNotContain("visitor-001");
    }
}
