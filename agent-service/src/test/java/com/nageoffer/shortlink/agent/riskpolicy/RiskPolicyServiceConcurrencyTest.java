package com.nageoffer.shortlink.agent.riskpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicySource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyActivationCommand;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyServiceConcurrencyTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-10T03:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Test
    void concurrentSameIdempotencyKeyConvergesToOnePolicyAndOutbox() throws Exception {
        Fixture fixture = fixture("risk_policy_concurrent_idempotency");
        RiskPolicyActivationCommand command = command("policy-stable", "auto:stable", 60);

        List<RiskPolicy> results = runConcurrently(
                () -> fixture.service.activatePolicy(command),
                () -> fixture.service.activatePolicy(command)
        );

        assertThat(results)
                .extracting(RiskPolicy::policyId)
                .containsOnly("policy-stable");
        assertThat(results)
                .extracting(RiskPolicy::policyVersion)
                .containsOnly(1L);
        assertThat(fixture.count("t_agent_risk_policy")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_effective")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_policy_sync_outbox")).isEqualTo(1L);
        assertThat(fixture.count("t_agent_risk_action_audit")).isEqualTo(1L);
    }

    @Test
    void concurrentDifferentActionsOnSamePolicyKeyAllocateContinuousVersions() throws Exception {
        Fixture fixture = fixture("risk_policy_concurrent_versions");

        List<RiskPolicy> results = runConcurrently(
                () -> fixture.service.activatePolicy(command("policy-a", "auto:a", 60)),
                () -> fixture.service.activatePolicy(command("policy-b", "auto:b", 30))
        );

        assertThat(results)
                .extracting(RiskPolicy::policyVersion)
                .containsExactlyInAnyOrder(1L, 2L);
        List<RiskPolicy> history = fixture.policyRepository
                .findByPolicyKeyOrderByVersion(results.get(0).policyKey());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).policyVersion()).isEqualTo(2L);
        assertThat(history.get(0).status()).isEqualTo(RiskPolicyStatus.ACTIVE);
        assertThat(history.get(1).policyVersion()).isEqualTo(1L);
        assertThat(history.get(1).status()).isEqualTo(RiskPolicyStatus.SUPERSEDED);
        assertThat(fixture.effectiveRepository.findByPolicyKey(results.get(0).policyKey()))
                .get()
                .extracting(EffectiveRiskPolicy::policyVersion)
                .isEqualTo(2L);
        assertThat(fixture.count("t_agent_risk_policy_sync_outbox")).isEqualTo(2L);
    }

    private RiskPolicyActivationCommand command(
            String policyId,
            String idempotencyKey,
            int limit
    ) {
        return RiskPolicyActivationCommand.shortLink(
                policyId,
                idempotencyKey,
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "concurrent",
                "{\"action\":\"LIMIT_RATE\",\"limit\":" + limit + ",\"windowSeconds\":60}",
                RiskPolicySource.AGENT_AUTO,
                "security-risk-agent",
                "concurrent activation",
                "trace-" + policyId,
                "event-" + policyId
        );
    }

    private List<RiskPolicy> runConcurrently(
            ThrowingPolicySupplier first,
            ThrowingPolicySupplier second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RiskPolicy> firstFuture = executor.submit(() -> runAfterBarrier(first, ready, start));
            Future<RiskPolicy> secondFuture = executor.submit(() -> runAfterBarrier(second, ready, start));
            ready.await();
            start.countDown();
            return List.of(firstFuture.get(), secondFuture.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private RiskPolicy runAfterBarrier(
            ThrowingPolicySupplier supplier,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        return supplier.get();
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
        RiskPolicyService service = new RiskPolicyService(
                policyRepository,
                effectiveRepository,
                new JdbcRiskActionAuditRepository(jdbcTemplate),
                new JdbcRiskReviewRepository(jdbcTemplate),
                new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate),
                new RiskPolicyRedisValueCodec(new ObjectMapper().findAndRegisterModules()),
                new AgentProperties(),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                CLOCK
        );
        return new Fixture(jdbcTemplate, policyRepository, effectiveRepository, service);
    }

    private DataSource h2DataSource(String name) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @FunctionalInterface
    private interface ThrowingPolicySupplier {
        RiskPolicy get() throws Exception;
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcRiskPolicyRepository policyRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            RiskPolicyService service
    ) {
        long count(String table) {
            Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
            return value == null ? 0L : value;
        }
    }
}
