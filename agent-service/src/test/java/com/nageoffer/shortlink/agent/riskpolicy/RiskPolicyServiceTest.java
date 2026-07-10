package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskPolicyServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void activatesAndDisablesShortLinkPolicyWithRedisAndAudit() {
        TestFixture fixture = fixture("risk_policy_service", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);

        RiskPolicy policy = fixture.service.activatePolicy(RiskPolicyActivationCommand.shortLink(
                "policy-001",
                RiskPolicyAction.DISABLE_SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"DISABLE_SHORT_LINK\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed high risk",
                "trace-001",
                "event-001"
        ));

        assertThat(fixture.policyRepository.findByPolicyId("policy-001")).isPresent();
        assertThat(fixture.policyRepository.findByPolicyId("policy-001").get().status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(fixture.auditRepository.countByPolicyId("policy-001")).isEqualTo(1);
        verify(fixture.valueOperations).set(policy.policyKey(), policy.policyPayloadJson());

        fixture.service.disablePolicy(new RiskPolicyDisableCommand(
                "policy-001",
                "gid-001",
                "manual-user",
                "false positive",
                "trace-002"
        ));

        assertThat(fixture.policyRepository.findByPolicyId("policy-001").get().status()).isEqualTo(RiskPolicyStatus.DISABLED);
        assertThat(fixture.auditRepository.countByPolicyId("policy-001")).isEqualTo(2);
        verify(fixture.stringRedisTemplate).delete(policy.policyKey());
    }

    @Test
    void disablePolicyRejectsCommandGidDifferentFromPersistedPolicyGid() {
        TestFixture fixture = fixture("risk_policy_disable_gid_guard", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        RiskPolicy policy = fixture.service.activatePolicy(RiskPolicyActivationCommand.shortLink(
                "policy-guard-001",
                RiskPolicyAction.LIMIT_RATE,
                "owner-gid",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed high risk",
                "trace-001",
                "event-001"
        ));

        assertThatThrownBy(() -> fixture.service.disablePolicy(new RiskPolicyDisableCommand(
                "policy-guard-001",
                "other-gid",
                "manual-user",
                "false positive",
                "trace-002"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy is not owned by gid: other-gid");

        assertThat(fixture.policyRepository.findByPolicyId("policy-guard-001").get().status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        verify(fixture.stringRedisTemplate, never()).delete(policy.policyKey());
    }

    @Test
    void blockIpPolicyRequiresHashSaltAndDoesNotPersistRawIp() {
        TestFixture fixture = fixture("risk_policy_block_ip", "");

        assertThatThrownBy(() -> fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-001",
                "gid-001",
                "203.0.113.8",
                "{\"action\":\"BLOCK_IP\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed abusive source",
                "trace-001",
                "event-001"
        ))).isInstanceOf(IllegalStateException.class);

        verify(fixture.stringRedisTemplate, never()).opsForValue();
        assertThat(fixture.policyRepository.findByPolicyId("policy-ip-001")).isEmpty();
    }

    @Test
    void marksExpiredPolicyWhenExpireTimeAlreadyPassed() {
        TestFixture fixture = fixture("risk_policy_expired", "risk-test-salt");

        RiskPolicy policy = fixture.service.activatePolicy(new RiskPolicyActivationCommand(
                "policy-expired-001",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "expired before publish",
                "trace-001",
                "event-001",
                LocalDateTime.of(2026, 7, 10, 10, 59)
        ));

        assertThat(policy.status()).isEqualTo(RiskPolicyStatus.EXPIRED);
        assertThat(fixture.policyRepository.findByPolicyId("policy-expired-001").get().status()).isEqualTo(RiskPolicyStatus.EXPIRED);
        verify(fixture.stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void repeatedActivationWithSamePolicyIdWritesOneActivationAudit() {
        TestFixture fixture = fixture("risk_policy_activation_idempotency", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        RiskPolicyActivationCommand command = RiskPolicyActivationCommand.shortLink(
                "policy-auto-stable",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "automatic retry-safe activation",
                "trace-stable",
                "event-stable"
        );

        fixture.service.activatePolicy(command);
        fixture.service.activatePolicy(command);

        assertThat(fixture.policyRepository.findByPolicyId("policy-auto-stable")).isPresent();
        assertThat(fixture.auditRepository.countByPolicyId("policy-auto-stable")).isEqualTo(1);
    }

    @Test
    void limitRateAutoActionRequiresHighRiskScoreAndTwoStrongReasons() {
        TestFixture fixture = fixture("risk_policy_auto", "risk-test-salt");

        assertThat(fixture.service.canAutoLimitRate(
                RiskLevel.HIGH,
                80,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION)
        )).isTrue();
        assertThat(fixture.service.canAutoLimitRate(
                RiskLevel.HIGH,
                80,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE)
        )).isFalse();
        assertThat(fixture.service.canAutoLimitRate(
                RiskLevel.MEDIUM,
                90,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION)
        )).isFalse();
        assertThat(fixture.service.canAutoLimitRate(
                RiskLevel.HIGH,
                79,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE, RiskReasonCode.IP_CONCENTRATION)
        )).isFalse();
    }

    private TestFixture fixture(String databaseName, String hashSalt) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository = new JdbcRiskActionAuditRepository(jdbcTemplate);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        AgentProperties properties = new AgentProperties();
        properties.getRisk().setHashSalt(hashSalt);
        RiskPolicyRedisPublisher publisher = new RiskPolicyRedisPublisher(stringRedisTemplate, CLOCK);
        return new TestFixture(
                policyRepository,
                auditRepository,
                stringRedisTemplate,
                valueOperations,
                new RiskPolicyService(policyRepository, auditRepository, publisher, properties)
        );
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record TestFixture(
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            StringRedisTemplate stringRedisTemplate,
            ValueOperations<String, String> valueOperations,
            RiskPolicyService service
    ) {
    }
}
