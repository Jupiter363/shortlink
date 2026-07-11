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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskPolicyServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final LocalDateTime EFFECTIVE_TIME = LocalDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());
    private static final String RAW_IP = "203.0.113.8";
    private static final String IP_HASH = "ddbea5471056690e5b1dcfe0c39ffca2f91ee81710048d066e2d53c7d012e14e";

    @Test
    void activatesAndDisablesShortLinkPolicyWithRedisAndAudit() {
        TestFixture fixture = fixture("risk_policy_service", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);

        RiskPolicy policy = fixture.service.activatePolicy(RiskPolicyActivationCommand.shortLink(
                "policy-001",
                "activate:policy-001",
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

        RiskPolicy persisted = fixture.policyRepository.findByPolicyId("policy-001").orElseThrow();
        assertThat(persisted).isEqualTo(policy);
        assertThat(persisted.status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(persisted.policyVersion()).isEqualTo(1L);
        assertThat(persisted.effectiveTime()).isEqualTo(EFFECTIVE_TIME);
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
                "activate:policy-guard-001",
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
    void blockIpPolicyPersistsOnlyHashAndRejectsRawIp() {
        TestFixture fixture = fixture("risk_policy_block_ip", "risk-test-salt");

        assertThatThrownBy(() -> fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-raw",
                "activate:policy-ip-raw",
                "gid-001",
                RAW_IP,
                "{\"action\":\"BLOCK_IP\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed abusive source",
                "trace-001",
                "event-001"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ipHash must be a lowercase SHA-256 value");

        verify(fixture.stringRedisTemplate, never()).opsForValue();
        assertThat(fixture.policyRepository.findByPolicyId("policy-ip-raw")).isEmpty();

        assertThatThrownBy(() -> fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-payload-raw",
                "activate:policy-ip-payload-raw",
                "gid-001",
                IP_HASH,
                "{\"action\":\"BLOCK_IP\",\"rawIp\":\"" + RAW_IP + "\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed abusive source",
                "trace-raw-payload",
                "event-raw-payload"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk payload contains sensitive data");
        assertThat(fixture.policyRepository.findByPolicyId("policy-ip-payload-raw")).isEmpty();

        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        RiskPolicy policy = fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-hash",
                "activate:policy-ip-hash",
                "gid-001",
                IP_HASH,
                "{\"action\":\"BLOCK_IP\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "confirmed abusive source",
                "trace-002",
                "event-002"
        ));

        RiskPolicy persisted = fixture.policyRepository.findByPolicyId("policy-ip-hash").orElseThrow();
        assertThat(persisted).isEqualTo(policy);
        assertThat(persisted.ipHash()).isEqualTo(IP_HASH);
        assertThat(persisted.policyKey()).contains(IP_HASH).doesNotContain(RAW_IP);
        assertThat(persisted.toString()).doesNotContain(RAW_IP);
        assertThat(persisted.policyVersion()).isEqualTo(1L);
        assertThat(persisted.effectiveTime()).isEqualTo(EFFECTIVE_TIME);
        verify(fixture.valueOperations).set(policy.policyKey(), policy.policyPayloadJson());
    }

    @Test
    void marksExpiredPolicyWhenExpireTimeAlreadyPassed() {
        TestFixture fixture = fixture("risk_policy_expired", "risk-test-salt");

        RiskPolicy policy = fixture.service.activatePolicy(new RiskPolicyActivationCommand(
                "policy-expired-001",
                "activate:policy-expired-001",
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
        assertThat(policy.policyVersion()).isEqualTo(1L);
        assertThat(policy.effectiveTime()).isEqualTo(EFFECTIVE_TIME);
        assertThat(fixture.policyRepository.findByPolicyId("policy-expired-001").get().status()).isEqualTo(RiskPolicyStatus.EXPIRED);
        verify(fixture.stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void repeatedActivationWithSameIdempotencyKeyWritesPolicyRedisAndAuditOnce() {
        TestFixture fixture = fixture("risk_policy_activation_idempotency", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        RiskPolicyActivationCommand firstCommand = RiskPolicyActivationCommand.shortLink(
                "policy-auto-stable",
                "auto:batch-001:gid-001:nurl.ink:abc123:LIMIT_RATE",
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
        RiskPolicyActivationCommand retryCommand = RiskPolicyActivationCommand.shortLink(
                "policy-auto-stable",
                firstCommand.idempotencyKey(),
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "automatic retry-safe activation",
                "trace-retry",
                "event-retry"
        );

        RiskPolicy first = fixture.service.activatePolicy(firstCommand);
        RiskPolicy retry = fixture.service.activatePolicy(retryCommand);

        assertThat(retry).isEqualTo(first);
        assertThat(first.idempotencyKey()).isEqualTo(firstCommand.idempotencyKey());
        assertThat(first.policyVersion()).isEqualTo(1L);
        assertThat(first.effectiveTime()).isEqualTo(EFFECTIVE_TIME);
        assertThat(fixture.policyRepository.findByPolicyKeyOrderByVersion(first.policyKey()))
                .singleElement()
                .isEqualTo(first);
        assertThat(fixture.auditRepository.countByPolicyId("policy-auto-stable")).isEqualTo(1);
        verify(fixture.valueOperations, times(1)).set(first.policyKey(), first.policyPayloadJson());
    }

    @Test
    void retriesIncompleteActivationAndRejectsIdempotencyPayloadConflict() {
        TestFixture fixture = fixture("risk_policy_activation_recovery", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        doThrow(new IllegalStateException("redis unavailable"))
                .doNothing()
                .when(fixture.valueOperations)
                .set(anyString(), anyString());
        RiskPolicyActivationCommand command = RiskPolicyActivationCommand.shortLink(
                "policy-recovery",
                "auto:recovery",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "recovery",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "retry incomplete activation",
                "trace-first",
                "event-first"
        );

        assertThatThrownBy(() -> fixture.service.activatePolicy(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
        assertThat(fixture.policyRepository.findByPolicyId("policy-recovery"))
                .isPresent()
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(fixture.auditRepository.countByPolicyId("policy-recovery")).isZero();

        RiskPolicy recovered = fixture.service.activatePolicy(command);

        assertThat(recovered.policyVersion()).isEqualTo(1L);
        assertThat(fixture.auditRepository.countByPolicyId("policy-recovery")).isEqualTo(1);
        verify(fixture.valueOperations, times(2)).set(recovered.policyKey(), recovered.policyPayloadJson());

        RiskPolicyActivationCommand conflicting = RiskPolicyActivationCommand.shortLink(
                command.policyId(),
                command.idempotencyKey(),
                command.action(),
                "other-gid",
                command.domain(),
                command.shortUri(),
                command.policyPayloadJson(),
                command.source(),
                command.executor(),
                command.reason(),
                "trace-conflict",
                "event-conflict"
        );
        assertThatThrownBy(() -> fixture.service.activatePolicy(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Risk policy idempotency conflict");
        assertThat(fixture.policyRepository.findByPolicyKeyOrderByVersion(recovered.policyKey()))
                .singleElement()
                .isEqualTo(recovered);
    }

    @Test
    void rejectsDisablingSupersededPolicyWithoutDeletingCurrentRedisValue() {
        TestFixture fixture = fixture("risk_policy_disable_stale_version", "risk-test-salt");
        when(fixture.stringRedisTemplate.opsForValue()).thenReturn(fixture.valueOperations);
        RiskPolicy first = fixture.service.activatePolicy(RiskPolicyActivationCommand.shortLink(
                "policy-v1",
                "manual:policy-v1",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "first policy",
                "trace-v1",
                "event-v1"
        ));
        RiskPolicy second = fixture.service.activatePolicy(RiskPolicyActivationCommand.shortLink(
                "policy-v2",
                "manual:policy-v2",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                "{\"action\":\"LIMIT_RATE\",\"limit\":30,\"windowSeconds\":60}",
                RiskPolicySource.MANUAL_REVIEW,
                "manual-user",
                "replacement policy",
                "trace-v2",
                "event-v2"
        ));

        assertThat(fixture.policyRepository.findByPolicyId(first.policyId()))
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.SUPERSEDED);
        assertThat(second.policyVersion()).isEqualTo(2L);
        assertThatThrownBy(() -> fixture.service.disablePolicy(new RiskPolicyDisableCommand(
                first.policyId(),
                first.gid(),
                "manual-user",
                "stale disable",
                "trace-disable-stale"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("Risk policy is not the active version: " + first.policyId());
        verify(fixture.stringRedisTemplate, never()).delete(anyString());
        assertThat(fixture.policyRepository.findActiveByPolicyKey(second.policyKey()))
                .contains(second);
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
                new RiskPolicyService(policyRepository, auditRepository, publisher, properties, CLOCK)
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
