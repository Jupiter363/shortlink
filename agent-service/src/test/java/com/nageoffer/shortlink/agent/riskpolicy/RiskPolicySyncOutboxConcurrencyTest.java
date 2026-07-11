package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicySyncOutboxConcurrencyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);

    @Test
    void concurrentCreateKeepsOneOutboxForPolicyVersionAndOperation() throws Exception {
        Fixture fixture = fixture();
        RiskPolicySyncOutbox first = pendingOutbox(
                "outbox-create-a", "risk:key:create", "policy-create", 1L);
        RiskPolicySyncOutbox second = first.withOutboxId("outbox-create-b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstResult = executor.submit(() -> createAfterSignal(
                    fixture.repository(), first, ready, start));
            Future<Boolean> secondResult = executor.submit(() -> createAfterSignal(
                    fixture.repository(), second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    firstResult.get(5, TimeUnit.SECONDS),
                    secondResult.get(5, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);
            assertThat(countRows(fixture.jdbcTemplate())).isEqualTo(1L);
            String storedId = fixture.jdbcTemplate().queryForObject(
                    "select outbox_id from t_agent_risk_policy_sync_outbox",
                    String.class
            );
            assertThat(storedId).isIn(first.outboxId(), second.outboxId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentWorkersOnlyAllowOneClaimForSameOutbox() throws Exception {
        Fixture fixture = fixture();
        RiskPolicySyncOutbox outbox = pendingOutbox(
                "outbox-claim", "risk:key:claim", "policy-claim", 1L);
        assertThat(fixture.repository().createIfAbsent(outbox)).isTrue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<RiskPolicySyncOutbox>> first = executor.submit(() -> claimAfterSignal(
                    fixture.repository(), "owner-a", ready, start));
            Future<Optional<RiskPolicySyncOutbox>> second = executor.submit(() -> claimAfterSignal(
                    fixture.repository(), "owner-b", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<RiskPolicySyncOutbox>> results = new ArrayList<>(2);
            results.add(first.get(5, TimeUnit.SECONDS));
            results.add(second.get(5, TimeUnit.SECONDS));
            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);

            RiskPolicySyncOutbox stored = fixture.repository()
                    .findByOutboxId(outbox.outboxId())
                    .orElseThrow();
            assertThat(stored.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
            assertThat(stored.attemptCount()).isEqualTo(1);
            assertThat(stored.ownerToken()).isIn("owner-a", "owner-b");
            assertThat(stored.leaseUntil()).isEqualTo(NOW.plusMinutes(5));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void claimDoesNotReturnRecordReassignedBeforeOwnerProtectedRead() {
        DataSource dataSource = dataSource();
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate contender = new JdbcTemplate(dataSource);
        ClaimRaceJdbcTemplate claimTemplate = new ClaimRaceJdbcTemplate(
                dataSource,
                contender,
                "outbox-owner-race",
                NOW.plusMinutes(10)
        );
        JdbcRiskPolicySyncOutboxRepository repository =
                new JdbcRiskPolicySyncOutboxRepository(claimTemplate);
        assertThat(repository.createIfAbsent(pendingOutbox(
                "outbox-owner-race", "risk:key:owner-race", "policy-owner-race", 1L
        ))).isTrue();
        claimTemplate.arm();

        assertThat(repository.claimNext(
                "owner-original", NOW, Duration.ofMinutes(5), 3)).isEmpty();

        assertThat(repository.findByOutboxId("outbox-owner-race"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
                    assertThat(value.ownerToken()).isEqualTo("owner-reassigned");
                    assertThat(value.leaseUntil()).isEqualTo(NOW.plusMinutes(10));
                });
    }

    @Test
    void findForUpdateHoldsRowLockUntilTransactionCompletes() throws Exception {
        Fixture fixture = fixture();
        RiskPolicySyncOutbox outbox = pendingOutbox(
                "outbox-lock", "risk:key:lock", "policy-lock", 1L);
        assertThat(fixture.repository().createIfAbsent(outbox)).isTrue();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> locker = executor.submit(() -> fixture.transactionTemplate()
                    .executeWithoutResult(status -> {
                        fixture.repository().findByOutboxIdForUpdate(outbox.outboxId())
                                .orElseThrow();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> updater = executor.submit(() -> fixture.jdbcTemplate().update("""
                                update t_agent_risk_policy_sync_outbox
                                set last_error = ?
                                where outbox_id = ?
                                """,
                        "updated after lock",
                        outbox.outboxId()
                ));
            assertThatThrownBy(() -> updater.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DataAccessException.class);

            release.countDown();
            locker.get(5, TimeUnit.SECONDS);
            assertThat(fixture.jdbcTemplate().update("""
                            update t_agent_risk_policy_sync_outbox
                            set last_error = ?
                            where outbox_id = ?
                            """,
                    "updated after lock",
                    outbox.outboxId()
            )).isEqualTo(1);
            assertThat(fixture.repository().findByOutboxId(outbox.outboxId()))
                    .isPresent()
                    .get()
                    .extracting(RiskPolicySyncOutbox::lastError)
                    .isEqualTo("updated after lock");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private boolean createAfterSignal(
            JdbcRiskPolicySyncOutboxRepository repository,
            RiskPolicySyncOutbox outbox,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return repository.createIfAbsent(outbox);
    }

    private Optional<RiskPolicySyncOutbox> claimAfterSignal(
            JdbcRiskPolicySyncOutboxRepository repository,
            String ownerToken,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        return repository.claimNext(ownerToken, NOW, Duration.ofMinutes(5), 3);
    }

    private Fixture fixture() {
        DataSource dataSource = dataSource();
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(
                jdbcTemplate,
                new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    private RiskPolicySyncOutbox pendingOutbox(
            String outboxId,
            String policyKey,
            String policyId,
            long policyVersion
    ) {
        return new RiskPolicySyncOutbox(
                null,
                outboxId,
                policyKey,
                policyId,
                policyVersion,
                RiskPolicySyncOperation.UPSERT,
                "{\"policyId\":\"" + policyId + "\",\"policyVersion\":" + policyVersion + "}",
                "",
                RiskPolicySyncOutboxStatus.PENDING,
                0,
                null,
                "",
                null,
                "",
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );
    }

    private long countRows(JdbcTemplate jdbcTemplate) {
        Long count = jdbcTemplate.queryForObject(
                "select count(1) from t_agent_risk_policy_sync_outbox",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private DataSource dataSource() {
        String databaseName = "risk_policy_sync_outbox_concurrency_"
                + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=500",
                "sa",
                ""
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test coordination", ex);
        }
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcRiskPolicySyncOutboxRepository repository,
            TransactionTemplate transactionTemplate
    ) {
    }

    private static final class ClaimRaceJdbcTemplate extends JdbcTemplate {

        private final JdbcTemplate contender;

        private final String outboxId;

        private final LocalDateTime reassignedLeaseUntil;

        private boolean armed;

        private boolean reassignBeforeRead;

        private ClaimRaceJdbcTemplate(
                DataSource dataSource,
                JdbcTemplate contender,
                String outboxId,
                LocalDateTime reassignedLeaseUntil
        ) {
            super(dataSource);
            this.contender = contender;
            this.outboxId = outboxId;
            this.reassignedLeaseUntil = reassignedLeaseUntil;
        }

        private void arm() {
            armed = true;
        }

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            int updated = super.update(sql, args);
            if (armed
                    && updated > 0
                    && normalizeSql(sql).contains("attempt_count = attempt_count + 1")) {
                armed = false;
                reassignBeforeRead = true;
            }
            return updated;
        }

        @Override
        public <T> List<T> query(
                String sql,
                RowMapper<T> rowMapper,
                Object... args
        ) throws DataAccessException {
            String normalized = normalizeSql(sql);
            if (reassignBeforeRead
                    && normalized.contains("from t_agent_risk_policy_sync_outbox")
                    && normalized.contains("where outbox_id = ?")) {
                reassignBeforeRead = false;
                contender.update("""
                                update t_agent_risk_policy_sync_outbox
                                set owner_token = ?, lease_until = ?
                                where outbox_id = ? and status = ?
                                """,
                        "owner-reassigned",
                        Timestamp.valueOf(reassignedLeaseUntil),
                        outboxId,
                        RiskPolicySyncOutboxStatus.PROCESSING.name()
                );
            }
            return super.query(sql, rowMapper, args);
        }

        private static String normalizeSql(String sql) {
            return sql.replaceAll("\\s+", " ").trim().toLowerCase();
        }
    }
}
