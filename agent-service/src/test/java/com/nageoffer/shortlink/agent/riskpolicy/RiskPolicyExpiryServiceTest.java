package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicyExpiryService;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncService;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyDeleteResult;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskPolicyExpiryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final String POLICY_KEY =
            "risk:policy:short-link:rate-limit:nurl.ink:abc123";
    private static final String REDIS_VALUE =
            "{\"action\":\"LIMIT_RATE\",\"policyId\":\"policy-1\",\"policyVersion\":1}";

    @Test
    void expiryCreatesSameVersionDeleteAndWorkerCompletesProjection() {
        Fixture fixture = fixture();
        fixture.policyRepository().insert(history());
        fixture.effectiveRepository().upsert(effective());

        assertThat(fixture.expiryService().expireNext(NOW)).isTrue();

        assertThat(fixture.policyRepository().findByPolicyId("policy-1"))
                .isPresent()
                .get()
                .extracting(RiskPolicy::status)
                .isEqualTo(RiskPolicyStatus.EXPIRED);
        EffectiveRiskPolicy expired = fixture.effectiveRepository()
                .findByPolicyKey(POLICY_KEY)
                .orElseThrow();
        assertThat(expired.desiredState()).isEqualTo(RiskPolicyDesiredState.EXPIRED);
        assertThat(expired.syncStatus()).isEqualTo(RiskPolicySyncStatus.PENDING);
        RiskPolicySyncOutbox delete = fixture.outboxRepository()
                .findByPolicyKeyVersionAndOperation(
                        POLICY_KEY, 1L, RiskPolicySyncOperation.DELETE)
                .orElseThrow();
        assertThat(delete.expectedRedisValue()).isEqualTo(REDIS_VALUE);
        assertThat(expired.lastOutboxId()).isEqualTo(delete.outboxId());

        RiskPolicySyncOutbox claimed = fixture.outboxRepository().claimNext(
                "worker-1", NOW.plusSeconds(1), Duration.ofMinutes(5), 10)
                .orElseThrow();
        when(fixture.publisher().compareAndDelete(POLICY_KEY, REDIS_VALUE))
                .thenReturn(RiskPolicyDeleteResult.ALREADY_ABSENT);
        fixture.syncService().process(claimed, "worker-1", NOW.plusSeconds(1));

        assertThat(fixture.outboxRepository().findByOutboxId(delete.outboxId()))
                .isPresent()
                .get()
                .extracting(RiskPolicySyncOutbox::status)
                .isEqualTo(com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus.SUCCEEDED);
        assertThat(fixture.effectiveRepository().findByPolicyKey(POLICY_KEY))
                .isPresent()
                .get()
                .extracting(EffectiveRiskPolicy::syncStatus)
                .isEqualTo(RiskPolicySyncStatus.SYNCED);
    }

    @Test
    void expiryReturnsFalseWhenNoPolicyIsDue() {
        Fixture fixture = fixture();
        fixture.policyRepository().insert(history());
        EffectiveRiskPolicy future = new EffectiveRiskPolicy(
                null,
                POLICY_KEY,
                "policy-1",
                1L,
                "gid-1",
                RiskPolicyAction.LIMIT_RATE,
                RiskPolicyDesiredState.ACTIVE,
                "{}",
                REDIS_VALUE,
                NOW.minusHours(1),
                NOW.plusMinutes(1),
                RiskPolicySyncStatus.SYNCED,
                "outbox-upsert",
                "trace-1",
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
        fixture.effectiveRepository().upsert(future);

        assertThat(fixture.expiryService().expireNext(NOW)).isFalse();
        assertThat(fixture.outboxRepository().findByPolicyKeyVersionAndOperation(
                POLICY_KEY, 1L, RiskPolicySyncOperation.DELETE)).isEmpty();
    }

    @Test
    void oneInvalidExpiryCandidateDoesNotBlockLaterPolicies() {
        Fixture fixture = fixture();
        String brokenKey = POLICY_KEY + ":broken";
        String healthyKey = POLICY_KEY + ":healthy";
        fixture.effectiveRepository().upsert(effective(
                "policy-broken", brokenKey, "broken", "outbox-broken"));
        fixture.policyRepository().insert(history(
                "policy-healthy", healthyKey, "healthy"));
        fixture.effectiveRepository().upsert(effective(
                "policy-healthy", healthyKey, "healthy", "outbox-healthy"));

        assertThat(fixture.expiryService().expireBatch(NOW, 10)).isEqualTo(1);

        assertThat(fixture.effectiveRepository().findByPolicyKey(brokenKey))
                .isPresent()
                .get()
                .extracting(EffectiveRiskPolicy::desiredState)
                .isEqualTo(RiskPolicyDesiredState.ACTIVE);
        assertThat(fixture.effectiveRepository().findByPolicyKey(healthyKey))
                .isPresent()
                .get()
                .extracting(EffectiveRiskPolicy::desiredState)
                .isEqualTo(RiskPolicyDesiredState.EXPIRED);
    }

    private Fixture fixture() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_policy_expiry_"
                        + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                new JdbcEffectiveRiskPolicyRepository(jdbcTemplate);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcRiskPolicySyncOutboxRepository outboxRepository =
                new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository =
                new JdbcRiskActionAuditRepository(jdbcTemplate);
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        RiskPolicyRedisPublisher publisher = mock(RiskPolicyRedisPublisher.class);
        RiskPolicyExpiryService expiryService = new RiskPolicyExpiryService(
                effectiveRepository, policyRepository, outboxRepository, transactionTemplate);
        RiskPolicySyncService syncService = new RiskPolicySyncService(
                outboxRepository,
                effectiveRepository,
                policyRepository,
                auditRepository,
                publisher,
                transactionTemplate,
                CLOCK
        );
        return new Fixture(
                policyRepository,
                effectiveRepository,
                outboxRepository,
                publisher,
                expiryService,
                syncService
        );
    }

    private RiskPolicy history() {
        return history("policy-1", POLICY_KEY, "abc123");
    }

    private RiskPolicy history(String policyId, String policyKey, String shortUri) {
        return RiskPolicy.shortLinkPolicy(
                policyId,
                policyKey,
                "auto:event-1",
                1L,
                RiskPolicyAction.LIMIT_RATE,
                "gid-1",
                "nurl.ink",
                shortUri,
                "{}",
                RiskPolicySource.AGENT_AUTO,
                "trace-1",
                "event-1",
                NOW.minusHours(1),
                NOW.minusSeconds(1)
        );
    }

    private EffectiveRiskPolicy effective() {
        return effective("policy-1", POLICY_KEY, "abc123", "outbox-upsert");
    }

    private EffectiveRiskPolicy effective(
            String policyId,
            String policyKey,
            String shortUri,
            String outboxId
    ) {
        String redisValue = "{\"action\":\"LIMIT_RATE\",\"policyId\":\""
                + policyId + "\",\"policyVersion\":1}";
        return new EffectiveRiskPolicy(
                null,
                policyKey,
                policyId,
                1L,
                "gid-1",
                RiskPolicyAction.LIMIT_RATE,
                RiskPolicyDesiredState.ACTIVE,
                "{}",
                redisValue,
                NOW.minusHours(1),
                NOW.minusSeconds(1),
                RiskPolicySyncStatus.SYNCED,
                outboxId,
                "trace-1",
                NOW.minusHours(1),
                NOW.minusHours(1)
        );
    }

    private record Fixture(
            JdbcRiskPolicyRepository policyRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            RiskPolicyRedisPublisher publisher,
            RiskPolicyExpiryService expiryService,
            RiskPolicySyncService syncService
    ) {
    }
}
