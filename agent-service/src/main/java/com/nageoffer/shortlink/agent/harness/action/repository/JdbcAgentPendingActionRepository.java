package com.nageoffer.shortlink.agent.harness.action.repository;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionClaim;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposalResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadCodec;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadConflictException;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcAgentPendingActionRepository {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Agent action proposal is invalid";
    private static final String LEASE_EXPIRED_CODE = "EXECUTION_LEASE_EXPIRED";
    private static final String LEASE_EXPIRED_MESSAGE = "Execution lease expired";

    private static final Duration MAX_EXECUTION_LEASE_DURATION = Duration.ofSeconds(
            AgentProperties.Action.MAX_EXECUTION_LEASE_SECONDS
    );

    private static final String ACTION_COLUMNS = """
            id,
            action_id,
            agent_type,
            action_type,
            payload_version,
            authorization_scope,
            owner_username,
            gid,
            target_type,
            target_key,
            target_ref_json,
            title,
            summary,
            payload_json,
            payload_hash,
            evidence_json,
            idempotency_key,
            active_slot_key,
            status,
            expire_time,
            version,
            execution_token,
            execution_lease_until,
            attempt_count,
            result_json,
            failure_code,
            failure_message,
            proposed_by,
            confirmed_by,
            confirmed_time,
            rejected_by,
            rejected_time,
            rejection_reason,
            rejection_review_action,
            trace_id,
            event_id,
            batch_id,
            session_id,
            create_time,
            update_time
            """;

    private static final String INSERT_SQL = """
            insert into t_agent_pending_action (
                action_id,
                agent_type,
                action_type,
                payload_version,
                authorization_scope,
                owner_username,
                gid,
                target_type,
                target_key,
                target_ref_json,
                title,
                summary,
                payload_json,
                payload_hash,
                evidence_json,
                idempotency_key,
                active_slot_key,
                status,
                expire_time,
                version,
                execution_token,
                execution_lease_until,
                attempt_count,
                result_json,
                failure_code,
                failure_message,
                proposed_by,
                confirmed_by,
                confirmed_time,
                rejected_by,
                rejected_time,
                rejection_reason,
                rejection_review_action,
                trace_id,
                event_id,
                batch_id,
                session_id,
                create_time,
                update_time
            )
            values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAgentPendingActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    public AgentActionProposalResult propose(
            AgentActionProposal proposal,
            AgentActionPayloadCodec codec,
            LocalDateTime now,
            LocalDateTime expireTime
    ) {
        validate(proposal, codec, now, expireTime);
        PreparedProposal prepared = prepare(proposal, codec, now, expireTime);

        try {
            insert(prepared);
            return createdResult(prepared.proposal().actionId());
        } catch (DuplicateKeyException ignored) {
            Optional<AgentActionProposalResult> resolved = resolveExisting(prepared);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        try {
            insert(prepared);
            return createdResult(prepared.proposal().actionId());
        } catch (DuplicateKeyException ignored) {
            Optional<AgentActionProposalResult> resolved = resolveExisting(prepared);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        return resolveActionIdFallback(prepared);
    }

    public Optional<AgentPendingAction> findByActionId(String actionId) {
        if (!hasText(actionId)) {
            return Optional.empty();
        }
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where action_id = ?
                        limit 1
                        """.formatted(ACTION_COLUMNS),
                actionId
        );
    }

    public Optional<AgentPendingAction> findByTypeAndIdempotencyKey(
            String actionType,
            String idempotencyKey
    ) {
        if (!hasText(actionType) || !hasText(idempotencyKey)) {
            return Optional.empty();
        }
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where action_type = ?
                          and idempotency_key = ?
                        limit 1
                        """.formatted(ACTION_COLUMNS),
                actionType,
                idempotencyKey
        );
    }

    public Optional<AgentPendingAction> findActiveBySlotKey(String activeSlotKey) {
        if (!hasText(activeSlotKey)) {
            return Optional.empty();
        }
        return findActiveBySlotHash(sha256(activeSlotKey));
    }

    @Transactional
    public Optional<AgentActionClaim> claimForExecution(
            String actionId,
            long expectedVersion,
            String executionToken,
            LocalDateTime now,
            Duration leaseDuration,
            boolean replaySafe
    ) {
        validateLeaseDuration(leaseDuration);
        if (!hasText(actionId)
                || expectedVersion < 0
                || !hasText(executionToken)
                || now == null) {
            return Optional.empty();
        }

        LocalDateTime leaseUntil = leaseUntil(now, leaseDuration);
        int updated = replaySafe
                ? jdbcTemplate.update("""
                                update t_agent_pending_action
                                set confirmed_time = coalesce(confirmed_time, ?),
                                    status = ?,
                                    execution_token = ?,
                                    execution_lease_until = ?,
                                    attempt_count = attempt_count + 1,
                                    result_json = '{}',
                                    failure_code = '',
                                    failure_message = '',
                                    version = version + 1,
                                    update_time = ?
                                where action_id = ?
                                  and version = ?
                                  and status in (?, ?)
                                  and (expire_time is null or expire_time > ?)
                                """,
                        timestamp(now),
                        AgentActionStatus.EXECUTING.name(),
                        executionToken,
                        timestamp(leaseUntil),
                        timestamp(now),
                        actionId,
                        expectedVersion,
                        AgentActionStatus.PENDING.name(),
                        AgentActionStatus.FAILED.name(),
                        timestamp(now)
                )
                : jdbcTemplate.update("""
                                update t_agent_pending_action
                                set confirmed_time = coalesce(confirmed_time, ?),
                                    status = ?,
                                    execution_token = ?,
                                    execution_lease_until = ?,
                                    attempt_count = attempt_count + 1,
                                    result_json = '{}',
                                    failure_code = '',
                                    failure_message = '',
                                    version = version + 1,
                                    update_time = ?
                                where action_id = ?
                                  and version = ?
                                  and status = ?
                                  and (expire_time is null or expire_time > ?)
                                """,
                        timestamp(now),
                        AgentActionStatus.EXECUTING.name(),
                        executionToken,
                        timestamp(leaseUntil),
                        timestamp(now),
                        actionId,
                        expectedVersion,
                        AgentActionStatus.PENDING.name(),
                        timestamp(now)
                );
        if (updated != 1) {
            return Optional.empty();
        }

        long claimedVersion = expectedVersion + 1;
        AgentPendingAction action = queryOne("""
                        select %s
                        from t_agent_pending_action
                        where action_id = ?
                          and status = ?
                          and execution_token = ?
                          and version = ?
                        limit 1
                        """.formatted(ACTION_COLUMNS),
                actionId,
                AgentActionStatus.EXECUTING.name(),
                executionToken,
                claimedVersion
        ).orElseThrow(() -> new IllegalStateException(
                "Claimed agent action could not be loaded"
        ));
        return Optional.of(new AgentActionClaim(action, executionToken, action.version()));
    }

    public boolean completeExecution(
            String actionId,
            String executionToken,
            long claimedVersion,
            String resultJson,
            LocalDateTime now
    ) {
        if (!hasText(actionId)
                || !hasText(executionToken)
                || claimedVersion < 0
                || resultJson == null
                || now == null) {
            return false;
        }
        return jdbcTemplate.update("""
                        update t_agent_pending_action
                        set status = ?,
                            active_slot_key = null,
                            execution_token = '',
                            execution_lease_until = null,
                            result_json = ?,
                            failure_code = '',
                            failure_message = '',
                            version = version + 1,
                            update_time = ?
                        where action_id = ?
                          and status = ?
                          and execution_token = ?
                          and version = ?
                        """,
                AgentActionStatus.EXECUTED.name(),
                resultJson,
                timestamp(now),
                actionId,
                AgentActionStatus.EXECUTING.name(),
                executionToken,
                claimedVersion
        ) == 1;
    }

    public boolean failExecution(
            String actionId,
            String executionToken,
            long claimedVersion,
            String failureCode,
            String failureMessage,
            LocalDateTime now
    ) {
        if (!hasText(actionId)
                || !hasText(executionToken)
                || claimedVersion < 0
                || failureCode == null
                || failureMessage == null
                || now == null) {
            return false;
        }
        return jdbcTemplate.update("""
                        update t_agent_pending_action
                        set status = ?,
                            execution_token = '',
                            execution_lease_until = null,
                            failure_code = ?,
                            failure_message = ?,
                            version = version + 1,
                            update_time = ?
                        where action_id = ?
                          and status = ?
                          and execution_token = ?
                          and version = ?
                        """,
                AgentActionStatus.FAILED.name(),
                failureCode,
                failureMessage,
                timestamp(now),
                actionId,
                AgentActionStatus.EXECUTING.name(),
                executionToken,
                claimedVersion
        ) == 1;
    }

    public boolean reject(
            String actionId,
            long expectedVersion,
            String rejectedBy,
            String reason,
            String reviewAction,
            LocalDateTime now
    ) {
        if (!hasText(actionId)
                || expectedVersion < 0
                || rejectedBy == null
                || reason == null
                || now == null) {
            return false;
        }
        return jdbcTemplate.update("""
                        update t_agent_pending_action
                        set status = ?,
                            active_slot_key = null,
                            execution_token = '',
                            execution_lease_until = null,
                            rejected_by = ?,
                            rejected_time = ?,
                            rejection_reason = ?,
                            rejection_review_action = ?,
                            version = version + 1,
                            update_time = ?
                        where action_id = ?
                          and version = ?
                          and status in (?, ?)
                          and (expire_time is null or expire_time > ?)
                        """,
                AgentActionStatus.REJECTED.name(),
                rejectedBy,
                timestamp(now),
                reason,
                reviewAction,
                timestamp(now),
                actionId,
                expectedVersion,
                AgentActionStatus.PENDING.name(),
                AgentActionStatus.FAILED.name(),
                timestamp(now)
        ) == 1;
    }

    public int expireDue(LocalDateTime now) {
        if (now == null) {
            return 0;
        }
        return jdbcTemplate.update("""
                        update t_agent_pending_action
                        set status = ?,
                            active_slot_key = null,
                            execution_token = '',
                            execution_lease_until = null,
                            version = version + 1,
                            update_time = ?
                        where status in (?, ?)
                          and expire_time is not null
                          and expire_time <= ?
                        """,
                AgentActionStatus.EXPIRED.name(),
                timestamp(now),
                AgentActionStatus.PENDING.name(),
                AgentActionStatus.FAILED.name(),
                timestamp(now)
        );
    }

    public int recoverExpiredExecutions(LocalDateTime now) {
        if (now == null) {
            return 0;
        }
        return jdbcTemplate.update("""
                        update t_agent_pending_action
                        set status = ?,
                            execution_token = '',
                            execution_lease_until = null,
                            failure_code = ?,
                            failure_message = ?,
                            version = version + 1,
                            update_time = ?
                        where status = ?
                          and execution_lease_until is not null
                          and execution_lease_until <= ?
                        """,
                AgentActionStatus.FAILED.name(),
                LEASE_EXPIRED_CODE,
                LEASE_EXPIRED_MESSAGE,
                timestamp(now),
                AgentActionStatus.EXECUTING.name(),
                timestamp(now)
        );
    }

    public AgentActionPage<AgentPendingAction> page(
            String gid,
            String agentType,
            String actionType,
            AgentActionStatus status,
            int pageNo,
            int pageSize
    ) {
        int normalizedPageNo = Math.max(1, pageNo);
        int normalizedPageSize = Math.min(100, Math.max(1, pageSize));
        QuerySpec querySpec = querySpec(gid, agentType, actionType, status);

        Long count = jdbcTemplate.queryForObject("""
                        select count(1)
                        from t_agent_pending_action
                        %s
                        """.formatted(querySpec.whereClause()),
                Long.class,
                querySpec.args().toArray()
        );

        List<Object> rowArgs = new ArrayList<>(querySpec.args());
        rowArgs.add(normalizedPageSize);
        rowArgs.add((long) (normalizedPageNo - 1) * normalizedPageSize);
        List<AgentPendingAction> records = jdbcTemplate.query("""
                        select %s
                        from t_agent_pending_action
                        %s
                        order by update_time desc, id desc
                        limit ? offset ?
                        """.formatted(ACTION_COLUMNS, querySpec.whereClause()),
                this::mapAction,
                rowArgs.toArray()
        );

        return new AgentActionPage<>(
                records,
                count == null ? 0L : count,
                normalizedPageNo,
                normalizedPageSize
        );
    }

    private PreparedProposal prepare(
            AgentActionProposal proposal,
            AgentActionPayloadCodec codec,
            LocalDateTime now,
            LocalDateTime expireTime
    ) {
        String payloadJson = codec.canonicalJson(proposal.payload());
        return new PreparedProposal(
                proposal,
                codec.canonicalJson(proposal.targetRef()),
                payloadJson,
                sha256(payloadJson),
                codec.canonicalJson(proposal.evidence()),
                sha256(proposal.activeSlotKey()),
                now,
                expireTime
        );
    }

    private void insert(PreparedProposal prepared) {
        AgentActionProposal proposal = prepared.proposal();
        jdbcTemplate.update(
                INSERT_SQL,
                proposal.actionId(),
                proposal.agentType(),
                proposal.actionType().value(),
                proposal.payloadVersion(),
                proposal.authorizationScope().name(),
                proposal.ownerUsername(),
                proposal.gid(),
                proposal.targetType(),
                proposal.targetKey(),
                prepared.targetRefJson(),
                proposal.title(),
                proposal.summary(),
                prepared.payloadJson(),
                prepared.payloadHash(),
                prepared.evidenceJson(),
                proposal.idempotencyKey(),
                prepared.activeSlotHash(),
                AgentActionStatus.PENDING.name(),
                timestamp(prepared.expireTime()),
                1L,
                "",
                null,
                0,
                "{}",
                "",
                "",
                proposal.proposedBy(),
                "",
                null,
                "",
                null,
                "",
                null,
                proposal.traceId(),
                proposal.eventId(),
                proposal.batchId(),
                proposal.sessionId(),
                timestamp(prepared.now()),
                timestamp(prepared.now())
        );
    }

    private Optional<AgentActionProposalResult> resolveExisting(PreparedProposal prepared) {
        AgentActionProposal proposal = prepared.proposal();
        Optional<AgentPendingAction> idempotent = findByTypeAndIdempotencyKeyForUpdate(
                proposal.actionType().value(),
                proposal.idempotencyKey()
        );
        if (idempotent.isPresent()) {
            AgentPendingAction existing = idempotent.get();
            if (!existing.payloadHash().equals(prepared.payloadHash())) {
                throw new AgentActionPayloadConflictException();
            }
            return Optional.of(new AgentActionProposalResult(existing, false));
        }

        return findActiveBySlotHashForUpdate(prepared.activeSlotHash())
                .map(existing -> new AgentActionProposalResult(existing, false));
    }

    private AgentActionProposalResult resolveActionIdFallback(PreparedProposal prepared) {
        AgentActionProposal proposal = prepared.proposal();
        Optional<AgentPendingAction> actionIdMatch = findByActionIdForUpdate(proposal.actionId());
        if (actionIdMatch.isPresent()) {
            AgentPendingAction existing = actionIdMatch.get();
            if (existing.actionType().value().equals(proposal.actionType().value())
                    && existing.idempotencyKey().equals(proposal.idempotencyKey())
                    && existing.payloadHash().equals(prepared.payloadHash())) {
                return new AgentActionProposalResult(existing, false);
            }
            throw new IllegalStateException(
                    "Agent action id conflicts with an existing proposal"
            );
        }
        throw new IllegalStateException(
                "Agent action proposal conflict could not be resolved"
        );
    }

    private AgentActionProposalResult createdResult(String actionId) {
        AgentPendingAction action = findByActionId(actionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Inserted agent action could not be loaded"
                ));
        return new AgentActionProposalResult(action, true);
    }

    private Optional<AgentPendingAction> findActiveBySlotHash(String activeSlotHash) {
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where active_slot_key = ?
                          and status in (?, ?, ?)
                        order by id desc
                        limit 1
                        """.formatted(ACTION_COLUMNS),
                activeSlotHash,
                AgentActionStatus.PENDING.name(),
                AgentActionStatus.EXECUTING.name(),
                AgentActionStatus.FAILED.name()
        );
    }

    private Optional<AgentPendingAction> findByActionIdForUpdate(String actionId) {
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where action_id = ?
                        limit 1 for update
                        """.formatted(ACTION_COLUMNS),
                actionId
        );
    }

    private Optional<AgentPendingAction> findByTypeAndIdempotencyKeyForUpdate(
            String actionType,
            String idempotencyKey
    ) {
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where action_type = ?
                          and idempotency_key = ?
                        limit 1 for update
                        """.formatted(ACTION_COLUMNS),
                actionType,
                idempotencyKey
        );
    }

    private Optional<AgentPendingAction> findActiveBySlotHashForUpdate(String activeSlotHash) {
        return queryOne("""
                        select %s
                        from t_agent_pending_action
                        where active_slot_key = ?
                          and status in (?, ?, ?)
                        order by id desc
                        limit 1 for update
                        """.formatted(ACTION_COLUMNS),
                activeSlotHash,
                AgentActionStatus.PENDING.name(),
                AgentActionStatus.EXECUTING.name(),
                AgentActionStatus.FAILED.name()
        );
    }

    private Optional<AgentPendingAction> queryOne(String sql, Object... args) {
        List<AgentPendingAction> actions = jdbcTemplate.query(sql, this::mapAction, args);
        return actions.stream().findFirst();
    }

    private QuerySpec querySpec(
            String gid,
            String agentType,
            String actionType,
            AgentActionStatus status
    ) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (hasText(gid)) {
            clauses.add("gid = ?");
            args.add(gid);
        }
        if (hasText(agentType)) {
            clauses.add("agent_type = ?");
            args.add(agentType);
        }
        if (hasText(actionType)) {
            clauses.add("action_type = ?");
            args.add(actionType);
        }
        if (status != null) {
            clauses.add("status = ?");
            args.add(status.name());
        }
        return new QuerySpec(
                clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses),
                List.copyOf(args)
        );
    }

    private AgentPendingAction mapAction(ResultSet rs, int rowNum) throws SQLException {
        return new AgentPendingAction(
                rs.getLong("id"),
                rs.getString("action_id"),
                rs.getString("agent_type"),
                new AgentActionType(rs.getString("action_type")),
                rs.getInt("payload_version"),
                AgentActionAuthorizationScope.valueOf(rs.getString("authorization_scope")),
                rs.getString("owner_username"),
                rs.getString("gid"),
                rs.getString("target_type"),
                rs.getString("target_key"),
                rs.getString("target_ref_json"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("payload_json"),
                rs.getString("payload_hash"),
                rs.getString("evidence_json"),
                rs.getString("idempotency_key"),
                rs.getString("active_slot_key"),
                AgentActionStatus.valueOf(rs.getString("status")),
                localDateTime(rs.getTimestamp("expire_time")),
                rs.getLong("version"),
                rs.getString("execution_token"),
                localDateTime(rs.getTimestamp("execution_lease_until")),
                rs.getInt("attempt_count"),
                rs.getString("result_json"),
                rs.getString("failure_code"),
                rs.getString("failure_message"),
                rs.getString("proposed_by"),
                rs.getString("confirmed_by"),
                localDateTime(rs.getTimestamp("confirmed_time")),
                rs.getString("rejected_by"),
                localDateTime(rs.getTimestamp("rejected_time")),
                rs.getString("rejection_reason"),
                rs.getString("rejection_review_action"),
                rs.getString("trace_id"),
                rs.getString("event_id"),
                rs.getString("batch_id"),
                rs.getString("session_id"),
                localDateTime(rs.getTimestamp("create_time")),
                localDateTime(rs.getTimestamp("update_time"))
        );
    }

    private void validate(
            AgentActionProposal proposal,
            AgentActionPayloadCodec codec,
            LocalDateTime now,
            LocalDateTime expireTime
    ) {
        if (proposal == null
                || codec == null
                || now == null
                || expireTime == null
                || !expireTime.isAfter(now)
                || !validProposal(proposal)) {
            throw new AgentActionException(INVALID_CODE, INVALID_MESSAGE);
        }
    }

    private boolean validProposal(AgentActionProposal proposal) {
        return hasText(proposal.actionId())
                && hasText(proposal.agentType())
                && proposal.actionType() != null
                && hasText(proposal.actionType().value())
                && proposal.payloadVersion() > 0
                && validAuthorizationScope(proposal)
                && hasText(proposal.targetType())
                && hasText(proposal.targetKey())
                && proposal.targetRef() != null
                && hasText(proposal.title())
                && proposal.summary() != null
                && proposal.payload() != null
                && proposal.evidence() != null
                && hasText(proposal.idempotencyKey())
                && hasText(proposal.activeSlotKey())
                && hasText(proposal.proposedBy())
                && proposal.traceId() != null
                && proposal.eventId() != null
                && proposal.batchId() != null
                && proposal.sessionId() != null;
    }

    private boolean validAuthorizationScope(AgentActionProposal proposal) {
        if (proposal.authorizationScope() == AgentActionAuthorizationScope.GID) {
            return hasText(proposal.gid()) && proposal.ownerUsername() != null;
        }
        if (proposal.authorizationScope() == AgentActionAuthorizationScope.OWNER) {
            return hasText(proposal.ownerUsername()) && proposal.gid() != null;
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validateLeaseDuration(Duration leaseDuration) {
        if (leaseDuration == null) {
            throw new IllegalArgumentException("leaseDuration must not be null");
        }
        if (leaseDuration.isZero()
                || leaseDuration.isNegative()
                || leaseDuration.compareTo(MAX_EXECUTION_LEASE_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive and at most "
                            + AgentProperties.Action.MAX_EXECUTION_LEASE_SECONDS
                            + " seconds"
            );
        }
    }

    private LocalDateTime leaseUntil(LocalDateTime now, Duration leaseDuration) {
        try {
            return now.plus(leaseDuration);
        } catch (DateTimeException | ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "leaseDuration exceeds the supported LocalDateTime range",
                    ex
            );
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private record PreparedProposal(
            AgentActionProposal proposal,
            String targetRefJson,
            String payloadJson,
            String payloadHash,
            String evidenceJson,
            String activeSlotHash,
            LocalDateTime now,
            LocalDateTime expireTime
    ) {
    }

    private record QuerySpec(String whereClause, List<Object> args) {
    }
}
