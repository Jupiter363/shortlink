package com.nageoffer.shortlink.agent.riskpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadV1;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionCommand;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionResult;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisValueCodec;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicyServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(CLOCK.instant(), CLOCK.getZone());
    private static final String IP_HASH =
            "ddbea5471056690e5b1dcfe0c39ffca2f91ee81710048d066e2d53c7d012e14e";

    @Test
    void automaticActivationWritesHistoryEffectiveAuditAndUpsertOutbox() {
        Fixture fixture = fixture("risk_policy_auto_transaction");

        RiskPolicy policy = fixture.service.activatePolicy(shortLinkCommand(
                "policy-001",
                "auto:policy-001",
                "abc123",
                60
        ));

        assertThat(policy.status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(policy.policyVersion()).isEqualTo(1L);
        assertThat(policy.effectiveTime()).isEqualTo(NOW);
        assertThat(fixture.policyRepository.findByPolicyId(policy.policyId())).contains(policy);
        EffectiveRiskPolicy effective = fixture.effectiveRepository
                .findByPolicyKey(policy.policyKey())
                .orElseThrow();
        assertThat(effective.policyId()).isEqualTo(policy.policyId());
        assertThat(effective.policyVersion()).isEqualTo(1L);
        assertThat(effective.desiredState()).isEqualTo(RiskPolicyDesiredState.ACTIVE);
        assertThat(effective.syncStatus()).isEqualTo(RiskPolicySyncStatus.PENDING);
        assertThat(effective.redisValueJson())
                .contains("\"policyId\":\"policy-001\"")
                .contains("\"policyVersion\":1");
        assertThat(fixture.auditRepository.countByPolicyId(policy.policyId())).isEqualTo(1);
        assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
                policy.policyKey(),
                1L,
                RiskPolicySyncOperation.UPSERT
        )).isPresent().get().satisfies(outbox -> {
            assertThat(outbox.redisValueJson()).isEqualTo(effective.redisValueJson());
            assertThat(outbox.expectedRedisValue()).isEmpty();
        });
        assertThat(fixture.reviewRepository.countByEventId(policy.eventId())).isZero();
    }

    @Test
    void confirmedActionWritesReviewPolicyEffectiveAuditAndOutboxInOneTransaction() {
        Fixture fixture = fixture("risk_policy_manual_transaction");
        RiskPolicyConfirmedActionCommand command = confirmedDisableCommand("action-1", "event-1");

        RiskPolicyConfirmedActionResult result = fixture.service.execute(command);

        assertThat(result.policyId()).isEqualTo(deterministicPolicyId("action-1"));
        assertThat(result.policyVersion()).isEqualTo(1L);
        assertThat(result.policyStatus()).isEqualTo(RiskPolicyStatus.ACTIVE.name());
        assertThat(result.syncStatus()).isEqualTo(RiskPolicySyncStatus.PENDING.name());
        assertThat(fixture.reviewRepository.countByEventId("event-1")).isEqualTo(1L);
        assertThat(fixture.reviewRepository.listByGid("gid-001"))
                .singleElement()
                .satisfies(review -> {
                    assertThat(review.reviewAction()).isEqualTo(RiskReviewAction.CONFIRM_RISK);
                    assertThat(review.reviewer()).isEqualTo("trusted-user");
                });
        assertThat(fixture.policyRepository.findByIdempotencyKey("manual:action-1")).isPresent();
        assertThat(fixture.effectiveRepository.findByPolicyKey(result.policyKey())).isPresent();
        assertThat(fixture.auditRepository.countByPolicyId(result.policyId())).isEqualTo(1);
        assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
                result.policyKey(),
                1L,
                RiskPolicySyncOperation.UPSERT
        )).isPresent();
    }

    @Test
    void repeatedConfirmedActionIsIdempotentAcrossAllTransactionalWrites() {
        Fixture fixture = fixture("risk_policy_manual_idempotency");
        RiskPolicyConfirmedActionCommand command = confirmedDisableCommand("action-stable", "event-stable");

        RiskPolicyConfirmedActionResult first = fixture.service.execute(command);
        RiskPolicyConfirmedActionResult second = fixture.service.execute(command);

        assertThat(second).isEqualTo(first);
        assertThat(fixture.count("t_agent_risk_policy")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_effective")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_sync_outbox")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_action_audit")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_review")).isEqualTo(1L);
    }

    @Test
    void replacementSupersedesHistoryAndOnlyCurrentPolicyCanBeDisabled() {
        Fixture fixture = fixture("risk_policy_replace_disable");
        RiskPolicy first = fixture.service.activatePolicy(shortLinkCommand(
                "policy-v1",
                "manual:policy-v1",
                "replace",
                60
        ));
        RiskPolicy second = fixture.service.activatePolicy(shortLinkCommand(
                "policy-v2",
                "manual:policy-v2",
                "replace",
                30
        ));

        assertThat(second.policyVersion()).isEqualTo(2L);
        assertThat(fixture.policyRepository.findByPolicyId(first.policyId()))
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.SUPERSEDED);
        assertThat(fixture.effectiveRepository.findByPolicyKey(second.policyKey()))
                .get()
                .extracting(EffectiveRiskPolicy::policyVersion)
                .isEqualTo(2L);

        assertThatThrownBy(() -> fixture.service.disablePolicy(new RiskPolicyDisableCommand(
                first.policyId(),
                first.gid(),
                "trusted-user",
                "stale disable",
                "trace-disable-stale"
        ))).isInstanceOfSatisfying(AgentActionException.class, ex ->
                assertThat(ex.code()).isEqualTo("POLICY_NOT_EFFECTIVE"));

        EffectiveRiskPolicy beforeDisable = fixture.effectiveRepository
                .findByPolicyKey(second.policyKey())
                .orElseThrow();
        fixture.service.disablePolicy(new RiskPolicyDisableCommand(
                second.policyId(),
                second.gid(),
                "trusted-user",
                "confirmed false positive",
                "trace-disable-current"
        ));

        assertThat(fixture.policyRepository.findByPolicyId(second.policyId()))
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.DISABLED);
        EffectiveRiskPolicy disabled = fixture.effectiveRepository
                .findByPolicyKey(second.policyKey())
                .orElseThrow();
        assertThat(disabled.policyVersion()).isEqualTo(3L);
        assertThat(disabled.desiredState()).isEqualTo(RiskPolicyDesiredState.DISABLED);
        assertThat(disabled.syncStatus()).isEqualTo(RiskPolicySyncStatus.PENDING);
        assertThat(fixture.auditRepository.countByPolicyId(second.policyId())).isEqualTo(2);
        assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
                second.policyKey(),
                3L,
                RiskPolicySyncOperation.DELETE
        )).isPresent().get().satisfies(outbox -> {
            assertThat(outbox.redisValueJson()).isEmpty();
            assertThat(outbox.expectedRedisValue()).isEqualTo(beforeDisable.redisValueJson());
        });
    }

    @Test
    void blockIpPolicyUsesShortLinkScopeAndNeverPersistsRawIp() {
        Fixture fixture = fixture("risk_policy_block_ip_scope");
        String rawIp = "203" + ".0.113.8";

        assertThatThrownBy(() -> fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-raw",
                "activate:policy-ip-raw",
                "gid-001",
                "nurl.ink",
                "abc123",
                rawIp,
                "{\"action\":\"BLOCK_IP\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "trusted-user",
                "confirmed abusive source",
                "trace-raw",
                "event-raw"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ipHash must be a lowercase SHA-256 value");

        RiskPolicy policy = fixture.service.activatePolicy(RiskPolicyActivationCommand.blockIp(
                "policy-ip-hash",
                "activate:policy-ip-hash",
                "gid-001",
                "nurl.ink",
                "abc123",
                IP_HASH,
                "{\"action\":\"BLOCK_IP\"}",
                RiskPolicySource.MANUAL_REVIEW,
                "trusted-user",
                "confirmed abusive source",
                "trace-hash",
                "event-hash"
        ));

        assertThat(policy.policyKey()).isEqualTo(
                "risk:policy:short-link:block-ip:nurl.ink:abc123:" + IP_HASH
        );
        assertThat(policy.ipHash()).isEqualTo(IP_HASH);
        assertThat(policy.toString()).doesNotContain(rawIp);
    }

    @Test
    void expiredActivationWritesOnlyExpiredHistoryAndAudit() {
        Fixture fixture = fixture("risk_policy_expired_transaction");
        RiskPolicyActivationCommand command = new RiskPolicyActivationCommand(
                "policy-expired",
                "auto:expired",
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "expired",
                "",
                "{\"action\":\"LIMIT_RATE\",\"limit\":60,\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "expired before activation",
                "trace-expired",
                "event-expired",
                NOW.minusSeconds(1)
        );

        RiskPolicy expired = fixture.service.activatePolicy(command);

        assertThat(expired.status()).isEqualTo(RiskPolicyStatus.EXPIRED);
        assertThat(fixture.effectiveRepository.findByPolicyKey(expired.policyKey())).isEmpty();
        assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
                expired.policyKey(),
                expired.policyVersion(),
                RiskPolicySyncOperation.UPSERT
        )).isEmpty();
        assertThat(fixture.auditRepository.countByPolicyId(expired.policyId())).isEqualTo(1);
    }

    @Test
    void idempotencyConflictDoesNotCreateAdditionalState() {
        Fixture fixture = fixture("risk_policy_idempotency_conflict");
        RiskPolicyActivationCommand original = shortLinkCommand(
                "policy-conflict",
                "auto:conflict",
                "conflict",
                60
        );
        fixture.service.activatePolicy(original);
        RiskPolicyActivationCommand conflicting = new RiskPolicyActivationCommand(
                original.policyId(),
                original.idempotencyKey(),
                original.action(),
                "other-gid",
                original.domain(),
                original.shortUri(),
                original.ipHash(),
                original.policyPayloadJson(),
                original.source(),
                original.executor(),
                original.reason(),
                "trace-conflict",
                original.eventId(),
                original.expireTime()
        );

        assertThatThrownBy(() -> fixture.service.activatePolicy(conflicting))
                .isInstanceOfSatisfying(AgentActionException.class, ex ->
                        assertThat(ex.code()).isEqualTo("ACTION_PAYLOAD_CONFLICT"));
        assertThat(fixture.count("t_agent_risk_policy")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_effective")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_sync_outbox")).isEqualTo(1L);
    }

    @Test
    void limitRateAutoActionRequiresHighRiskScoreAndTwoStrongReasons() {
        Fixture fixture = fixture("risk_policy_auto_threshold");

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
    }

    private RiskPolicyActivationCommand shortLinkCommand(
            String policyId,
            String idempotencyKey,
            String shortUri,
            int limit
    ) {
        return RiskPolicyActivationCommand.shortLink(
                policyId,
                idempotencyKey,
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                shortUri,
                "{\"action\":\"LIMIT_RATE\",\"limit\":" + limit + ",\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "automatic risk limit",
                "trace-" + policyId,
                "event-" + policyId
        );
    }

    private RiskPolicyConfirmedActionCommand confirmedDisableCommand(String actionId, String eventId) {
        RiskPolicyActionPayloadV1 payload = new RiskPolicyActionPayloadV1(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "manual001",
                "",
                "",
                List.of(),
                "confirmed malicious destination",
                eventId,
                "batch-001",
                null
        );
        return new RiskPolicyConfirmedActionCommand(
                actionId,
                deterministicPolicyId(actionId),
                "manual:" + actionId,
                payload,
                "trusted-user",
                "confirmed after review",
                "trace-" + actionId,
                "session-001"
        );
    }

    private String deterministicPolicyId(String actionId) {
        return "policy-action-" + UUID.nameUUIDFromBytes(
                actionId.getBytes(StandardCharsets.UTF_8)
        );
    }

    private Fixture fixture(String databaseName) {
        DataSource dataSource = h2DataSource(databaseName);
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                new JdbcEffectiveRiskPolicyRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository =
                new JdbcRiskActionAuditRepository(jdbcTemplate);
        JdbcRiskReviewRepository reviewRepository = new JdbcRiskReviewRepository(jdbcTemplate);
        JdbcRiskPolicySyncOutboxRepository outboxRepository =
                new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate);
        AgentProperties properties = new AgentProperties();
        RiskPolicyRedisValueCodec codec = new RiskPolicyRedisValueCodec(
                new ObjectMapper().findAndRegisterModules()
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        RiskPolicyService service = new RiskPolicyService(
                policyRepository,
                effectiveRepository,
                auditRepository,
                reviewRepository,
                outboxRepository,
                codec,
                properties,
                transactionTemplate,
                CLOCK
        );
        return new Fixture(
                jdbcTemplate,
                policyRepository,
                effectiveRepository,
                auditRepository,
                reviewRepository,
                outboxRepository,
                service
        );
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcRiskPolicyRepository policyRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskActionAuditRepository auditRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicyService service
    ) {
        long count(String table) {
            Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            return value == null ? 0L : value;
        }
    }
}
