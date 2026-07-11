package com.nageoffer.shortlink.agent.riskcommon;

import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.redis.RiskPolicyRedisKeyBuilder;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskHashService;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskSensitiveDataGuard;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskCommonModelTest {

    @Test
    void riskTablesAreCreatedByAgentServiceSchema() {
        DataSource dataSource = h2DataSource("risk_schema");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertThat(tableExists(jdbcTemplate, "t_agent_risk_event")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_risk_snapshot")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_short_link_risk_profile")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_group_risk_profile")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_risk_review")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_risk_policy")).isTrue();
        assertThat(tableExists(jdbcTemplate, "t_agent_risk_action_audit")).isTrue();
    }

    @Test
    void riskEnumsExposeStableContracts() {
        assertThat(RiskLevel.fromScore(0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromScore(39)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromScore(40)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromScore(69)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromScore(70)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.fromScore(100)).isEqualTo(RiskLevel.HIGH);

        assertThat(RiskTargetType.values()).containsExactly(RiskTargetType.GROUP, RiskTargetType.SHORT_LINK);
        assertThat(RiskReasonCode.values()).contains(
                RiskReasonCode.TRAFFIC_SPIKE,
                RiskReasonCode.IP_CONCENTRATION,
                RiskReasonCode.HIGH_REPEAT_VISIT,
                RiskReasonCode.PEAK_HOUR_BURST,
                RiskReasonCode.DEVICE_CONCENTRATION,
                RiskReasonCode.REGION_CONCENTRATION,
                RiskReasonCode.BROWSER_CONCENTRATION
        );
        assertThat(RiskPolicyAction.DISABLE_SHORT_LINK.requiresManualReview()).isTrue();
        assertThat(RiskPolicyAction.BLOCK_IP.requiresManualReview()).isTrue();
        assertThat(RiskPolicyAction.LIMIT_TIME_WINDOW.requiresManualReview()).isTrue();
        assertThat(RiskPolicyAction.LIMIT_RATE.requiresManualReview()).isFalse();
        assertThat(RiskPolicyStatus.values()).containsExactly(
                RiskPolicyStatus.ACTIVE,
                RiskPolicyStatus.SUPERSEDED,
                RiskPolicyStatus.DISABLED,
                RiskPolicyStatus.EXPIRED
        );
        assertThat(RiskReviewAction.values()).contains(
                RiskReviewAction.CONFIRM_RISK,
                RiskReviewAction.FALSE_POSITIVE,
                RiskReviewAction.IGNORE,
                RiskReviewAction.WATCH,
                RiskReviewAction.UNWATCH
        );
    }

    @Test
    void sharedUtilitiesHashSerializeGuardAndBuildRedisKeys() {
        RiskHashService hashService = new RiskHashService("risk-test-salt");
        String ipHash = hashService.sha256("203.0.113.8");

        assertThat(ipHash).hasSize(64);
        assertThat(ipHash).doesNotContain("203.0.113.8");
        assertThat(hashService.sha256("203.0.113.8")).isEqualTo(ipHash);
        assertThatThrownBy(() -> new RiskHashService(" ").sha256("203.0.113.8"))
                .isInstanceOf(IllegalStateException.class);

        RiskPolicyRedisKeyBuilder keyBuilder = new RiskPolicyRedisKeyBuilder("risk");
        assertThat(keyBuilder.disableShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:disable:nurl.ink:abc123");
        assertThat(keyBuilder.rateLimitShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:rate-limit:nurl.ink:abc123");
        assertThat(keyBuilder.timeWindowShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:time-window:nurl.ink:abc123");
        assertThat(keyBuilder.blockIpKey("hash001"))
                .isEqualTo("risk:policy:ip:block:hash001");
        assertThat(keyBuilder.rateCounterKey("nurl.ink", "abc123", "hash001"))
                .isEqualTo("risk:rate:nurl.ink:abc123:hash001");

        RiskJsonCodec jsonCodec = new RiskJsonCodec();
        String json = jsonCodec.toJson(Map.of("riskLevel", "HIGH", "score", 91));
        Map<?, ?> parsed = jsonCodec.fromJson(json, Map.class);
        assertThat(parsed.get("riskLevel")).isEqualTo("HIGH");
        assertThat(parsed.get("score")).isEqualTo(91);

        RiskSensitiveDataGuard guard = new RiskSensitiveDataGuard();
        guard.requireSafe("{\"ipHash\":\"abc\",\"riskLevel\":\"HIGH\"}");
        assertThatThrownBy(() -> guard.requireSafe("{\"rawIp\":\"203.0.113.8\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.requireSafe("{\"source\":\"2001:db8::44\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.requireSafe("{\"visitorId\":\"visitor-001\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }
}
