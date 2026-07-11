package com.nageoffer.shortlink.agent.harness.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionClaim;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposalResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadCodec;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class AgentPendingActionConcurrencyTest {

    private static final AgentActionType DISABLE_SHORT_LINK =
            new AgentActionType("risk.disable-short-link");
    private static final AgentActionType LIMIT_TIME_WINDOW =
            new AgentActionType("risk.limit-time-window");

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 9, 30);

    @Test
    void actionClaimRecordsThePersistedPostClaimVersion() {
        assertThat(AgentActionClaim.class.isRecord()).isTrue();
        assertThat(Arrays.stream(AgentActionClaim.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("action", "executionToken", "claimedVersion");
    }

    @Test
    void claimRollsBackCasWhenPostUpdateReadFailsThroughSpringTransactionProxy() {
        DataSource dataSource = dataSource("claim_transaction_rollback");
        populate(dataSource);
        JdbcTemplate observerJdbcTemplate = new JdbcTemplate(dataSource);
        JdbcAgentPendingActionRepository observerRepository =
                new JdbcAgentPendingActionRepository(observerJdbcTemplate);
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
        observerRepository.propose(
                proposal("action-transaction-rollback"),
                codec,
                NOW,
                NOW.plusHours(2)
        );
        FailingPostClaimReadJdbcTemplate failingJdbcTemplate =
                new FailingPostClaimReadJdbcTemplate(dataSource);

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.register(TransactionTestConfiguration.class);
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource)
            );
            context.registerBean(
                    "pendingActionRepository",
                    JdbcAgentPendingActionRepository.class,
                    () -> new JdbcAgentPendingActionRepository(failingJdbcTemplate)
            );
            context.refresh();
            JdbcAgentPendingActionRepository transactionalRepository =
                    context.getBean(JdbcAgentPendingActionRepository.class);

            assertThatThrownBy(() -> transactionalRepository.claimForExecution(
                    "action-transaction-rollback",
                    1L,
                    "token-transaction-rollback",
                    NOW,
                    Duration.ofMinutes(5),
                    true
            ))
                    .isInstanceOf(DataRetrievalFailureException.class)
                    .hasMessage("Injected post-claim read failure");

            AgentPendingAction rolledBack = observerRepository
                    .findByActionId("action-transaction-rollback")
                    .orElseThrow();
            assertSoftly(softly -> {
                softly.assertThat(AopUtils.isAopProxy(transactionalRepository)).isTrue();
                softly.assertThat(failingJdbcTemplate.transactionActiveDuringClaimRead())
                        .isTrue();
                softly.assertThat(rolledBack.status()).isEqualTo(AgentActionStatus.PENDING);
                softly.assertThat(rolledBack.version()).isEqualTo(1L);
                softly.assertThat(rolledBack.attemptCount()).isZero();
                softly.assertThat(rolledBack.executionToken()).isEmpty();
                softly.assertThat(rolledBack.executionLeaseUntil()).isNull();
                softly.assertThat(rolledBack.confirmedTime()).isNull();
            });
        }
    }

    @Test
    void unsupportedLeaseDurationsThrowWithoutUpdatingTheAction() {
        Fixture fixture = fixture("unsupported_lease_duration");
        AgentPendingAction pending = insertPending(
                fixture,
                "action-unsupported-lease",
                NOW,
                NOW.plusHours(2)
        );

        for (Duration invalidDuration : Arrays.asList(
                null,
                Duration.ZERO,
                Duration.ofSeconds(-1),
                Duration.ofSeconds(86_401)
        )) {
            assertThatThrownBy(() -> fixture.repository().claimForExecution(
                    "action-unsupported-lease",
                    pending.version(),
                    "token-unsupported-lease",
                    NOW,
                    invalidDuration,
                    true
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("leaseDuration");
        }

        assertThat(action(fixture, "action-unsupported-lease")).isEqualTo(pending);
    }

    @Test
    void leaseDurationOverflowIsReportedAsIllegalArgumentWithoutUpdatingTheAction() {
        Fixture fixture = fixture("lease_duration_overflow");
        AgentPendingAction pending = insertPending(
                fixture,
                "action-overflow-lease",
                NOW,
                NOW.plusHours(2)
        );

        assertThatThrownBy(() -> fixture.repository().claimForExecution(
                "action-overflow-lease",
                pending.version(),
                "token-overflow-lease",
                LocalDateTime.MAX.minusSeconds(10),
                Duration.ofSeconds(86_400),
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseDuration");
        assertThat(action(fixture, "action-overflow-lease")).isEqualTo(pending);
    }

    @Test
    void maximumSupportedLeaseDurationIsAllowed() {
        Fixture fixture = fixture("maximum_lease_duration");
        insertPending(fixture, "action-maximum-lease", NOW, NOW.plusDays(2));

        AgentActionClaim claim = claimAt(
                fixture,
                "action-maximum-lease",
                1L,
                "token-maximum-lease",
                NOW,
                Duration.ofSeconds(86_400),
                true
        );

        assertThat(claim.action().executionLeaseUntil()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void onlyOneConcurrentConfirmationClaimsTheAction() throws Exception {
        Fixture fixture = fixture("claim_concurrency");
        insertPending(fixture, "action-claim", NOW, NOW.plusHours(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Optional<AgentActionClaim>> first = executor.submit(() -> claimAfterStart(
                    fixture,
                    ready,
                    start,
                    "action-claim",
                    "token-first"
            ));
            Future<Optional<AgentActionClaim>> second = executor.submit(() -> claimAfterStart(
                    fixture,
                    ready,
                    start,
                    "action-claim",
                    "token-second"
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Optional<AgentActionClaim>> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            AgentActionClaim winner = results.stream()
                    .flatMap(Optional::stream)
                    .findFirst()
                    .orElseThrow();

            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            assertThat(winner.claimedVersion()).isEqualTo(2L);
            assertThat(winner.claimedVersion()).isEqualTo(winner.action().version());
            assertThat(winner.executionToken()).isEqualTo(winner.action().executionToken());
            assertThat(winner.action().status()).isEqualTo(AgentActionStatus.EXECUTING);
            assertThat(winner.action().executionLeaseUntil()).isEqualTo(NOW.plusMinutes(5));
            assertThat(winner.action().attemptCount()).isEqualTo(1);
            assertThat(fixture.repository().findByActionId("action-claim"))
                    .contains(winner.action());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentConfirmAndRejectHaveOnlyOneWinner() throws Exception {
        Fixture fixture = fixture("confirm_reject_concurrency");
        insertPending(fixture, "action-race", NOW, NOW.plusHours(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> confirm = executor.submit(() -> {
                ready.countDown();
                await(start);
                return fixture.repository().claimForExecution(
                        "action-race",
                        1L,
                        "token-race",
                        NOW,
                        Duration.ofMinutes(5),
                        true
                ).isPresent();
            });
            Future<Boolean> reject = executor.submit(() -> {
                ready.countDown();
                await(start);
                return fixture.repository().reject(
                        "action-race",
                        1L,
                        "reviewer",
                        "not approved",
                        "IGNORE",
                        NOW
                );
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            boolean confirmWon = confirm.get(10, TimeUnit.SECONDS);
            boolean rejectWon = reject.get(10, TimeUnit.SECONDS);
            AgentPendingAction stored = action(fixture, "action-race");

            assertThat(Stream.of(confirmWon, rejectWon).filter(Boolean::booleanValue).count())
                    .isEqualTo(1L);
            assertThat(stored.version()).isEqualTo(2L);
            assertThat(confirmWon).isEqualTo(stored.status() == AgentActionStatus.EXECUTING);
            assertThat(rejectWon).isEqualTo(stored.status() == AgentActionStatus.REJECTED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void executionCompletionAndFailureRequireBothTokenAndClaimedVersion() {
        Fixture fixture = fixture("execution_fencing");
        insertPending(fixture, "action-complete", NOW, NOW.plusHours(2));
        insertPending(fixture, "action-fail", NOW, NOW.plusHours(2));
        String failedSlotHash = action(fixture, "action-fail").activeSlotKey();
        AgentActionClaim completeClaim = claim(fixture, "action-complete", 1L, "token-complete", true);
        AgentActionClaim failClaim = claim(fixture, "action-fail", 1L, "token-fail", true);

        assertThat(fixture.repository().completeExecution(
                "action-complete",
                "wrong-token",
                completeClaim.claimedVersion(),
                "{\"ok\":true}",
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(fixture.repository().completeExecution(
                "action-complete",
                completeClaim.executionToken(),
                completeClaim.claimedVersion() - 1,
                "{\"ok\":true}",
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(action(fixture, "action-complete").status())
                .isEqualTo(AgentActionStatus.EXECUTING);
        assertThat(fixture.repository().completeExecution(
                "action-complete",
                completeClaim.executionToken(),
                completeClaim.claimedVersion(),
                "{\"ok\":true}",
                NOW.plusMinutes(1)
        )).isTrue();

        AgentPendingAction completed = action(fixture, "action-complete");
        assertThat(completed.status()).isEqualTo(AgentActionStatus.EXECUTED);
        assertThat(completed.version()).isEqualTo(completeClaim.claimedVersion() + 1);
        assertThat(completed.resultJson()).isEqualTo("{\"ok\":true}");
        assertTerminalResourcesReleased(completed);

        assertThat(fixture.repository().failExecution(
                "action-fail",
                "wrong-token",
                failClaim.claimedVersion(),
                "EXECUTOR_FAILED",
                "executor failed",
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(fixture.repository().failExecution(
                "action-fail",
                failClaim.executionToken(),
                failClaim.claimedVersion() - 1,
                "EXECUTOR_FAILED",
                "executor failed",
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(action(fixture, "action-fail").status())
                .isEqualTo(AgentActionStatus.EXECUTING);
        assertThat(fixture.repository().failExecution(
                "action-fail",
                failClaim.executionToken(),
                failClaim.claimedVersion(),
                "EXECUTOR_FAILED",
                "executor failed",
                NOW.plusMinutes(1)
        )).isTrue();

        AgentPendingAction failed = action(fixture, "action-fail");
        assertThat(failed.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(failed.version()).isEqualTo(failClaim.claimedVersion() + 1);
        assertThat(failed.failureCode()).isEqualTo("EXECUTOR_FAILED");
        assertThat(failed.failureMessage()).isEqualTo("executor failed");
        assertFailedExecutionOwnershipReleased(failed, failedSlotHash);

        AgentActionProposalResult duplicate = fixture.repository().propose(
                proposal(
                        "action-after-fail",
                        LIMIT_TIME_WINDOW,
                        "idem-after-fail",
                        "slot-action-fail",
                        "batch-after-fail"
                ),
                fixture.codec(),
                NOW.plusMinutes(2),
                NOW.plusHours(3)
        );
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.action().actionId()).isEqualTo("action-fail");
        assertThat(duplicate.action().status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(countActions(fixture)).isEqualTo(2);
    }

    @Test
    void expiredExecutionLeaseRecoversToFailedWithoutTouchingLiveExecution() {
        Fixture fixture = fixture("lease_recovery");
        insertPending(fixture, "action-expired-lease", NOW.minusMinutes(20), NOW.plusHours(2));
        insertPending(fixture, "action-live-lease", NOW.minusMinutes(20), NOW.plusHours(2));
        insertPending(fixture, "action-stale-non-executing", NOW.minusMinutes(20), NOW.plusHours(2));
        String recoveredSlotHash = action(fixture, "action-expired-lease").activeSlotKey();
        AgentActionClaim expired = claimAt(
                fixture,
                "action-expired-lease",
                1L,
                "token-expired",
                NOW.minusMinutes(10),
                Duration.ofMinutes(10),
                true
        );
        AgentActionClaim live = claimAt(
                fixture,
                "action-live-lease",
                1L,
                "token-live",
                NOW.minusMinutes(10),
                Duration.ofMinutes(30),
                true
        );
        setFailedWithStaleLease(fixture, "action-stale-non-executing", 2L);

        assertThat(fixture.repository().recoverExpiredExecutions(NOW)).isEqualTo(1);

        AgentPendingAction recovered = action(fixture, "action-expired-lease");
        assertThat(recovered.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(recovered.version()).isEqualTo(expired.claimedVersion() + 1);
        assertThat(recovered.failureCode()).isEqualTo("EXECUTION_LEASE_EXPIRED");
        assertFailedExecutionOwnershipReleased(recovered, recoveredSlotHash);

        AgentPendingAction stillExecuting = action(fixture, "action-live-lease");
        assertThat(stillExecuting.status()).isEqualTo(AgentActionStatus.EXECUTING);
        assertThat(stillExecuting.version()).isEqualTo(live.claimedVersion());
        assertThat(stillExecuting.executionToken()).isEqualTo(live.executionToken());
        assertThat(stillExecuting.activeSlotKey()).isNotNull();

        AgentPendingAction staleNonExecuting = action(fixture, "action-stale-non-executing");
        assertThat(staleNonExecuting.status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(staleNonExecuting.version()).isEqualTo(2L);
        assertThat(staleNonExecuting.executionToken()).isEqualTo("stale-token");
        assertThat(staleNonExecuting.executionLeaseUntil()).isEqualTo(NOW.minusMinutes(5));
        assertThat(staleNonExecuting.activeSlotKey()).isNotNull();

        AgentActionProposalResult duplicate = fixture.repository().propose(
                proposal(
                        "action-after-recovery",
                        LIMIT_TIME_WINDOW,
                        "idem-after-recovery",
                        "slot-action-expired-lease",
                        "batch-after-recovery"
                ),
                fixture.codec(),
                NOW.plusMinutes(1),
                NOW.plusHours(3)
        );
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.action().actionId()).isEqualTo("action-expired-lease");
        assertThat(duplicate.action().status()).isEqualTo(AgentActionStatus.FAILED);
        assertThat(countActions(fixture)).isEqualTo(3);
        assertThat(fixture.repository().recoverExpiredExecutions(NOW)).isZero();
    }

    @Test
    void rejectAcceptsPendingAndFailedAndPreservesReviewActionExactly() {
        Fixture fixture = fixture("reject_review_action");
        insertPending(fixture, "action-ignore", NOW, NOW.plusHours(2));
        insertPending(fixture, "action-false-positive", NOW, NOW.plusHours(2));
        setFailed(fixture, "action-false-positive", 2L);

        assertThat(fixture.repository().reject(
                "action-ignore",
                1L,
                "reviewer-ignore",
                "ignore this signal",
                "IGNORE",
                NOW
        )).isTrue();
        assertThat(fixture.repository().reject(
                "action-false-positive",
                2L,
                "reviewer-false-positive",
                "known campaign traffic",
                "FALSE_POSITIVE",
                NOW
        )).isTrue();

        AgentPendingAction ignored = action(fixture, "action-ignore");
        assertThat(ignored.status()).isEqualTo(AgentActionStatus.REJECTED);
        assertThat(ignored.rejectedBy()).isEqualTo("reviewer-ignore");
        assertThat(ignored.rejectedTime()).isEqualTo(NOW);
        assertThat(ignored.rejectionReason()).isEqualTo("ignore this signal");
        assertThat(ignored.rejectionReviewAction()).isEqualTo("IGNORE");
        assertTerminalResourcesReleased(ignored);

        AgentPendingAction falsePositive = action(fixture, "action-false-positive");
        assertThat(falsePositive.status()).isEqualTo(AgentActionStatus.REJECTED);
        assertThat(falsePositive.rejectedBy()).isEqualTo("reviewer-false-positive");
        assertThat(falsePositive.rejectionReason()).isEqualTo("known campaign traffic");
        assertThat(falsePositive.rejectionReviewAction()).isEqualTo("FALSE_POSITIVE");
        assertTerminalResourcesReleased(falsePositive);
    }

    @Test
    void failedActionsCanOnlyBeClaimedAgainWhenReplayIsSafe() {
        Fixture fixture = fixture("failed_replay_gate");
        insertPending(fixture, "action-retry", NOW, NOW.plusHours(2));
        AgentActionClaim firstClaim = claim(fixture, "action-retry", 1L, "token-first", true);
        assertThat(fixture.repository().failExecution(
                "action-retry",
                firstClaim.executionToken(),
                firstClaim.claimedVersion(),
                "EXECUTOR_FAILED",
                "executor failed",
                NOW.plusMinutes(1)
        )).isTrue();
        AgentPendingAction failed = action(fixture, "action-retry");
        String retainedSlotHash = failed.activeSlotKey();

        assertThat(retainedSlotHash).isNotNull();
        assertExecutionLeaseReleased(failed);

        assertThat(fixture.repository().claimForExecution(
                "action-retry",
                failed.version(),
                "token-unsafe-retry",
                NOW.plusMinutes(2),
                Duration.ofMinutes(5),
                false
        )).isEmpty();
        assertThat(action(fixture, "action-retry").attemptCount()).isEqualTo(1);

        AgentActionClaim retried = claimAt(
                fixture,
                "action-retry",
                failed.version(),
                "token-safe-retry",
                NOW.plusMinutes(2),
                Duration.ofMinutes(5),
                true
        );
        assertThat(retried.claimedVersion()).isEqualTo(failed.version() + 1);
        assertThat(retried.action().status()).isEqualTo(AgentActionStatus.EXECUTING);
        assertThat(retried.action().attemptCount()).isEqualTo(2);
        assertThat(retried.action().activeSlotKey()).isEqualTo(retainedSlotHash);
    }

    @Test
    void expireDueOnlyExpiresDuePendingAndFailedActions() {
        Fixture fixture = fixture("expire_due");
        insertPending(fixture, "action-due-pending", NOW.minusHours(2), NOW);
        insertPending(fixture, "action-due-failed", NOW.minusHours(2), NOW);
        insertPending(fixture, "action-due-executing", NOW.minusMinutes(20), NOW);
        insertPending(fixture, "action-future-pending", NOW, NOW.plusHours(1));
        insertPending(fixture, "action-future-failed", NOW, NOW.plusHours(1));
        insertPending(fixture, "action-due-executed", NOW.minusHours(2), NOW);
        insertPending(fixture, "action-due-rejected", NOW.minusHours(2), NOW);
        insertPending(fixture, "action-already-expired", NOW.minusHours(2), NOW);
        setFailedWithStaleLease(fixture, "action-due-failed", 2L);
        setFailed(fixture, "action-future-failed", 2L);
        setTerminal(fixture, "action-due-executed", AgentActionStatus.EXECUTED, 5L);
        setTerminal(fixture, "action-due-rejected", AgentActionStatus.REJECTED, 6L);
        setTerminal(fixture, "action-already-expired", AgentActionStatus.EXPIRED, 7L);
        AgentActionClaim executing = claimAt(
                fixture,
                "action-due-executing",
                1L,
                "token-executing",
                NOW.minusMinutes(10),
                Duration.ofMinutes(30),
                true
        );

        assertThat(fixture.repository().claimForExecution(
                "action-due-pending",
                1L,
                "token-too-late",
                NOW,
                Duration.ofMinutes(5),
                true
        )).isEmpty();
        assertThat(fixture.repository().reject(
                "action-due-failed",
                2L,
                "late-reviewer",
                "too late",
                "IGNORE",
                NOW
        )).isFalse();

        assertThat(fixture.repository().expireDue(NOW)).isEqualTo(2);

        AgentPendingAction duePending = action(fixture, "action-due-pending");
        assertThat(duePending.status()).isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(duePending.version()).isEqualTo(2L);
        assertTerminalResourcesReleased(duePending);

        AgentPendingAction dueFailed = action(fixture, "action-due-failed");
        assertThat(dueFailed.status()).isEqualTo(AgentActionStatus.EXPIRED);
        assertThat(dueFailed.version()).isEqualTo(3L);
        assertTerminalResourcesReleased(dueFailed);

        AgentPendingAction dueExecuting = action(fixture, "action-due-executing");
        assertThat(dueExecuting.status()).isEqualTo(AgentActionStatus.EXECUTING);
        assertThat(dueExecuting.version()).isEqualTo(executing.claimedVersion());
        assertThat(dueExecuting.executionToken()).isEqualTo(executing.executionToken());
        assertThat(dueExecuting.activeSlotKey()).isNotNull();
        assertThat(action(fixture, "action-future-pending").status())
                .isEqualTo(AgentActionStatus.PENDING);
        assertThat(action(fixture, "action-future-failed").status())
                .isEqualTo(AgentActionStatus.FAILED);
        assertThat(action(fixture, "action-due-executed").version()).isEqualTo(5L);
        assertThat(action(fixture, "action-due-rejected").version()).isEqualTo(6L);
        assertThat(action(fixture, "action-already-expired").version()).isEqualTo(7L);
        assertThat(fixture.repository().expireDue(NOW)).isZero();
    }

    @Test
    void representativeInvalidInputsDoNotMutateActionState() {
        Fixture fixture = fixture("invalid_inputs");
        insertPending(fixture, "action-invalid-input", NOW, NOW.plusHours(2));
        AgentPendingAction pending = action(fixture, "action-invalid-input");

        assertThat(fixture.repository().claimForExecution(
                "action-invalid-input",
                pending.version(),
                "",
                NOW,
                Duration.ofMinutes(5),
                true
        )).isEmpty();
        assertThatThrownBy(() -> fixture.repository().claimForExecution(
                "action-invalid-input",
                pending.version(),
                "token-invalid-duration",
                NOW,
                Duration.ZERO,
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseDuration");
        assertThat(fixture.repository().reject(
                "action-invalid-input",
                pending.version(),
                null,
                "reason",
                "IGNORE",
                NOW
        )).isFalse();
        assertThat(action(fixture, "action-invalid-input")).isEqualTo(pending);

        AgentActionClaim claim = claim(
                fixture,
                "action-invalid-input",
                pending.version(),
                "token-valid",
                true
        );
        AgentPendingAction executing = action(fixture, "action-invalid-input");
        assertThat(fixture.repository().completeExecution(
                "action-invalid-input",
                claim.executionToken(),
                claim.claimedVersion(),
                null,
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(fixture.repository().failExecution(
                "action-invalid-input",
                claim.executionToken(),
                claim.claimedVersion(),
                null,
                "failure",
                NOW.plusMinutes(1)
        )).isFalse();
        assertThat(action(fixture, "action-invalid-input")).isEqualTo(executing);
    }

    private Optional<AgentActionClaim> claimAfterStart(
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start,
            String actionId,
            String token
    ) {
        ready.countDown();
        await(start);
        return fixture.repository().claimForExecution(
                actionId,
                1L,
                token,
                NOW,
                Duration.ofMinutes(5),
                true
        );
    }

    private AgentActionClaim claim(
            Fixture fixture,
            String actionId,
            long expectedVersion,
            String token,
            boolean replaySafe
    ) {
        return claimAt(
                fixture,
                actionId,
                expectedVersion,
                token,
                NOW,
                Duration.ofMinutes(5),
                replaySafe
        );
    }

    private AgentActionClaim claimAt(
            Fixture fixture,
            String actionId,
            long expectedVersion,
            String token,
            LocalDateTime now,
            Duration leaseDuration,
            boolean replaySafe
    ) {
        return fixture.repository().claimForExecution(
                actionId,
                expectedVersion,
                token,
                now,
                leaseDuration,
                replaySafe
        ).orElseThrow();
    }

    private AgentPendingAction insertPending(
            Fixture fixture,
            String actionId,
            LocalDateTime createTime,
            LocalDateTime expireTime
    ) {
        return fixture.repository().propose(
                proposal(actionId),
                fixture.codec(),
                createTime,
                expireTime
        ).action();
    }

    private void setFailed(Fixture fixture, String actionId, long version) {
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?, version = ?, update_time = ?
                        where action_id = ?
                        """,
                AgentActionStatus.FAILED.name(),
                version,
                Timestamp.valueOf(NOW.minusMinutes(1)),
                actionId
        );
    }

    private void setFailedWithStaleLease(Fixture fixture, String actionId, long version) {
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?,
                            version = ?,
                            execution_token = ?,
                            execution_lease_until = ?,
                            update_time = ?
                        where action_id = ?
                        """,
                AgentActionStatus.FAILED.name(),
                version,
                "stale-token",
                Timestamp.valueOf(NOW.minusMinutes(5)),
                Timestamp.valueOf(NOW.minusMinutes(1)),
                actionId
        );
    }

    private void setTerminal(
            Fixture fixture,
            String actionId,
            AgentActionStatus status,
            long version
    ) {
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?,
                            active_slot_key = null,
                            execution_token = '',
                            execution_lease_until = null,
                            version = ?,
                            update_time = ?
                        where action_id = ?
                        """,
                status.name(),
                version,
                Timestamp.valueOf(NOW.minusMinutes(1)),
                actionId
        );
    }

    private AgentPendingAction action(Fixture fixture, String actionId) {
        return fixture.repository().findByActionId(actionId).orElseThrow();
    }

    private void assertTerminalResourcesReleased(AgentPendingAction action) {
        assertThat(action.activeSlotKey()).isNull();
        assertExecutionLeaseReleased(action);
    }

    private void assertFailedExecutionOwnershipReleased(
            AgentPendingAction action,
            String expectedActiveSlotHash
    ) {
        assertThat(action.activeSlotKey()).isEqualTo(expectedActiveSlotHash).isNotNull();
        assertExecutionLeaseReleased(action);
    }

    private void assertExecutionLeaseReleased(AgentPendingAction action) {
        assertThat(action.executionToken()).isEmpty();
        assertThat(action.executionLeaseUntil()).isNull();
    }

    private AgentActionProposal proposal(String actionId) {
        return proposal(
                actionId,
                DISABLE_SHORT_LINK,
                "idem-" + actionId,
                "slot-" + actionId,
                "batch-" + actionId
        );
    }

    private AgentActionProposal proposal(
            String actionId,
            AgentActionType actionType,
            String idempotencyKey,
            String activeSlotKey,
            String batchId
    ) {
        return new AgentActionProposal(
                actionId,
                "security-risk",
                actionType,
                1,
                AgentActionAuthorizationScope.GID,
                "owner-user",
                "gid-1",
                "SHORT_LINK",
                "nurl.ink/" + actionId,
                Map.of("domain", "nurl.ink", "shortUri", actionId),
                "Action " + actionId,
                "Summary " + actionId,
                Map.of("enabled", false),
                Map.of("score", 90),
                idempotencyKey,
                activeSlotKey,
                "risk-analysis-worker",
                "trace-" + actionId,
                "event-" + actionId,
                batchId,
                "session-" + actionId
        );
    }

    private long countActions(Fixture fixture) {
        Long count = fixture.jdbcTemplate().queryForObject(
                "select count(1) from t_agent_pending_action",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private Fixture fixture(String name) {
        DataSource dataSource = dataSource(name);
        populate(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(
                jdbcTemplate,
                new JdbcAgentPendingActionRepository(jdbcTemplate),
                new AgentActionPayloadCodec(new ObjectMapper())
        );
    }

    private DataSource dataSource(String name) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + "_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "sa",
                ""
        );
    }

    private void populate(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent action update");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent action update was interrupted", ex);
        }
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcAgentPendingActionRepository repository,
            AgentActionPayloadCodec codec
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {
    }

    private static final class FailingPostClaimReadJdbcTemplate extends JdbcTemplate {

        private boolean claimUpdated;

        private boolean transactionActiveDuringClaimRead;

        private FailingPostClaimReadJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            int updated = super.update(sql, args);
            if (updated == 1 && sql.contains("attempt_count = attempt_count + 1")) {
                claimUpdated = true;
            }
            return updated;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
                throws DataAccessException {
            if (claimUpdated
                    && sql.contains("and execution_token = ?")
                    && sql.contains("and version = ?")) {
                transactionActiveDuringClaimRead =
                        TransactionSynchronizationManager.isActualTransactionActive();
                throw new DataRetrievalFailureException("Injected post-claim read failure");
            }
            return super.query(sql, rowMapper, args);
        }

        private boolean transactionActiveDuringClaimRead() {
            return transactionActiveDuringClaimRead;
        }
    }
}
