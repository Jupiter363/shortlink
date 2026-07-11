package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncService;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RiskPolicyReplayTransactionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );
    private static final AgentActionActor ACTOR = new AgentActionActor(
            "operator-1", "1001", "Risk Operator", "");

    @Test
    void replayAtomicallyResetsOutboxEffectiveStateAndWritesOneAudit() {
        Fixture fixture = fixture(false);
        fixture.insertReplayable("policy-1", "outbox-1");

        assertThat(fixture.service().replay("outbox-1", ACTOR, "redis recovered"))
                .isEqualTo(RiskPolicySyncOutboxStatus.PENDING);

        RiskPolicySyncOutbox replayed = fixture.outboxRepository()
                .findByOutboxId("outbox-1")
                .orElseThrow();
        assertThat(replayed.status()).isEqualTo(RiskPolicySyncOutboxStatus.PENDING);
        assertThat(replayed.attemptCount()).isZero();
        assertThat(replayed.nextRetryTime()).isEqualTo(NOW);
        assertThat(replayed.ownerToken()).isEmpty();
        assertThat(replayed.leaseUntil()).isNull();
        assertThat(replayed.lastError()).isEmpty();
        assertThat(fixture.effectiveRepository().findByPolicyKey(replayed.policyKey()))
                .isPresent()
                .get()
                .extracting(EffectiveRiskPolicy::syncStatus)
                .isEqualTo(RiskPolicySyncStatus.PENDING);
        assertThat(fixture.countAudits()).isEqualTo(1L);
        assertThat(fixture.auditEventId()).isEqualTo("outbox-1");

        assertThatThrownBy(() -> fixture.service().replay(
                "outbox-1", ACTOR, "duplicate replay"))
                .isInstanceOf(AgentActionException.class)
                .extracting(ex -> ((AgentActionException) ex).code())
                .isEqualTo("POLICY_SYNC_OUTBOX_NOT_REPLAYABLE");
        assertThat(fixture.countAudits()).isEqualTo(1L);
    }

    @Test
    void auditFailureRollsBackReplayAndEffectiveState() {
        Fixture fixture = fixture(true);
        fixture.insertReplayable("policy-2", "outbox-2");

        assertThatThrownBy(() -> fixture.service().replay(
                "outbox-2", ACTOR, "redis recovered"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        RiskPolicySyncOutbox stored = fixture.outboxRepository()
                .findByOutboxId("outbox-2")
                .orElseThrow();
        assertThat(stored.status()).isEqualTo(RiskPolicySyncOutboxStatus.DEAD);
        assertThat(stored.attemptCount()).isEqualTo(10);
        assertThat(stored.lastError()).isEqualTo("Redis unavailable");
        assertThat(fixture.effectiveRepository().findByPolicyKey(stored.policyKey()))
                .isPresent()
                .get()
                .extracting(EffectiveRiskPolicy::syncStatus)
                .isEqualTo(RiskPolicySyncStatus.DEAD);
        assertThat(fixture.countAudits()).isZero();
    }

    private Fixture fixture(boolean failAudit) {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_policy_replay_"
                        + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        JdbcRiskPolicySyncOutboxRepository outboxRepository =
                new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate);
        JdbcEffectiveRiskPolicyRepository effectiveRepository =
                new JdbcEffectiveRiskPolicyRepository(jdbcTemplate);
        JdbcRiskPolicyRepository policyRepository = new JdbcRiskPolicyRepository(jdbcTemplate);
        JdbcRiskActionAuditRepository auditRepository = failAudit
                ? new JdbcRiskActionAuditRepository(jdbcTemplate) {
                    @Override
                    public void saveOutboxReplayAudit(
                            String outboxId,
                            String policyId,
                            String executor,
                            String reason
                    ) {
                        throw new IllegalStateException("audit unavailable");
                    }
                }
                : new JdbcRiskActionAuditRepository(jdbcTemplate);
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        RiskPolicySyncService service = new RiskPolicySyncService(
                outboxRepository,
                effectiveRepository,
                policyRepository,
                auditRepository,
                mock(RiskPolicyRedisPublisher.class),
                transactionTemplate,
                CLOCK
        );
        return new Fixture(
                jdbcTemplate,
                outboxRepository,
                effectiveRepository,
                service
        );
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            RiskPolicySyncService service
    ) {

        void insertReplayable(String policyId, String outboxId) {
            String policyKey = "risk:policy:short-link:rate-limit:nurl.ink:" + policyId;
            String redisValue = "{\"policyId\":\"" + policyId + "\",\"policyVersion\":1}";
            effectiveRepository.upsert(new EffectiveRiskPolicy(
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
                    null,
                    RiskPolicySyncStatus.DEAD,
                    outboxId,
                    "trace-1",
                    NOW.minusHours(1),
                    NOW
            ));
            outboxRepository.createIfAbsent(new RiskPolicySyncOutbox(
                    null,
                    outboxId,
                    policyKey,
                    policyId,
                    1L,
                    RiskPolicySyncOperation.UPSERT,
                    redisValue,
                    "",
                    RiskPolicySyncOutboxStatus.DEAD,
                    10,
                    null,
                    "stale-worker",
                    NOW.minusMinutes(1),
                    "Redis unavailable",
                    NOW.minusHours(1),
                    NOW
            ));
        }

        long countAudits() {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from t_agent_risk_action_audit",
                    Long.class
            );
            return count == null ? 0L : count;
        }

        String auditEventId() {
            return jdbcTemplate.queryForObject(
                    "select event_id from t_agent_risk_action_audit limit 1",
                    String.class
            );
        }
    }
}
