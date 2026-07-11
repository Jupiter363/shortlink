package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskpolicy.action.RiskIpEvidence;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskIpEvidenceExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskIpEvidenceExtractorTest {

    private static final String TARGET = "nurl.ink/abc123";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private final RiskIpEvidenceExtractor extractor = new RiskIpEvidenceExtractor();

    @Test
    void extractorIsAStatelessComponent() {
        assertThat(RiskIpEvidenceExtractor.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(RiskIpEvidenceExtractor.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())).isTrue());
    }

    @Test
    void extractsHighestCountSafeEvidenceFromMatchingSuccessfulShortLinkStats() {
        List<Map<String, Object>> executions = List.of(
                execution("get_short_link_stats", true, TARGET, Map.of(
                        "pv", 100,
                        "topIpStats", List.of(
                                Map.of("ipHash", HASH_A, "maskedIp", "192.0.*.*", "cnt", 18),
                                Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 42)
                        )
                )),
                execution("get_short_link_stats", true, "nurl.ink/other", Map.of(
                        "topIpStats", List.of(Map.of(
                                "ipHash", "c".repeat(64), "maskedIp", "203.0.*.*", "cnt", 99
                        ))
                ))
        );

        Optional<RiskIpEvidence> evidence = extractor.extract(executions, TARGET);

        assertThat(evidence).contains(new RiskIpEvidence(HASH_B, "198.51.*.*", 42));
    }

    @Test
    void ignoresInvalidRowsAndRowsContainingAnyRawIpField() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("ipHash", HASH_A, "maskedIp", "192.0.*.*", "cnt", 7));
        rows.add(Map.of("ipHash", "A".repeat(64), "maskedIp", "198.51.*.*", "cnt", 90));
        rows.add(Map.of("ipHash", "f".repeat(63), "maskedIp", "198.51.*.*", "cnt", 80));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", " ", "cnt", 70));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 0));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", "not-a-count"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "ip", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "rawIp", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "raw_ip", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "ipAddress", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "clientIp", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "remoteIp", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "sourceIp", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "raw-ip", "198.51.100.8"));
        rows.add(Map.of("ipHash", HASH_B, "maskedIp", "198.51.*.*", "cnt", 60, "label", "unapproved"));

        Optional<RiskIpEvidence> evidence = extractor.extract(
                List.of(execution("get_short_link_stats", true, TARGET, Map.of("topIpStats", rows))),
                TARGET
        );

        assertThat(evidence).contains(new RiskIpEvidence(HASH_A, "192.0.*.*", 7));
    }

    @Test
    void neverHashesRawIpRowsOnItsOwn() {
        Map<String, Object> rawOnly = Map.of("ip", "192.0.2.44", "cnt", 100);
        Map<String, Object> mixed = Map.of(
                "ip", "198.51.100.8",
                "ipHash", HASH_A,
                "maskedIp", "198.51.*.*",
                "cnt", 90
        );

        assertThat(extractor.extract(List.of(execution(
                "get_short_link_stats", true, TARGET, Map.of("topIpStats", List.of(rawOnly, mixed))
        )), TARGET)).isEmpty();
    }

    @Test
    void ignoresWrongToolFailedExecutionTargetMismatchAndShareOnlyData() {
        List<Map<String, Object>> executions = List.of(
                execution("get_group_stats", true, TARGET, safeData()),
                execution("get_short_link_stats", false, TARGET, safeData()),
                execution("get_short_link_stats", true, TARGET + "/other", safeData()),
                execution("get_short_link_stats", true, TARGET, Map.of("topIpShare", 0.9D)),
                Map.of(
                        "name", "get_short_link_stats",
                        "success", true,
                        "arguments", Map.of("gid", "g1"),
                        "data", safeData()
                )
        );

        assertThat(extractor.extract(executions, TARGET)).isEmpty();
    }

    @Test
    void ignoresMalformedExecutionAndDataShapes() {
        Map<String, Object> nullData = new LinkedHashMap<>();
        nullData.put("name", "get_short_link_stats");
        nullData.put("success", true);
        nullData.put("arguments", Map.of("fullShortUrl", TARGET));
        nullData.put("data", null);

        assertThat(extractor.extract(null, TARGET)).isEmpty();
        assertThat(extractor.extract(List.of(), TARGET)).isEmpty();
        assertThat(extractor.extract(List.of(nullData), null)).isEmpty();
        assertThat(extractor.extract(List.of(nullData), TARGET)).isEmpty();
        assertThat(extractor.extract(List.of(execution(
                "get_short_link_stats", true, TARGET, Map.of("topIpStats", "not-a-list")
        )), TARGET)).isEmpty();
    }

    @Test
    void evidenceRejectsUnsafeOrMalformedValuesAndHasStableSafeToString() {
        RiskIpEvidence evidence = new RiskIpEvidence(HASH_A, "192.0.*.*", 12);
        RiskIpEvidence ipv6Evidence = new RiskIpEvidence(HASH_B, "2001:db8:*:*", 10);

        assertThat(evidence.toString()).isEqualTo(
                "RiskIpEvidence[ipHash=" + HASH_A + ", maskedIp=192.0.*.*, count=12]"
        );
        assertThat(evidence.toString()).doesNotContain("192.0.2.44");
        assertThat(ipv6Evidence.maskedIp()).isEqualTo("2001:db8:*:*");
        assertThatThrownBy(() -> new RiskIpEvidence("A".repeat(64), "192.0.*.*", 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk IP evidence is invalid");
        assertThatThrownBy(() -> new RiskIpEvidence(HASH_A, "192.0.2.44", 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk IP evidence is invalid");
        assertThatThrownBy(() -> new RiskIpEvidence(HASH_A, "2001:db8::44", 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk IP evidence is invalid");
        assertThatThrownBy(() -> new RiskIpEvidence(HASH_A, " ", 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk IP evidence is invalid");
        assertThatThrownBy(() -> new RiskIpEvidence(HASH_A, "192.0.*.*", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk IP evidence is invalid");
    }

    private Map<String, Object> safeData() {
        return Map.of("topIpStats", List.of(Map.of(
                "ipHash", HASH_A,
                "maskedIp", "192.0.*.*",
                "cnt", 12
        )));
    }

    private Map<String, Object> execution(
            String name,
            boolean success,
            String fullShortUrl,
            Object data
    ) {
        return Map.of(
                "name", name,
                "success", success,
                "arguments", Map.of("fullShortUrl", fullShortUrl),
                "data", data
        );
    }
}
