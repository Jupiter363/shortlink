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
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyDeleteResult;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskPolicySyncServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-12T04:00:00Z"),
            ZoneId.of("Asia/Shanghai")
    );

    @Mock
    private JdbcRiskPolicySyncOutboxRepository outboxRepository;

    @Mock
    private JdbcEffectiveRiskPolicyRepository effectiveRepository;

    @Mock
    private JdbcRiskPolicyRepository policyRepository;

    @Mock
    private JdbcRiskActionAuditRepository auditRepository;

    @Mock
    private RiskPolicyRedisPublisher publisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private RiskPolicySyncService service;

    @BeforeEach
    void setUp() {
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        service = new RiskPolicySyncService(
                outboxRepository,
                effectiveRepository,
                policyRepository,
                auditRepository,
                publisher,
                transactionTemplate,
                CLOCK
        );
    }

    @Test
    void staleUpsertIsSkippedBeforeRedisCall() {
        RiskPolicySyncOutbox outbox = outbox(
                "outbox-1", "policy-1", 1L, RiskPolicySyncOperation.UPSERT, "value-1");
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective(
                        "outbox-2", "policy-2", 2L, RiskPolicyDesiredState.ACTIVE, null)));

        service.process(outbox, "worker-1", NOW);

        verifyNoInteractions(publisher);
        verify(outboxRepository).markSkipped(
                eq("outbox-1"), eq("worker-1"), eq("STALE_POLICY_VERSION"), eq(NOW));
    }

    @Test
    void matchingUpsertPublishesAndMarksEffectiveSynced() {
        RiskPolicySyncOutbox outbox = outbox(
                "outbox-1", "policy-1", 1L, RiskPolicySyncOperation.UPSERT, "value-1");
        EffectiveRiskPolicy effective = effective(
                outbox.outboxId(), outbox.policyId(), outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE, NOW.plusHours(1));
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective));
        when(publisher.publish(outbox.policyKey(), outbox.redisValueJson(), effective.expireTime()))
                .thenReturn(true);
        when(outboxRepository.markSucceeded(outbox.outboxId(), "worker-1", NOW))
                .thenReturn(true);
        when(effectiveRepository.updateSyncStatusIfStateAndVersion(
                any(), any(), anyLong(), any(), any(), any(), any()
        )).thenReturn(true);

        service.process(outbox, "worker-1", NOW);

        verify(publisher).publish(outbox.policyKey(), outbox.redisValueJson(), effective.expireTime());
        verify(effectiveRepository).updateSyncStatusIfStateAndVersion(
                outbox.policyKey(),
                outbox.policyId(),
                outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE,
                RiskPolicySyncStatus.SYNCED,
                outbox.outboxId(),
                effective.traceId()
        );
    }

    @Test
    void deleteMismatchIsSkippedAndMarksEffectiveDead() {
        RiskPolicySyncOutbox outbox = outbox(
                "outbox-3", "policy-1", 3L, RiskPolicySyncOperation.DELETE, "expected-v2");
        EffectiveRiskPolicy effective = effective(
                outbox.outboxId(), outbox.policyId(), outbox.policyVersion(),
                RiskPolicyDesiredState.DISABLED, null);
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective));
        when(publisher.compareAndDelete(outbox.policyKey(), outbox.expectedRedisValue()))
                .thenReturn(RiskPolicyDeleteResult.VALUE_MISMATCH);
        when(outboxRepository.markSkipped(
                eq(outbox.outboxId()), eq("worker-1"), any(), eq(NOW)
        )).thenReturn(true);
        when(effectiveRepository.updateSyncStatusIfStateAndVersion(
                any(), any(), anyLong(), any(), any(), any(), any()
        )).thenReturn(true);

        service.process(outbox, "worker-1", NOW);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).markSkipped(
                eq(outbox.outboxId()), eq("worker-1"), reason.capture(), eq(NOW));
        assertThat(reason.getValue()).startsWith("REDIS_VALUE_MISMATCH");
        verify(effectiveRepository).updateSyncStatusIfStateAndVersion(
                outbox.policyKey(),
                outbox.policyId(),
                outbox.policyVersion(),
                RiskPolicyDesiredState.DISABLED,
                RiskPolicySyncStatus.DEAD,
                outbox.outboxId(),
                effective.traceId()
        );
    }

    @Test
    void expiredUpsertCreatesSameVersionDeleteWithoutPublishing() {
        RiskPolicySyncOutbox outbox = outbox(
                "outbox-4", "policy-1", 4L, RiskPolicySyncOperation.UPSERT, "version-4-value");
        EffectiveRiskPolicy effective = effective(
                outbox.outboxId(), outbox.policyId(), outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE, NOW.minusSeconds(1));
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective));
        when(outboxRepository.markSkipped(
                outbox.outboxId(), "worker-1", "POLICY_EXPIRED_BEFORE_SYNC", NOW
        )).thenReturn(true);
        when(policyRepository.markExpired(effective.policyId(), effective.traceId()))
                .thenReturn(true);
        when(outboxRepository.createIfAbsent(any())).thenReturn(true);
        when(effectiveRepository.markExpiredIfVersion(
                eq(effective.policyKey()),
                eq(effective.policyId()),
                eq(effective.policyVersion()),
                any()
        )).thenReturn(true);

        service.process(outbox, "worker-1", NOW);

        verify(publisher, never()).publish(any(), any(), any());
        ArgumentCaptor<RiskPolicySyncOutbox> deleteCaptor =
                ArgumentCaptor.forClass(RiskPolicySyncOutbox.class);
        verify(outboxRepository).createIfAbsent(deleteCaptor.capture());
        RiskPolicySyncOutbox delete = deleteCaptor.getValue();
        assertThat(delete.operation()).isEqualTo(RiskPolicySyncOperation.DELETE);
        assertThat(delete.policyVersion()).isEqualTo(4L);
        assertThat(delete.expectedRedisValue()).isEqualTo(effective.redisValueJson());
    }

    @Test
    void exhaustedFailureMarksMatchingEffectiveSlotDead() {
        RiskPolicySyncOutbox outbox = outbox(
                "outbox-dead", "policy-1", 1L, RiskPolicySyncOperation.UPSERT, "value-1");
        outbox = new RiskPolicySyncOutbox(
                outbox.id(), outbox.outboxId(), outbox.policyKey(), outbox.policyId(),
                outbox.policyVersion(), outbox.operation(), outbox.redisValueJson(),
                outbox.expectedRedisValue(), outbox.status(), 10, outbox.nextRetryTime(),
                outbox.ownerToken(), outbox.leaseUntil(), outbox.lastError(),
                outbox.createTime(), outbox.updateTime()
        );
        EffectiveRiskPolicy effective = effective(
                outbox.outboxId(), outbox.policyId(), outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE, null);
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective));
        when(outboxRepository.recordFailure(
                outbox.outboxId(), "worker-1", 10, NOW, NOW.plusMinutes(10), "failure"
        )).thenReturn(true);
        when(effectiveRepository.updateSyncStatusIfStateAndVersion(
                any(), any(), anyLong(), any(), any(), any(), any()
        )).thenReturn(true);

        service.recordFailure(
                outbox, "worker-1", 10, NOW, NOW.plusMinutes(10), "failure");

        verify(effectiveRepository).updateSyncStatusIfStateAndVersion(
                outbox.policyKey(),
                outbox.policyId(),
                outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE,
                RiskPolicySyncStatus.DEAD,
                outbox.outboxId(),
                effective.traceId()
        );
    }

    @Test
    void deadOutboxReplayResetsSyncStateAndWritesAudit() {
        RiskPolicySyncOutbox outbox = withStatus(
                outbox("outbox-5", "policy-1", 1L, RiskPolicySyncOperation.UPSERT, "value-1"),
                RiskPolicySyncOutboxStatus.DEAD,
                "Redis unavailable"
        );
        EffectiveRiskPolicy effective = effective(
                outbox.outboxId(), outbox.policyId(), outbox.policyVersion(),
                RiskPolicyDesiredState.ACTIVE, null);
        when(outboxRepository.findByOutboxId(outbox.outboxId()))
                .thenReturn(Optional.of(outbox));
        when(outboxRepository.findByOutboxIdForUpdate(outbox.outboxId()))
                .thenReturn(Optional.of(outbox));
        when(effectiveRepository.findByPolicyKeyForUpdate(outbox.policyKey()))
                .thenReturn(Optional.of(effective));
        when(outboxRepository.resetForReplay(outbox.outboxId(), NOW)).thenReturn(true);
        when(effectiveRepository.updateSyncStatusIfStateAndVersion(
                any(), any(), anyLong(), any(), any(), any(), any()
        )).thenReturn(true);
        AgentActionActor actor = new AgentActionActor(
                "operator-1", "1001", "Risk Operator", "");

        RiskPolicySyncOutboxStatus status = service.replay(
                outbox.outboxId(), actor, "retry after redis recovery");

        assertThat(status).isEqualTo(RiskPolicySyncOutboxStatus.PENDING);
        verify(auditRepository).saveOutboxReplayAudit(
                outbox.outboxId(),
                outbox.policyId(),
                actor.username(),
                "retry after redis recovery"
        );
    }

    @Test
    void nonReplayableOutboxDoesNotWriteAudit() {
        RiskPolicySyncOutbox outbox = withStatus(
                outbox("outbox-6", "policy-1", 1L, RiskPolicySyncOperation.UPSERT, "value-1"),
                RiskPolicySyncOutboxStatus.SKIPPED,
                "STALE_POLICY_VERSION"
        );
        when(outboxRepository.findByOutboxId(outbox.outboxId()))
                .thenReturn(Optional.of(outbox));

        assertThatThrownBy(() -> service.replay(
                outbox.outboxId(),
                new AgentActionActor("operator-1", "1001", "Risk Operator", ""),
                "retry"
        )).isInstanceOf(AgentActionException.class)
                .extracting(ex -> ((AgentActionException) ex).code())
                .isEqualTo("POLICY_SYNC_OUTBOX_NOT_REPLAYABLE");
        verifyNoInteractions(auditRepository);
    }

    private RiskPolicySyncOutbox outbox(
            String outboxId,
            String policyId,
            long policyVersion,
            RiskPolicySyncOperation operation,
            String value
    ) {
        return new RiskPolicySyncOutbox(
                1L,
                outboxId,
                "risk:policy:short-link:rate-limit:nurl.ink:abc123",
                policyId,
                policyVersion,
                operation,
                operation == RiskPolicySyncOperation.UPSERT ? value : "",
                operation == RiskPolicySyncOperation.DELETE ? value : "",
                RiskPolicySyncOutboxStatus.PROCESSING,
                1,
                null,
                "worker-1",
                NOW.plusMinutes(5),
                "",
                NOW.minusMinutes(1),
                NOW
        );
    }

    private EffectiveRiskPolicy effective(
            String outboxId,
            String policyId,
            long policyVersion,
            RiskPolicyDesiredState desiredState,
            LocalDateTime expireTime
    ) {
        return new EffectiveRiskPolicy(
                1L,
                "risk:policy:short-link:rate-limit:nurl.ink:abc123",
                policyId,
                policyVersion,
                "gid-1",
                RiskPolicyAction.LIMIT_RATE,
                desiredState,
                "{\"action\":\"LIMIT_RATE\",\"limit\":30,\"windowSeconds\":60}",
                "version-" + policyVersion + "-value",
                NOW.minusHours(1),
                expireTime,
                RiskPolicySyncStatus.PENDING,
                outboxId,
                "trace-1",
                NOW.minusHours(1),
                NOW
        );
    }

    private RiskPolicySyncOutbox withStatus(
            RiskPolicySyncOutbox source,
            RiskPolicySyncOutboxStatus status,
            String error
    ) {
        return new RiskPolicySyncOutbox(
                source.id(),
                source.outboxId(),
                source.policyKey(),
                source.policyId(),
                source.policyVersion(),
                source.operation(),
                source.redisValueJson(),
                source.expectedRedisValue(),
                status,
                source.attemptCount(),
                source.nextRetryTime(),
                "",
                null,
                error,
                source.createTime(),
                source.updateTime()
        );
    }
}
