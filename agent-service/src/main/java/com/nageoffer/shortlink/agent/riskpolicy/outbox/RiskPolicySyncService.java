package com.nageoffer.shortlink.agent.riskpolicy.outbox;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDesiredState;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicySyncStatus;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyDeleteResult;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyRedisPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Service
public class RiskPolicySyncService {

    static final String REDIS_VALUE_MISMATCH_PREFIX = "REDIS_VALUE_MISMATCH";

    private static final String OUTBOX_NOT_FOUND = "POLICY_SYNC_OUTBOX_NOT_FOUND";
    private static final String OUTBOX_NOT_FOUND_MESSAGE = "Risk policy sync outbox was not found";
    private static final String OUTBOX_NOT_REPLAYABLE = "POLICY_SYNC_OUTBOX_NOT_REPLAYABLE";
    private static final String OUTBOX_NOT_REPLAYABLE_MESSAGE =
            "Risk policy sync outbox cannot be replayed";
    private static final String ACTION_SCOPE_FORBIDDEN = "ACTION_SCOPE_FORBIDDEN";
    private static final String ACTION_SCOPE_FORBIDDEN_MESSAGE = "Agent action access is forbidden";
    private static final String PAYLOAD_INVALID = "ACTION_PAYLOAD_INVALID";
    private static final String PAYLOAD_INVALID_MESSAGE = "Risk policy sync request is invalid";
    private static final String STALE_POLICY = "STALE_POLICY_VERSION";
    private static final String EXPIRED_BEFORE_SYNC = "POLICY_EXPIRED_BEFORE_SYNC";
    private static final int MAX_REPLAY_REASON_LENGTH = 2048;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcRiskPolicySyncOutboxRepository outboxRepository;
    private final JdbcEffectiveRiskPolicyRepository effectiveRepository;
    private final JdbcRiskPolicyRepository policyRepository;
    private final JdbcRiskActionAuditRepository auditRepository;
    private final RiskPolicyRedisPublisher redisPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public RiskPolicySyncService(
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            TransactionTemplate transactionTemplate
    ) {
        this(
                outboxRepository,
                effectiveRepository,
                policyRepository,
                auditRepository,
                redisPublisher,
                transactionTemplate,
                Clock.system(SHANGHAI)
        );
    }

    public RiskPolicySyncService(
            JdbcRiskPolicySyncOutboxRepository outboxRepository,
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskPolicyRepository policyRepository,
            JdbcRiskActionAuditRepository auditRepository,
            RiskPolicyRedisPublisher redisPublisher,
            TransactionTemplate transactionTemplate,
            Clock clock
    ) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.effectiveRepository = Objects.requireNonNull(effectiveRepository, "effectiveRepository must not be null");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.redisPublisher = Objects.requireNonNull(redisPublisher, "redisPublisher must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void process(RiskPolicySyncOutbox outbox, String ownerToken, LocalDateTime now) {
        Objects.requireNonNull(outbox, "outbox must not be null");
        requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(now, "now must not be null");
        if (outbox.status() != RiskPolicySyncOutboxStatus.PROCESSING) {
            throw new IllegalArgumentException("Risk policy sync outbox must be claimed");
        }

        if (outbox.operation() == RiskPolicySyncOperation.UPSERT) {
            processUpsert(outbox, ownerToken, now);
            return;
        }
        processDelete(outbox, ownerToken, now);
    }

    public void recordFailure(
            RiskPolicySyncOutbox outbox,
            String ownerToken,
            int maxAttempts,
            LocalDateTime failureTime,
            LocalDateTime nextRetryTime,
            String error
    ) {
        Objects.requireNonNull(outbox, "outbox must not be null");
        requireText(ownerToken, "ownerToken");
        Objects.requireNonNull(failureTime, "failureTime must not be null");
        transactionTemplate.executeWithoutResult(status -> {
            EffectiveRiskPolicy effective = effectiveRepository
                    .findByPolicyKeyForUpdate(outbox.policyKey())
                    .orElse(null);
            boolean recorded = outboxRepository.recordFailure(
                    outbox.outboxId(),
                    ownerToken,
                    maxAttempts,
                    failureTime,
                    nextRetryTime,
                    error
            );
            if (!recorded || effective == null) {
                return;
            }
            RiskPolicyDesiredState desiredState = expectedState(outbox, effective);
            if (outbox.operation() == RiskPolicySyncOperation.DELETE
                    && desiredState == RiskPolicyDesiredState.ACTIVE) {
                return;
            }
            if (!matches(effective, outbox, desiredState)) {
                return;
            }
            RiskPolicySyncStatus syncStatus = outbox.attemptCount() >= Math.max(1, maxAttempts)
                    ? RiskPolicySyncStatus.DEAD
                    : RiskPolicySyncStatus.RETRY_WAIT;
            if (!effectiveRepository.updateSyncStatusIfStateAndVersion(
                    effective.policyKey(),
                    effective.policyId(),
                    effective.policyVersion(),
                    desiredState,
                    syncStatus,
                    outbox.outboxId(),
                    effective.traceId()
            )) {
                throw new IllegalStateException("Risk policy effective slot changed during failure handling");
            }
        });
    }

