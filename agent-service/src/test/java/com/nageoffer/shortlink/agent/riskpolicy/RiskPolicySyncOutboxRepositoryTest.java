package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.JdbcRiskPolicySyncOutboxRepository;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOperation;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutbox;
import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicySyncOutboxRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);

    @Test
    void createsOneOutboxPerPolicyVersionAndOperation() {
        Fixture fixture = fixture();
        RiskPolicySyncOutbox original = pendingOutbox(
                "outbox-1", "risk:key:1", "policy-1", 1L, RiskPolicySyncOperation.UPSERT);

        assertThat(fixture.repository().createIfAbsent(original)).isTrue();
        assertThat(fixture.repository().createIfAbsent(original.withOutboxId("outbox-2"))).isFalse();
        assertThatThrownBy(() -> fixture.repository().createIfAbsent(pendingOutbox(
                original.outboxId(),
                "risk:key:other",
                "policy-other",
                2L,
                RiskPolicySyncOperation.DELETE
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outboxId conflicts");
        assertThatThrownBy(() -> fixture.repository().createIfAbsent(copyWithPayloads(
                original.withOutboxId("outbox-payload-conflict"),
                "{\"policyId\":\"policy-conflict\",\"policyVersion\":1}",
                ""
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload conflicts");

        assertThat(countRows(fixture.jdbcTemplate())).isEqualTo(1L);
        RiskPolicySyncOutbox stored = fixture.repository()
                .findByOutboxId(original.outboxId())
                .orElseThrow();
        assertOutboxEquals(stored, original);
        assertThat(fixture.repository().findByPolicyKeyVersionAndOperation(
                original.policyKey(),
                original.policyVersion(),
                original.operation()
        )).contains(stored);
    }

    @Test
    void usesDatabaseTimestampsWhenNotProvided() {
        Fixture fixture = fixture();
        RiskPolicySyncOutbox source = pendingOutbox(
                "outbox-database-time",
                "risk:key:database-time",
                "policy-database-time",
                1L,
                RiskPolicySyncOperation.UPSERT
        );
        RiskPolicySyncOutbox withoutTimes = new RiskPolicySyncOutbox(
                null,
                source.outboxId(),
                source.policyKey(),
                source.policyId(),
                source.policyVersion(),
                source.operation(),
                source.redisValueJson(),
                source.expectedRedisValue(),
                source.status(),
                source.attemptCount(),
                source.nextRetryTime(),
                source.ownerToken(),
                source.leaseUntil(),
                source.lastError(),
                null,
                null
        );

        assertThat(fixture.repository().createIfAbsent(withoutTimes)).isTrue();

        RiskPolicySyncOutbox stored = fixture.repository()
                .findByOutboxId(source.outboxId())
                .orElseThrow();
        assertThat(stored.createTime()).isNotNull();
        assertThat(stored.updateTime()).isNotNull();
        assertThat(stored.updateTime()).isAfterOrEqualTo(stored.createTime());
    }

    @Test
    void rejectsRawIpInRedisValuesAndSanitizesInitialError() {
        Fixture fixture = fixture();
        String sourceAddress = "203.0." + "113.9";
        String ipv6Address = "2001:db8:" + ":1";
        RiskPolicySyncOutbox source = pendingOutbox(
                "outbox-sensitive",
                "risk:key:sensitive",
                "policy-sensitive",
                1L,
                RiskPolicySyncOperation.UPSERT
        );

        assertThatThrownBy(() -> fixture.repository().createIfAbsent(copyWithPayloads(
                source,
                "{\"sourceIp\":\"" + sourceAddress + "\"}",
                source.expectedRedisValue()
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisValueJson");
        assertThatThrownBy(() -> fixture.repository().createIfAbsent(copyWithPayloads(
                source,
                source.redisValueJson(),
                "{\"sourceIp\":\"" + sourceAddress + "\"}"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedRedisValue");
        assertThatThrownBy(() -> fixture.repository().createIfAbsent(copyWithPolicyKey(
                source,
                "risk:block:" + sourceAddress
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyKey");
        assertThatThrownBy(() -> fixture.repository().createIfAbsent(copyWithPolicyKey(
                source,
                "risk:block:" + ipv6Address
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyKey");

        String fakeApiKey = "s" + "k-test-fake-key";
        String sensitiveError = "source=" + sourceAddress
                + " source6=" + ipv6Address
                + " to" + "ken=plain-secret-value"
                + " Authori" + "zation: Bear" + "er fake-bearer-value"
                + " standalone Bear" + "er second-fake-value"
                + " key=" + fakeApiKey
                + " {\"pass" + "word\":\"json-secret-value\","
                + "\"visitor" + "Id\":\"visitor-123\"}"
                + " user" + "name=alice"
                + " jdbc:my" + "sql://db-user:db-password@localhost:3306/agent "
                + "x".repeat(3000);
        assertThat(fixture.repository().createIfAbsent(copyWithLastError(source, sensitiveError))).isTrue();
        String storedError = fixture.repository().findByOutboxId(source.outboxId())
                .orElseThrow()
                .lastError();
        assertThat(storedError).hasSize(2048);
        assertThat(RiskIpSafety.containsRawIpLiteral(storedError)).isFalse();
        assertThat(storedError)
                .contains(
                        "203.0.*.*",
                        "2001:db8:*:*",
                        "token=***",
                        "Authorization: ***",
                        "Bearer ***",
                        "\"password\":\"***\"",
                        "\"visitorId\":\"***\"",
                        "username=***",
                        "jdbc:***"
                )
                .doesNotContain(
                        sourceAddress,
                        ipv6Address,
                        "plain-secret-value",
                        "fake-bearer-value",
                        "second-fake-value",
                        fakeApiKey,
                        "json-secret-value",
                        "visitor-123",
                        "alice",
                        "db-password"
                );
    }

    @Test
    void claimsPendingAndDueRetryWithLeaseButNotFutureRetry() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(pendingOutbox(
                "outbox-pending", "risk:key:pending", "policy-pending", 1L,
                RiskPolicySyncOperation.UPSERT));
        fixture.repository().createIfAbsent(outbox(
                "outbox-due", "risk:key:due", "policy-due", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.RETRY_WAIT,
                1,
                NOW.minusSeconds(1),
                "",
                null,
                "previous failure"
        ));
        fixture.repository().createIfAbsent(outbox(
                "outbox-future", "risk:key:future", "policy-future", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.RETRY_WAIT,
                1,
                NOW.plusMinutes(1),
                "",
                null,
                "previous failure"
        ));

        RiskPolicySyncOutbox first = fixture.repository()
                .claimNext("worker-1", NOW, Duration.ofMinutes(5), 3)
                .orElseThrow();
        RiskPolicySyncOutbox second = fixture.repository()
                .claimNext("worker-2", NOW, Duration.ofMinutes(5), 3)
                .orElseThrow();

        assertThat(first.outboxId()).isEqualTo("outbox-pending");
        assertClaim(first, "worker-1", 1, NOW.plusMinutes(5));
        assertThat(second.outboxId()).isEqualTo("outbox-due");
        assertClaim(second, "worker-2", 2, NOW.plusMinutes(5));
        assertThat(fixture.repository().claimNext(
                "worker-3", NOW, Duration.ofMinutes(5), 3)).isEmpty();
        assertThat(fixture.repository().findByOutboxId("outbox-future"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.RETRY_WAIT);
                    assertThat(value.attemptCount()).isEqualTo(1);
                });
    }

    @Test
    void terminalUpdatesRequireCurrentOwner() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(pendingOutbox(
                "outbox-terminal", "risk:key:terminal", "policy-terminal", 1L,
                RiskPolicySyncOperation.UPSERT));
        RiskPolicySyncOutbox claimed = fixture.repository()
                .claimNext("owner-current", NOW, Duration.ofMinutes(5), 3)
                .orElseThrow();

        assertThat(fixture.repository().markSucceeded(
                claimed.outboxId(), "owner-stale", NOW.plusMinutes(1))).isFalse();
        assertThat(fixture.repository().markSucceeded(
                claimed.outboxId(), "OWNER-CURRENT", NOW.plusMinutes(1))).isFalse();
        assertThat(fixture.repository().markSkipped(
                claimed.outboxId(), "owner-stale", "stale", NOW.plusMinutes(1))).isFalse();
        assertThat(fixture.repository().recordFailure(
                claimed.outboxId(),
                "owner-stale",
                3,
                NOW.plusMinutes(1),
                NOW.plusMinutes(2),
                "stale"
        )).isFalse();
        assertThat(fixture.repository().markSucceeded(
                claimed.outboxId(), claimed.ownerToken(), NOW.plusMinutes(1))).isTrue();

        assertThat(fixture.repository().findByOutboxId(claimed.outboxId()))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.SUCCEEDED);
                    assertThat(value.ownerToken()).isEmpty();
                    assertThat(value.leaseUntil()).isNull();
                    assertThat(value.lastError()).isEmpty();
                });
        assertThat(fixture.repository().resetForReplay(claimed.outboxId(), NOW.plusMinutes(2)))
                .isFalse();
    }

    @Test
    void expiredLeaseCannotCompleteSkipOrRecordFailure() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(pendingOutbox(
                "outbox-expired-owner", "risk:key:expired-owner", "policy-expired-owner", 1L,
                RiskPolicySyncOperation.UPSERT));
        RiskPolicySyncOutbox claimed = fixture.repository()
                .claimNext("owner-expired", NOW, Duration.ofMinutes(5), 3)
                .orElseThrow();

        assertThat(fixture.repository().markSucceeded(
                claimed.outboxId(), claimed.ownerToken(), claimed.leaseUntil())).isFalse();
        assertThat(fixture.repository().markSkipped(
                claimed.outboxId(), claimed.ownerToken(), "expired", claimed.leaseUntil())).isFalse();
        assertThat(fixture.repository().recordFailure(
                claimed.outboxId(),
                claimed.ownerToken(),
                3,
                claimed.leaseUntil(),
                claimed.leaseUntil().plusMinutes(1),
                "expired"
        )).isFalse();
        assertThat(fixture.repository().findByOutboxId(claimed.outboxId()))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
                    assertThat(value.ownerToken()).isEqualTo(claimed.ownerToken());
                });
    }

    @Test
    void failureRetriesThenBecomesDeadAndCanBeReplayed() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(pendingOutbox(
                "outbox-retry", "risk:key:retry", "policy-retry", 1L,
                RiskPolicySyncOperation.UPSERT));
        RiskPolicySyncOutbox firstClaim = fixture.repository()
                .claimNext("owner-first", NOW, Duration.ofMinutes(5), 2)
                .orElseThrow();
        String sourceAddress = "198.51." + "100.7";
        String error = "redis source=" + sourceAddress + " pass" + "word=unsafe-value";

        assertThat(fixture.repository().recordFailure(
                firstClaim.outboxId(),
                firstClaim.ownerToken(),
                2,
                NOW.plusMinutes(1),
                NOW.plusMinutes(30),
                error
        )).isTrue();
        RiskPolicySyncOutbox retryWait = fixture.repository()
                .findByOutboxId(firstClaim.outboxId())
                .orElseThrow();
        assertThat(retryWait.status()).isEqualTo(RiskPolicySyncOutboxStatus.RETRY_WAIT);
        assertThat(retryWait.nextRetryTime()).isEqualTo(NOW.plusMinutes(30));
        assertThat(retryWait.ownerToken()).isEmpty();
        assertThat(retryWait.leaseUntil()).isNull();
        assertThat(RiskIpSafety.containsRawIpLiteral(retryWait.lastError())).isFalse();
        assertThat(retryWait.lastError())
                .contains("198.51.*.*", "password=***")
                .doesNotContain(sourceAddress, "unsafe-value");
        assertThat(fixture.repository().claimNext(
                "owner-too-early", NOW.plusMinutes(29), Duration.ofMinutes(5), 2)).isEmpty();

        RiskPolicySyncOutbox secondClaim = fixture.repository()
                .claimNext("owner-second", NOW.plusMinutes(30), Duration.ofMinutes(5), 2)
                .orElseThrow();
        assertThat(secondClaim.attemptCount()).isEqualTo(2);
        assertThat(fixture.repository().recordFailure(
                secondClaim.outboxId(),
                secondClaim.ownerToken(),
                2,
                NOW.plusMinutes(31),
                NOW.plusHours(1),
                "second failure"
        )).isTrue();

        assertThat(fixture.repository().findByOutboxId(secondClaim.outboxId()))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.DEAD);
                    assertThat(value.attemptCount()).isEqualTo(2);
                    assertThat(value.nextRetryTime()).isNull();
                    assertThat(value.ownerToken()).isEmpty();
                    assertThat(value.leaseUntil()).isNull();
                });
        LocalDateTime replayTime = NOW.plusMinutes(32);
        assertThat(fixture.repository().resetForReplay(secondClaim.outboxId(), replayTime)).isTrue();
        assertThat(fixture.repository().findByOutboxId(secondClaim.outboxId()))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PENDING);
                    assertThat(value.attemptCount()).isZero();
                    assertThat(value.nextRetryTime()).isEqualTo(replayTime);
                    assertThat(value.lastError()).isEmpty();
                });
        assertThat(fixture.repository().claimNext(
                "owner-replay", replayTime, Duration.ofMinutes(5), 2
        )).isPresent().get().satisfies(value -> {
            assertThat(value.outboxId()).isEqualTo(secondClaim.outboxId());
            assertThat(value.attemptCount()).isEqualTo(1);
            assertThat(value.ownerToken()).isEqualTo("owner-replay");
        });
        assertThat(fixture.repository().resetForReplay(
                secondClaim.outboxId(), NOW.plusMinutes(33))).isFalse();
    }

    @Test
    void skippedOutboxCanBeReplayedWithSanitizedReason() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(pendingOutbox(
                "outbox-skipped", "risk:key:skipped", "policy-skipped", 1L,
                RiskPolicySyncOperation.DELETE));
        RiskPolicySyncOutbox claimed = fixture.repository()
                .claimNext("owner-skip", NOW, Duration.ofMinutes(5), 3)
                .orElseThrow();
        String sourceAddress = "192.0." + "2.8";

        assertThat(fixture.repository().markSkipped(
                claimed.outboxId(),
                claimed.ownerToken(),
                "REDIS_VALUE_MISMATCH source=" + sourceAddress,
                NOW.plusMinutes(1)
        )).isTrue();
        assertThat(fixture.repository().findByOutboxId(claimed.outboxId()))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.SKIPPED);
                    assertThat(value.lastError()).startsWith("REDIS_VALUE_MISMATCH");
                    assertThat(RiskIpSafety.containsRawIpLiteral(value.lastError())).isFalse();
                    assertThat(value.lastError()).contains("192.0.*.*");
                });
        assertThat(fixture.repository().resetForReplay(
                claimed.outboxId(), NOW.plusMinutes(2))).isTrue();
        assertThat(fixture.repository().findByOutboxId(claimed.outboxId()))
                .isPresent()
                .get()
                .extracting(RiskPolicySyncOutbox::nextRetryTime)
                .isEqualTo(NOW.plusMinutes(2));

        RiskPolicySyncOutbox ordinarySkipped = outbox(
                "outbox-skipped-other",
                "risk:key:skipped-other",
                "policy-skipped-other",
                1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.SKIPPED,
                1,
                null,
                "",
                null,
                "STALE_UPSERT"
        );
        assertThat(fixture.repository().createIfAbsent(ordinarySkipped)).isTrue();
        assertThat(fixture.repository().resetForReplay(
                ordinarySkipped.outboxId(), NOW.plusMinutes(3))).isFalse();
    }

    @Test
    void recoversExpiredProcessingAndDeadLettersExhaustedLease() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(outbox(
                "outbox-expired-retry", "risk:key:expired-retry", "policy-expired-retry", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.PROCESSING,
                1,
                null,
                "owner-retry",
                NOW.minusSeconds(1),
                ""
        ));
        fixture.repository().createIfAbsent(outbox(
                "outbox-expired-dead", "risk:key:expired-dead", "policy-expired-dead", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.PROCESSING,
                2,
                null,
                "owner-dead",
                NOW.minusSeconds(1),
                "last known failure"
        ));
        fixture.repository().createIfAbsent(outbox(
                "outbox-active", "risk:key:active", "policy-active", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.PROCESSING,
                1,
                null,
                "owner-active",
                NOW.plusMinutes(1),
                ""
        ));

        assertThat(fixture.repository().recoverExpiredProcessing(NOW, 2)).isEqualTo(2);

        assertThat(fixture.repository().findByOutboxId("outbox-expired-retry"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.RETRY_WAIT);
                    assertThat(value.nextRetryTime()).isEqualTo(NOW);
                    assertThat(value.ownerToken()).isEmpty();
                    assertThat(value.leaseUntil()).isNull();
                    assertThat(value.lastError()).isEqualTo("Risk policy sync lease expired");
                });
        assertThat(fixture.repository().findByOutboxId("outbox-expired-dead"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.DEAD);
                    assertThat(value.nextRetryTime()).isNull();
                    assertThat(value.lastError()).isEqualTo("last known failure");
                });
        assertThat(fixture.repository().findByOutboxId("outbox-active"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
                    assertThat(value.ownerToken()).isEqualTo("owner-active");
                });
    }

    @Test
    void exhaustedReadyOutboxBecomesDeadInsteadOfBeingClaimed() {
        Fixture fixture = fixture();
        fixture.repository().createIfAbsent(outbox(
                "outbox-exhausted", "risk:key:exhausted", "policy-exhausted", 1L,
                RiskPolicySyncOperation.UPSERT,
                RiskPolicySyncOutboxStatus.RETRY_WAIT,
                2,
                NOW.minusSeconds(1),
                "",
                null,
                ""
        ));

        assertThat(fixture.repository().claimNext(
                "owner-never", NOW, Duration.ofMinutes(5), 2)).isEmpty();
        assertThat(fixture.repository().findByOutboxId("outbox-exhausted"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.DEAD);
                    assertThat(value.lastError())
                            .isEqualTo("Risk policy sync exhausted maximum attempts");
                });
    }

    private Fixture fixture() {
        DataSource dataSource = dataSource();
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/agent_service_schema.sql")
        ).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(jdbcTemplate, new JdbcRiskPolicySyncOutboxRepository(jdbcTemplate));
    }

    private RiskPolicySyncOutbox pendingOutbox(
            String outboxId,
            String policyKey,
            String policyId,
            long policyVersion,
            RiskPolicySyncOperation operation
    ) {
        return outbox(
                outboxId,
                policyKey,
                policyId,
                policyVersion,
                operation,
                RiskPolicySyncOutboxStatus.PENDING,
                0,
                null,
                "",
                null,
                ""
        );
    }

    private RiskPolicySyncOutbox outbox(
            String outboxId,
            String policyKey,
            String policyId,
            long policyVersion,
            RiskPolicySyncOperation operation,
            RiskPolicySyncOutboxStatus status,
            int attemptCount,
            LocalDateTime nextRetryTime,
            String ownerToken,
            LocalDateTime leaseUntil,
            String lastError
    ) {
        String redisValue = "{\"policyId\":\"" + policyId
                + "\",\"policyVersion\":" + policyVersion + "}";
        return new RiskPolicySyncOutbox(
                null,
                outboxId,
                policyKey,
                policyId,
                policyVersion,
                operation,
                redisValue,
                operation == RiskPolicySyncOperation.DELETE ? redisValue : "",
                status,
                attemptCount,
                nextRetryTime,
                ownerToken,
                leaseUntil,
                lastError,
                NOW.minusMinutes(1),
                NOW.minusMinutes(1)
        );
    }

    private RiskPolicySyncOutbox copyWithPayloads(
            RiskPolicySyncOutbox source,
            String redisValueJson,
            String expectedRedisValue
    ) {
        return new RiskPolicySyncOutbox(
                source.id(), source.outboxId(), source.policyKey(), source.policyId(),
                source.policyVersion(), source.operation(), redisValueJson, expectedRedisValue,
                source.status(), source.attemptCount(), source.nextRetryTime(), source.ownerToken(),
                source.leaseUntil(), source.lastError(), source.createTime(), source.updateTime()
        );
    }

    private RiskPolicySyncOutbox copyWithPolicyKey(
            RiskPolicySyncOutbox source,
            String policyKey
    ) {
        return new RiskPolicySyncOutbox(
                source.id(), source.outboxId(), policyKey, source.policyId(),
                source.policyVersion(), source.operation(), source.redisValueJson(),
                source.expectedRedisValue(), source.status(), source.attemptCount(),
                source.nextRetryTime(), source.ownerToken(), source.leaseUntil(), source.lastError(),
                source.createTime(), source.updateTime()
        );
    }

    private RiskPolicySyncOutbox copyWithLastError(
            RiskPolicySyncOutbox source,
            String lastError
    ) {
        return new RiskPolicySyncOutbox(
                source.id(), source.outboxId(), source.policyKey(), source.policyId(),
                source.policyVersion(), source.operation(), source.redisValueJson(),
                source.expectedRedisValue(), source.status(), source.attemptCount(),
                source.nextRetryTime(), source.ownerToken(), source.leaseUntil(), lastError,
                source.createTime(), source.updateTime()
        );
    }

    private void assertClaim(
            RiskPolicySyncOutbox value,
            String ownerToken,
            int attemptCount,
            LocalDateTime leaseUntil
    ) {
        assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
        assertThat(value.ownerToken()).isEqualTo(ownerToken);
        assertThat(value.attemptCount()).isEqualTo(attemptCount);
        assertThat(value.leaseUntil()).isEqualTo(leaseUntil);
        assertThat(value.nextRetryTime()).isNull();
    }

    private void assertOutboxEquals(
            RiskPolicySyncOutbox actual,
            RiskPolicySyncOutbox expected
    ) {
        assertThat(actual.id()).isPositive();
        assertThat(actual.outboxId()).isEqualTo(expected.outboxId());
        assertThat(actual.policyKey()).isEqualTo(expected.policyKey());
        assertThat(actual.policyId()).isEqualTo(expected.policyId());
        assertThat(actual.policyVersion()).isEqualTo(expected.policyVersion());
        assertThat(actual.operation()).isEqualTo(expected.operation());
        assertThat(actual.redisValueJson()).isEqualTo(expected.redisValueJson());
        assertThat(actual.expectedRedisValue()).isEqualTo(expected.expectedRedisValue());
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.attemptCount()).isEqualTo(expected.attemptCount());
        assertThat(actual.nextRetryTime()).isEqualTo(expected.nextRetryTime());
        assertThat(actual.ownerToken()).isEqualTo(expected.ownerToken());
        assertThat(actual.leaseUntil()).isEqualTo(expected.leaseUntil());
        assertThat(actual.lastError()).isEqualTo(expected.lastError());
        assertThat(actual.createTime()).isEqualTo(expected.createTime());
        assertThat(actual.updateTime()).isEqualTo(expected.updateTime());
    }

    private long countRows(JdbcTemplate jdbcTemplate) {
        Long count = jdbcTemplate.queryForObject(
                "select count(1) from t_agent_risk_policy_sync_outbox",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private DataSource dataSource() {
        String databaseName = "risk_policy_sync_outbox_"
                + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "sa",
                ""
        );
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcRiskPolicySyncOutboxRepository repository
    ) {
    }
}
