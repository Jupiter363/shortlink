package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EffectiveRiskPolicyRepositoryTest {

    private static final LocalDateTime EFFECTIVE_TIME =
            LocalDateTime.of(2026, 7, 12, 10, 0);
    private static final LocalDateTime EXPIRE_TIME =
            LocalDateTime.of(2026, 7, 13, 10, 0);
    private static final LocalDateTime CREATE_TIME =
            LocalDateTime.of(2026, 7, 12, 9, 0);

    @Test
    void upsertInsertsWhenPolicyKeyDoesNotExist() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy policy = versionOne("risk:policy:new");

        assertThat(fixture.repository().findByPolicyKey(policy.policyKey())).isEmpty();

        fixture.repository().upsert(policy);

        EffectiveRiskPolicy inserted = fixture.repository()
                .findByPolicyKey(policy.policyKey())
                .orElseThrow();
        assertPolicyEquals(inserted, policy);
        assertThat(countSlots(fixture.jdbcTemplate(), policy.policyKey())).isEqualTo(1L);
    }

    @Test
    void higherVersionUpsertReplacesOneSlotAndMapsEveryField() {
        Fixture fixture = fixture();
        String policyKey = "risk:policy:replace";
        EffectiveRiskPolicy versionOne = versionOne(policyKey);
        EffectiveRiskPolicy versionTwo = versionTwo(policyKey);

        fixture.repository().upsert(versionOne);
        EffectiveRiskPolicy storedVersionOne = fixture.repository()
                .findByPolicyKey(policyKey)
                .orElseThrow();
        fixture.repository().upsert(versionTwo);

        EffectiveRiskPolicy expected = withPersistenceIdentity(
                versionTwo,
                storedVersionOne.id(),
                storedVersionOne.createTime()
        );
        EffectiveRiskPolicy stored = fixture.repository()
                .findByPolicyKey(policyKey)
                .orElseThrow();
        assertPolicyEquals(stored, expected);
        assertThat(countSlots(fixture.jdbcTemplate(), policyKey)).isEqualTo(1L);
    }

    @Test
    void staleVersionCannotOverwriteHigherVersion() {
        Fixture fixture = fixture();
        String policyKey = "risk:policy:stale";
        EffectiveRiskPolicy current = versionTwo(policyKey);
        fixture.repository().upsert(current);
        EffectiveRiskPolicy persistedCurrent = fixture.repository()
                .findByPolicyKey(policyKey)
                .orElseThrow();

        assertThatThrownBy(() -> fixture.repository().upsert(versionOne(policyKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Effective risk policy version is stale");
        EffectiveRiskPolicy stored = fixture.repository()
                .findByPolicyKey(policyKey)
                .orElseThrow();
        assertPolicyEquals(stored, persistedCurrent);
        assertThat(countSlots(fixture.jdbcTemplate(), policyKey)).isEqualTo(1L);
    }

    @Test
    void sameIdentityReplayCannotRollSyncStateBack() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy original = versionOne("risk:policy:idempotent-replay");
        fixture.repository().upsert(original);
        assertThat(fixture.repository().updateSyncStatusIfVersion(
                original.policyKey(),
                original.policyId(),
                original.policyVersion(),
                RiskPolicySyncStatus.SYNCED,
                original.lastOutboxId(),
                "trace-synced"
        )).isTrue();
        EffectiveRiskPolicy synced = fixture.repository()
                .findByPolicyKey(original.policyKey())
                .orElseThrow();

        fixture.repository().upsert(original);

        assertPolicyEquals(
                fixture.repository().findByPolicyKey(original.policyKey()).orElseThrow(),
                synced
        );
    }

    @Test
    void concurrentSameIdentityInsertIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        EffectiveRiskPolicy policy = versionOne("risk:policy:concurrent-idempotent");

        List<Throwable> failures = runConcurrentUpserts(fixture.repository(), policy, policy);

        assertThat(failures).containsOnlyNulls();
        assertThat(countSlots(fixture.jdbcTemplate(), policy.policyKey())).isEqualTo(1L);
        assertThat(fixture.repository().findByPolicyKey(policy.policyKey()))
                .isPresent()
                .get()
                .satisfies(stored -> {
                    assertThat(stored.policyId()).isEqualTo(policy.policyId());
                    assertThat(stored.policyVersion()).isEqualTo(policy.policyVersion());
                });
    }

    @Test
    void concurrentDifferentIdentityAtSameVersionKeepsOneWinner() throws Exception {
        Fixture fixture = fixture();
        String policyKey = "risk:policy:concurrent-conflict";
        EffectiveRiskPolicy first = versionOne(policyKey);
        EffectiveRiskPolicy second = withPolicyId(first, "policy-v1-competing");

        List<Throwable> failures = runConcurrentUpserts(fixture.repository(), first, second);

        assertThat(failures).filteredOn(failure -> failure == null).hasSize(1);
        assertThat(failures).filteredOn(failure -> failure instanceof IllegalStateException).hasSize(1);
        assertThat(countSlots(fixture.jdbcTemplate(), policyKey)).isEqualTo(1L);
        assertThat(fixture.repository().findByPolicyKey(policyKey))
                .isPresent()
                .get()
                .satisfies(stored -> {
                    assertThat(stored.policyVersion()).isEqualTo(1L);
                    assertThat(stored.policyId()).isIn(first.policyId(), second.policyId());
                });
    }

    @Test
    void findByPolicyKeyForUpdateReadsCurrentSlotInsideTransaction() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy current = versionTwo("risk:policy:locked");
        fixture.repository().upsert(current);
        EffectiveRiskPolicy persistedCurrent = fixture.repository()
                .findByPolicyKey(current.policyKey())
                .orElseThrow();
        fixture.jdbcTemplate().clearQuerySql();

        EffectiveRiskPolicy locked = fixture.transactionTemplate().execute(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return fixture.repository()
                    .findByPolicyKeyForUpdate(current.policyKey())
                    .orElseThrow();
        });

        assertPolicyEquals(locked, persistedCurrent);
        assertThat(fixture.jdbcTemplate().querySql()).anySatisfy(sql ->
                assertThat(normalizeSql(sql)).contains(
                        "from t_agent_risk_policy_effective",
                        "where policy_key = ?",
                        "for update"
                )
        );
    }

    @Test
    void findByPolicyKeyForUpdateHoldsRowLockUntilTransactionCompletes() throws Exception {
        Fixture fixture = fixture();
        EffectiveRiskPolicy current = versionOne("risk:policy:row-lock");
        fixture.repository().upsert(current);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> locker = executor.submit(() -> fixture.transactionTemplate().executeWithoutResult(status -> {
                fixture.repository().findByPolicyKeyForUpdate(current.policyKey()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> updater = executor.submit(() -> {
                updateStarted.countDown();
                return fixture.jdbcTemplate().update("""
                                update t_agent_risk_policy_effective
                                set trace_id = ?
                                where policy_key = ?
                                """,
                        "trace-after-lock",
                        current.policyKey()
                );
            });
            assertThat(updateStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> updater.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            assertThat(updater.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            locker.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void updateSyncStatusRequiresPolicyKeyPolicyIdVersionAndCurrentOutbox() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy target = versionOne("risk:policy:sync-target");
        EffectiveRiskPolicy sameIdentityOnAnotherKey = versionOne("risk:policy:sync-other");
        fixture.repository().upsert(target);
        fixture.repository().upsert(sameIdentityOnAnotherKey);

        assertThat(fixture.repository().updateSyncStatusIfVersion(
                "risk:policy:missing",
                target.policyId(),
                target.policyVersion(),
                RiskPolicySyncStatus.DEAD,
                target.lastOutboxId(),
                "trace-wrong-key"
        )).isFalse();
        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                "policy-wrong-id",
                target.policyVersion(),
                RiskPolicySyncStatus.DEAD,
                target.lastOutboxId(),
                "trace-wrong-id"
        )).isFalse();
        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                target.policyId(),
                target.policyVersion() + 1,
                RiskPolicySyncStatus.DEAD,
                target.lastOutboxId(),
                "trace-wrong-version"
        )).isFalse();
        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                target.policyId(),
                target.policyVersion(),
                RiskPolicySyncStatus.DEAD,
                "outbox-stale",
                "trace-stale-outbox"
        )).isFalse();

        assertSyncMetadata(fixture, target, RiskPolicySyncStatus.PENDING,
                target.lastOutboxId(), target.traceId());
        assertSyncMetadata(fixture, sameIdentityOnAnotherKey, RiskPolicySyncStatus.PENDING,
                sameIdentityOnAnotherKey.lastOutboxId(), sameIdentityOnAnotherKey.traceId());

        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                target.policyId(),
                target.policyVersion(),
                RiskPolicySyncStatus.SYNCED,
                target.lastOutboxId(),
                "trace-synced"
        )).isTrue();

        assertSyncMetadata(fixture, target, RiskPolicySyncStatus.SYNCED,
                target.lastOutboxId(), "trace-synced");
        assertSyncMetadata(fixture, sameIdentityOnAnotherKey, RiskPolicySyncStatus.PENDING,
                sameIdentityOnAnotherKey.lastOutboxId(), sameIdentityOnAnotherKey.traceId());
    }

    @Test
    void replacedOutboxRejectsStaleCompletionForSamePolicyVersion() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy target = versionOne("risk:policy:replaced-outbox");
        fixture.repository().upsert(target);
        fixture.jdbcTemplate().update("""
                        update t_agent_risk_policy_effective
                        set desired_state = ?, sync_status = ?, last_outbox_id = ?
                        where policy_key = ?
                        """,
                RiskPolicyDesiredState.EXPIRED.name(),
                RiskPolicySyncStatus.PENDING.name(),
                "outbox-expiry",
                target.policyKey()
        );

        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                target.policyId(),
                target.policyVersion(),
                RiskPolicySyncStatus.SYNCED,
                target.lastOutboxId(),
                "trace-stale-upsert"
        )).isFalse();
        assertThat(fixture.repository().updateSyncStatusIfVersion(
                target.policyKey(),
                target.policyId(),
                target.policyVersion(),
                RiskPolicySyncStatus.SYNCED,
                "outbox-expiry",
                "trace-expiry"
        )).isTrue();

        EffectiveRiskPolicy stored = fixture.repository()
                .findByPolicyKey(target.policyKey())
                .orElseThrow();
        assertThat(stored.desiredState()).isEqualTo(RiskPolicyDesiredState.EXPIRED);
        assertThat(stored.syncStatus()).isEqualTo(RiskPolicySyncStatus.SYNCED);
        assertThat(stored.lastOutboxId()).isEqualTo("outbox-expiry");
        assertThat(stored.traceId()).isEqualTo("trace-expiry");
    }

    @Test
    void insertUsesDatabaseTimestampsWhenTheyAreNotProvided() {
        Fixture fixture = fixture();
        EffectiveRiskPolicy policy = withTimestamps(
                versionOne("risk:policy:database-timestamps"),
                null,
                null
        );

        fixture.repository().upsert(policy);

        EffectiveRiskPolicy stored = fixture.repository()
                .findByPolicyKey(policy.policyKey())
                .orElseThrow();
        assertThat(stored.createTime()).isNotNull();
        assertThat(stored.updateTime()).isNotNull();
        assertThat(stored.updateTime()).isAfterOrEqualTo(stored.createTime());
    }

    private Fixture fixture() {
        DataSource dataSource = h2DataSource();
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(dataSource);
        return new Fixture(
                jdbcTemplate,
                new JdbcEffectiveRiskPolicyRepository(jdbcTemplate),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    private EffectiveRiskPolicy versionOne(String policyKey) {
        return new EffectiveRiskPolicy(
                null,
                policyKey,
                "policy-v1",
                1L,
                "gid-v1",
                RiskPolicyAction.DISABLE_SHORT_LINK,
                RiskPolicyDesiredState.ACTIVE,
                "{\"action\":\"DISABLE_SHORT_LINK\",\"version\":1}",
                "{\"policyId\":\"policy-v1\",\"policyVersion\":1}",
                EFFECTIVE_TIME,
                null,
                RiskPolicySyncStatus.PENDING,
                "outbox-v1",
                "trace-v1",
                CREATE_TIME,
                CREATE_TIME.plusMinutes(1)
        );
    }

    private EffectiveRiskPolicy versionTwo(String policyKey) {
        return new EffectiveRiskPolicy(
                null,
                policyKey,
                "policy-v2",
                2L,
                "gid-v2",
                RiskPolicyAction.BLOCK_IP,
                RiskPolicyDesiredState.DISABLED,
                "{\"action\":\"BLOCK_IP\",\"ipHash\":\"hash-v2\"}",
                "{\"policyId\":\"policy-v2\",\"policyVersion\":2,\"blocked\":true}",
                EFFECTIVE_TIME.plusHours(1),
                EXPIRE_TIME,
                RiskPolicySyncStatus.RETRY_WAIT,
                "outbox-v2",
                "trace-v2",
                CREATE_TIME.plusHours(1),
                CREATE_TIME.plusHours(1).plusMinutes(1)
        );
    }

    private EffectiveRiskPolicy withPersistenceIdentity(
            EffectiveRiskPolicy policy,
            Long id,
            LocalDateTime createTime
    ) {
        return new EffectiveRiskPolicy(
                id,
                policy.policyKey(),
                policy.policyId(),
                policy.policyVersion(),
                policy.gid(),
                policy.action(),
                policy.desiredState(),
                policy.policyPayloadJson(),
                policy.redisValueJson(),
                policy.effectiveTime(),
                policy.expireTime(),
                policy.syncStatus(),
                policy.lastOutboxId(),
                policy.traceId(),
                createTime,
                policy.updateTime()
        );
    }

    private EffectiveRiskPolicy withPolicyId(EffectiveRiskPolicy policy, String policyId) {
        return new EffectiveRiskPolicy(
                policy.id(),
                policy.policyKey(),
                policyId,
                policy.policyVersion(),
                policy.gid(),
                policy.action(),
                policy.desiredState(),
                policy.policyPayloadJson(),
                policy.redisValueJson(),
                policy.effectiveTime(),
                policy.expireTime(),
                policy.syncStatus(),
                policy.lastOutboxId(),
                policy.traceId(),
                policy.createTime(),
                policy.updateTime()
        );
    }

    private EffectiveRiskPolicy withTimestamps(
            EffectiveRiskPolicy policy,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
        return new EffectiveRiskPolicy(
                policy.id(),
                policy.policyKey(),
                policy.policyId(),
                policy.policyVersion(),
                policy.gid(),
                policy.action(),
                policy.desiredState(),
                policy.policyPayloadJson(),
                policy.redisValueJson(),
                policy.effectiveTime(),
                policy.expireTime(),
                policy.syncStatus(),
                policy.lastOutboxId(),
                policy.traceId(),
                createTime,
                updateTime
        );
    }

    private List<Throwable> runConcurrentUpserts(
            JdbcEffectiveRiskPolicyRepository repository,
            EffectiveRiskPolicy first,
            EffectiveRiskPolicy second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> firstResult = executor.submit(() -> upsertAfterSignal(
                    repository, first, ready, start));
            Future<Throwable> secondResult = executor.submit(() -> upsertAfterSignal(
                    repository, second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>(2);
            failures.add(firstResult.get(5, TimeUnit.SECONDS));
            failures.add(secondResult.get(5, TimeUnit.SECONDS));
            return failures;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Throwable upsertAfterSignal(
            JdbcEffectiveRiskPolicyRepository repository,
            EffectiveRiskPolicy policy,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        try {
            repository.upsert(policy);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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

    private void assertPolicyEquals(
            EffectiveRiskPolicy actual,
            EffectiveRiskPolicy expected
    ) {
        assertThat(actual).isNotNull();
        if (expected.id() == null) {
            assertThat(actual.id()).isPositive();
        } else {
            assertThat(actual.id()).isEqualTo(expected.id());
        }
        assertThat(actual.policyKey()).isEqualTo(expected.policyKey());
        assertThat(actual.policyId()).isEqualTo(expected.policyId());
        assertThat(actual.policyVersion()).isEqualTo(expected.policyVersion());
        assertThat(actual.gid()).isEqualTo(expected.gid());
        assertThat(actual.action()).isEqualTo(expected.action());
        assertThat(actual.desiredState()).isEqualTo(expected.desiredState());
        assertThat(actual.policyPayloadJson()).isEqualTo(expected.policyPayloadJson());
        assertThat(actual.redisValueJson()).isEqualTo(expected.redisValueJson());
        assertThat(actual.effectiveTime()).isEqualTo(expected.effectiveTime());
        assertThat(actual.expireTime()).isEqualTo(expected.expireTime());
        assertThat(actual.syncStatus()).isEqualTo(expected.syncStatus());
        assertThat(actual.lastOutboxId()).isEqualTo(expected.lastOutboxId());
        assertThat(actual.traceId()).isEqualTo(expected.traceId());
        assertThat(actual.createTime()).isEqualTo(expected.createTime());
        assertThat(actual.updateTime()).isEqualTo(expected.updateTime());
    }

    private void assertSyncMetadata(
            Fixture fixture,
            EffectiveRiskPolicy identity,
            RiskPolicySyncStatus expectedStatus,
            String expectedOutboxId,
            String expectedTraceId
    ) {
        EffectiveRiskPolicy stored = fixture.repository()
                .findByPolicyKey(identity.policyKey())
                .orElseThrow();
        assertThat(stored.policyId()).isEqualTo(identity.policyId());
        assertThat(stored.policyVersion()).isEqualTo(identity.policyVersion());
        assertThat(stored.policyPayloadJson()).isEqualTo(identity.policyPayloadJson());
        assertThat(stored.syncStatus()).isEqualTo(expectedStatus);
        assertThat(stored.lastOutboxId()).isEqualTo(expectedOutboxId);
        assertThat(stored.traceId()).isEqualTo(expectedTraceId);
    }

    private long countSlots(JdbcTemplate jdbcTemplate, String policyKey) {
        Long count = jdbcTemplate.queryForObject(
                "select count(1) from t_agent_risk_policy_effective where policy_key = ?",
                Long.class,
                policyKey
        );
        return count == null ? 0L : count;
    }

    private DataSource h2DataSource() {
        String databaseName = "effective_risk_policy_"
                + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private record Fixture(
            RecordingJdbcTemplate jdbcTemplate,
            JdbcEffectiveRiskPolicyRepository repository,
            TransactionTemplate transactionTemplate
    ) {
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {

        private final List<String> querySql = new ArrayList<>();

        private RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
                throws DataAccessException {
            querySql.add(sql);
            return super.query(sql, rowMapper, args);
        }

        private List<String> querySql() {
            return List.copyOf(querySql);
        }

        private void clearQuerySql() {
            querySql.clear();
        }
    }
}