    public RiskPolicySyncOutboxStatus replay(
            String outboxId,
            AgentActionActor actor,
            String reason
    ) {
        requireReplayActor(actor);
        String normalizedReason = normalizeReason(reason);
        RiskPolicySyncOutbox candidate = outboxRepository.findByOutboxId(outboxId)
                .orElseThrow(() -> error(OUTBOX_NOT_FOUND, OUTBOX_NOT_FOUND_MESSAGE));
        if (!isReplayable(candidate)) {
            throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
        }
        RiskPolicySyncOutboxStatus result = transactionTemplate.execute(status -> {
            EffectiveRiskPolicy effective = effectiveRepository
                    .findByPolicyKeyForUpdate(candidate.policyKey())
                    .orElseThrow(() -> error(
                            OUTBOX_NOT_REPLAYABLE,
                            OUTBOX_NOT_REPLAYABLE_MESSAGE
                    ));
            RiskPolicySyncOutbox outbox = outboxRepository.findByOutboxIdForUpdate(outboxId)
                    .orElseThrow(() -> error(OUTBOX_NOT_FOUND, OUTBOX_NOT_FOUND_MESSAGE));
            if (!isReplayable(outbox)) {
                throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
            }
            if (!candidate.policyKey().equals(outbox.policyKey())) {
                throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
            }
            RiskPolicyDesiredState expectedState = expectedState(outbox, effective);
            if (outbox.operation() == RiskPolicySyncOperation.DELETE
                    && expectedState == RiskPolicyDesiredState.ACTIVE) {
                throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
            }
            if (!matches(effective, outbox, expectedState)) {
                throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
            }
            if (!outboxRepository.resetForReplay(outbox.outboxId(), now())) {
                throw error(OUTBOX_NOT_REPLAYABLE, OUTBOX_NOT_REPLAYABLE_MESSAGE);
            }
            if (!effectiveRepository.updateSyncStatusIfStateAndVersion(
                    effective.policyKey(),
                    effective.policyId(),
                    effective.policyVersion(),
                    expectedState,
                    RiskPolicySyncStatus.PENDING,
                    outbox.outboxId(),
                    effective.traceId()
            )) {
                throw new IllegalStateException("Risk policy effective slot changed during replay");
            }
            auditRepository.saveOutboxReplayAudit(
                    outbox.outboxId(),
                    outbox.policyId(),
                    actor.username(),
                    normalizedReason
            );
            return RiskPolicySyncOutboxStatus.PENDING;
        });
        return Objects.requireNonNull(result, "Risk policy replay transaction returned no result");
    }

