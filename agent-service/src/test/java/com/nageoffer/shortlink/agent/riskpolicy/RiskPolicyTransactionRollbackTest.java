package com.nageoffer.shortlink.agent.riskpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadV1;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyConfirmedActionCommand;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicyTransactionRollbackTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void outboxFailureRollsBackReviewHistoryEffectiveAndAudit() {
        DataSource dataSource = h2DataSource("risk_policy_transaction_rollback");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        RiskPolicyService service = new RiskPolicyService(
                new JdbcRiskPolicyRepository(jdbcTemplate),
                new JdbcEffectiveRiskPolicyRepository(jdbcTemplate),
                new JdbcRiskActionAuditRepository(jdbcTemplate),
                new JdbcRiskReviewRepository(jdbcTemplate),
                new FailingOutboxRepository(jdbcTemplate),
                new RiskPolicyRedisValueCodec(new ObjectMapper().findAndRegisterModules()),
                new AgentProperties(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                CLOCK
        );

        assertThatThrownBy(() -> service.execute(confirmedCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");

        assertThat(count(jdbcTemplate, "t_agent_risk_review")).isZero();
        assertThat(count(jdbcTemplate, "t_agent_risk_policy")).isZero();
        assertThat(count(jdbcTemplate, "t_agent_risk_policy_effective")).isZero();
        assertThat(count(jdbcTemplate, "t_agent_risk_action_audit")).isZero();
        assertThat(count(jdbcTemplate, "t_agent_risk_policy_sync_outbox")).isZero();
    }

    private RiskPolicyConfirmedActionCommand confirmedCommand() {
        String actionId = "action-rollback";
        return new RiskPolicyConfirmedActionCommand(
                actionId,
                "policy-action-" + UUID.nameUUIDFromBytes(
                        actionId.getBytes(StandardCharsets.UTF_8)
                ),
                "manual:" + actionId,
                new RiskPolicyActionPayloadV1(
                        RiskPolicyAction.DISABLE_SHORT_LINK,
                        "gid-001",
                        "nurl.ink",
                        "rollback",
                        "",
                        "",
                        List.of(),
                        "confirmed malicious destination",
                        "event-rollback",
                        "batch-rollback",
                        null
                ),
                "trusted-user",
                "confirmed after review",
                "trace-rollback",
                "session-rollback"
        );
    }

    private long count(JdbcTemplate jdbcTemplate, String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0L : value;
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

    private static final class FailingOutboxRepository
            extends JdbcRiskPolicySyncOutboxRepository {

        private FailingOutboxRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        @Override
        public boolean createIfAbsent(RiskPolicySyncOutbox outbox) {
            throw new IllegalStateException("outbox unavailable");
        }
    }
}