    private void processUpsert(
            RiskPolicySyncOutbox outbox,
            String ownerToken,
            LocalDateTime now
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            EffectiveRiskPolicy effective = effectiveRepository
                    .findByPolicyKeyForUpdate(outbox.policyKey())
                    .orElse(null);
            if (!matches(effective, outbox, RiskPolicyDesiredState.ACTIVE)) {
                outboxRepository.markSkipped(outbox.outboxId(), ownerToken, STALE_POLICY, now);
                return;
            }
            if (isExpired(effective.expireTime(), now)) {
                convertExpiredUpsertLocked(outbox, ownerToken, now, effective);
                return;
            }
            if (!redisPublisher.publish(
                    outbox.policyKey(),
                    outbox.redisValueJson(),
                    effective.expireTime()
            )) {
                throw new IllegalStateException("Risk policy expired while publishing");
            }
            completeSyncLocked(
                    outbox,
                    ownerToken,
                    effective,
                    RiskPolicyDesiredState.ACTIVE,
                    RiskPolicySyncStatus.SYNCED,
                    false,
                    "",
                    now()
            );
        });
    }

    private void processDelete(
            RiskPolicySyncOutbox outbox,
            String ownerToken,
            LocalDateTime now
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            EffectiveRiskPolicy effective = effectiveRepository
                    .findByPolicyKeyForUpdate(outbox.policyKey())
                    .orElse(null);
            RiskPolicyDesiredState desiredState = expectedState(outbox, effective);
            if (desiredState == RiskPolicyDesiredState.ACTIVE
                    || !matches(effective, outbox, desiredState)) {
                outboxRepository.markSkipped(outbox.outboxId(), ownerToken, STALE_POLICY, now);
                return;
            }

            RiskPolicyDeleteResult result = redisPublisher.compareAndDelete(
                    outbox.policyKey(),
                    outbox.expectedRedisValue()
            );
            if (result == RiskPolicyDeleteResult.VALUE_MISMATCH) {
                completeSyncLocked(
                        outbox,
                        ownerToken,
                        effective,
                        desiredState,
                        RiskPolicySyncStatus.DEAD,
                        true,
                        REDIS_VALUE_MISMATCH_PREFIX
                                + ": Redis value differs from expected policy value",
                        now()
                );
                return;
            }
            completeSyncLocked(
                    outbox,
                    ownerToken,
                    effective,
                    desiredState,
                    RiskPolicySyncStatus.SYNCED,
                    false,
                    "",
                    now()
            );
        });
    }

    private void completeSyncLocked(
            RiskPolicySyncOutbox outbox,
            String ownerToken,
            EffectiveRiskPolicy effective,
            RiskPolicyDesiredState desiredState,
            RiskPolicySyncStatus syncStatus,
            boolean skipped,
            String reason,
            LocalDateTime now
    ) {
        boolean transitioned = skipped
                ? outboxRepository.markSkipped(outbox.outboxId(), ownerToken, reason, now)
                : outboxRepository.markSucceeded(outbox.outboxId(), ownerToken, now);
        if (!transitioned) {
            return;
        }
        if (!effectiveRepository.updateSyncStatusIfStateAndVersion(
                effective.policyKey(),
                effective.policyId(),
                effective.policyVersion(),
                desiredState,
                syncStatus,
                outbox.outboxId(),
                effective.traceId()
        )) {
            throw new IllegalStateException("Risk policy effective slot changed during sync");
        }
    }

    private void convertExpiredUpsertLocked(
            RiskPolicySyncOutbox outbox,
            String ownerToken,
            LocalDateTime now,
            EffectiveRiskPolicy effective
    ) {
        if (!outboxRepository.markSkipped(
                outbox.outboxId(),
                ownerToken,
                EXPIRED_BEFORE_SYNC,
                now
        )) {
            return;
        }
        if (!policyRepository.markExpired(effective.policyId(), effective.traceId())) {
            throw new IllegalStateException("Risk policy history changed during expiration");
        }
        String deleteOutboxId = outboxId(
                effective.policyKey(),
                effective.policyVersion(),
                RiskPolicySyncOperation.DELETE
        );
        outboxRepository.createIfAbsent(pendingDelete(effective, deleteOutboxId, now));
        if (!effectiveRepository.markExpiredIfVersion(
                effective.policyKey(),
                effective.policyId(),
                effective.policyVersion(),
                deleteOutboxId
        )) {
            throw new IllegalStateException("Risk policy effective slot changed during expiration");
        }
    }

    private boolean matches(
            EffectiveRiskPolicy effective,
            RiskPolicySyncOutbox outbox,
            RiskPolicyDesiredState desiredState
    ) {
        return effective != null
                && effective.policyKey().equals(outbox.policyKey())
                && effective.policyId().equals(outbox.policyId())
                && effective.policyVersion() == outbox.policyVersion()
                && effective.desiredState() == desiredState
                && effective.lastOutboxId().equals(outbox.outboxId());
    }

    private RiskPolicyDesiredState expectedState(
            RiskPolicySyncOutbox outbox,
            EffectiveRiskPolicy effective
    ) {
        if (outbox.operation() == RiskPolicySyncOperation.UPSERT) {
            return RiskPolicyDesiredState.ACTIVE;
        }
        return effective == null ? RiskPolicyDesiredState.ACTIVE : effective.desiredState();
    }

    private RiskPolicySyncOutbox pendingDelete(
            EffectiveRiskPolicy effective,
            String outboxId,
            LocalDateTime now
    ) {
        return new RiskPolicySyncOutbox(
                null,
                outboxId,
                effective.policyKey(),
                effective.policyId(),
                effective.policyVersion(),
                RiskPolicySyncOperation.DELETE,
                "",
                effective.redisValueJson(),
                RiskPolicySyncOutboxStatus.PENDING,
                0,
                null,
                "",
                null,
                "",
                now,
                now
        );
    }

    private boolean isReplayable(RiskPolicySyncOutbox outbox) {
        return outbox.status() == RiskPolicySyncOutboxStatus.DEAD
                || (outbox.status() == RiskPolicySyncOutboxStatus.SKIPPED
                && outbox.lastError().startsWith(REDIS_VALUE_MISMATCH_PREFIX));
    }

    private void requireReplayActor(AgentActionActor actor) {
        if (actor == null
                || !StringUtils.hasText(actor.username())
                || !StringUtils.hasText(actor.userId())
                || !StringUtils.hasText(actor.realName())) {
            throw error(ACTION_SCOPE_FORBIDDEN, ACTION_SCOPE_FORBIDDEN_MESSAGE);
        }
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason) || reason.length() > MAX_REPLAY_REASON_LENGTH) {
            throw error(PAYLOAD_INVALID, PAYLOAD_INVALID_MESSAGE);
        }
        return RiskIpSafety.sanitizeIpLiterals(reason.trim());
    }

    private String outboxId(
            String policyKey,
            long policyVersion,
            RiskPolicySyncOperation operation
    ) {
        String value = operation.name() + "|" + policyKey + "|" + policyVersion;
        return "outbox-" + UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isExpired(LocalDateTime expireTime, LocalDateTime now) {
        return expireTime != null && !expireTime.isAfter(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), clock.getZone());
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private AgentActionException error(String code, String message) {
        return new AgentActionException(code, message);
    }
}
