package com.nageoffer.shortlink.agent.harness.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposalResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadCodec;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class AgentPendingActionRepositoryTest {

    private static final AgentActionType DISABLE_SHORT_LINK =
            new AgentActionType("risk.disable-short-link");
    private static final AgentActionType LIMIT_TIME_WINDOW =
            new AgentActionType("risk.limit-time-window");
    private static final AgentActionType PAUSE_PLACEMENT =
            new AgentActionType("campaign.pause-placement");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 9, 30);
    private static final LocalDateTime EXPIRE_TIME = NOW.plusHours(2);

    @Test
    void proposalResultIsARecordAndRejectsNullActions() {
        assertThat(AgentActionProposalResult.class.isRecord()).isTrue();
        assertThat(Arrays.stream(AgentActionProposalResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("action", "created");

        assertThatThrownBy(() -> new AgentActionProposalResult(null, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("action must not be null");
    }

    @Test
    void sameTypeAndIdempotencyKeyWithSamePayloadReturnsOriginalWithoutUpdatingIt() {
        Fixture fixture = fixture();
        AgentActionProposal original = proposal(
                "action-same-1",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                Map.of("domain", "nurl.ink", "shortUri", "abc"),
                "Original title",
                "Original summary",
                Map.of("enabled", false, "reason", "abuse"),
                Map.of("score", 92),
                "idem-same",
                "slot-same-1",
                "batch-1"
        );
        AgentActionProposal retry = proposal(
                "action-same-2",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                Map.of("domain", "changed.example"),
                "Changed title",
                "Changed summary",
                Map.of("enabled", false, "reason", "abuse"),
                Map.of("score", 1),
                "idem-same",
                "slot-same-2",
                "batch-2"
        );

        AgentActionProposalResult created = fixture.repository().propose(
                original,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );
        AgentActionProposalResult duplicate = fixture.repository().propose(
                retry,
                fixture.codec(),
                NOW.plusMinutes(5),
                EXPIRE_TIME.plusHours(3)
        );

        assertThat(created.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.action()).isEqualTo(created.action());
        assertThat(duplicate.action().actionId()).isEqualTo("action-same-1");
        assertThat(duplicate.action().title()).isEqualTo("Original title");
        assertThat(duplicate.action().evidenceJson()).isEqualTo("{\"score\":92}");
        assertThat(duplicate.action().expireTime()).isEqualTo(EXPIRE_TIME);
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(1);
    }

    @Test
    void sameTypeAndIdempotencyKeyWithDifferentPayloadThrowsStableConflict() {
        Fixture fixture = fixture();
        fixture.repository().propose(
                proposal("action-conflict-1", "idem-conflict", "slot-conflict-1", Map.of("limit", 10)),
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );

        assertThatThrownBy(() -> fixture.repository().propose(
                proposal("action-conflict-2", "idem-conflict", "slot-conflict-2", Map.of("limit", 20)),
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ))
                .isInstanceOf(AgentActionPayloadConflictException.class)
                .hasMessage("Agent action payload conflicts with an existing action")
                .extracting(throwable -> ((AgentActionException) throwable).code())
                .isEqualTo("ACTION_PAYLOAD_CONFLICT");
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(1);
    }

    @Test
    void canonicalPayloadMapOrderDoesNotCauseAnIdempotencyConflict() {
        Fixture fixture = fixture();
        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("gid", "gid-1");
        firstPayload.put("domain", "nurl.ink");
        Map<String, Object> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("domain", "nurl.ink");
        reorderedPayload.put("gid", "gid-1");

        AgentActionProposalResult first = fixture.repository().propose(
                proposal("action-order-1", "idem-order", "slot-order-1", firstPayload),
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );
        AgentActionProposalResult second = fixture.repository().propose(
                proposal("action-order-2", "idem-order", "slot-order-2", reorderedPayload),
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.action()).isEqualTo(first.action());
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(1);
    }

    @Test
    void activeSlotDeduplicatesAcrossActionTypeBatchIdempotencyAndPayloadAndUsesRawSha256() {
        Fixture fixture = fixture();
        String rawSlot = "gid-1|SHORT_LINK|nurl.ink/abc";
        AgentActionProposal firstProposal = proposal(
                "action-slot-1",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                Map.of("domain", "nurl.ink"),
                "Disable",
                "High risk",
                Map.of("enabled", false),
                Map.of("score", 91),
                "idem-slot-1",
                rawSlot,
                "batch-slot-1"
        );
        AgentActionProposal secondProposal = proposal(
                "action-slot-2",
                "campaign-agent",
                PAUSE_PLACEMENT,
                "gid-2",
                Map.of("campaignId", "campaign-2"),
                "Pause",
                "Related placement",
                Map.of("paused", true, "placement", "p-2"),
                Map.of("score", 72),
                "idem-slot-2",
                rawSlot,
                "batch-slot-2"
        );

        AgentActionProposalResult first = fixture.repository().propose(
                firstProposal,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );
        AgentActionProposalResult second = fixture.repository().propose(
                secondProposal,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        );
        String storedHash = fixture.jdbcTemplate().queryForObject(
                "select active_slot_key from t_agent_pending_action where action_id = ?",
                String.class,
                "action-slot-1"
        );

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.action()).isEqualTo(first.action());
        assertThat(storedHash)
                .isEqualTo(rawSha256(rawSlot))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(fixture.codec().hash(rawSlot));
        assertThat(fixture.repository().findActiveBySlotKey(rawSlot))
                .contains(first.action());
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(1);
    }

    @Test
    void pendingExecutingAndFailedRemainActiveWhileReleasedTerminalSlotsCanBeReused() {
        Fixture fixture = fixture();
        for (AgentActionStatus status : List.of(
                AgentActionStatus.PENDING,
                AgentActionStatus.EXECUTING,
                AgentActionStatus.FAILED
        )) {
            String suffix = status.name().toLowerCase(Locale.ROOT);
            String slot = "slot-active-" + suffix;
            AgentActionProposalResult original = fixture.repository().propose(
                    proposal("action-active-" + suffix, "idem-active-" + suffix, slot, Map.of("v", 1)),
                    fixture.codec(),
                    NOW,
                    EXPIRE_TIME
            );
            fixture.jdbcTemplate().update(
                    "update t_agent_pending_action set status = ? where action_id = ?",
                    status.name(),
                    original.action().actionId()
            );

            AgentActionProposalResult duplicate = fixture.repository().propose(
                    proposal(
                            "action-active-duplicate-" + suffix,
                            "idem-active-duplicate-" + suffix,
                            slot,
                            Map.of("v", 2)
                    ),
                    fixture.codec(),
                    NOW,
                    EXPIRE_TIME
            );

            assertThat(duplicate.created()).isFalse();
            assertThat(duplicate.action().actionId()).isEqualTo(original.action().actionId());
            assertThat(duplicate.action().status()).isEqualTo(status);
            assertThat(fixture.repository().findActiveBySlotKey(slot))
                    .get()
                    .extracting(AgentPendingAction::status)
                    .isEqualTo(status);
        }

        for (AgentActionStatus terminal : List.of(
                AgentActionStatus.EXECUTED,
                AgentActionStatus.REJECTED,
                AgentActionStatus.EXPIRED
        )) {
            String suffix = terminal.name().toLowerCase(Locale.ROOT);
            String slot = "slot-terminal-" + suffix;
            AgentActionProposalResult original = fixture.repository().propose(
                    proposal("action-terminal-" + suffix, "idem-terminal-" + suffix, slot, Map.of("v", 1)),
                    fixture.codec(),
                    NOW,
                    EXPIRE_TIME
            );
            fixture.jdbcTemplate().update(
                    "update t_agent_pending_action set status = ?, active_slot_key = null where action_id = ?",
                    terminal.name(),
                    original.action().actionId()
            );

            assertThat(fixture.repository().findActiveBySlotKey(slot)).isEmpty();
            AgentActionProposalResult replacement = fixture.repository().propose(
                    proposal(
                            "action-replacement-" + suffix,
                            "idem-replacement-" + suffix,
                            slot,
                            Map.of("v", 2)
                    ),
                    fixture.codec(),
                    NOW.plusMinutes(1),
                    EXPIRE_TIME.plusMinutes(1)
            );

            assertThat(replacement.created()).isTrue();
            assertThat(replacement.action().actionId()).isEqualTo("action-replacement-" + suffix);
        }

        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(9);
    }

    @Test
    void lookupMethodsMapAllPendingActionFieldsIncludingNullableTimestamps() {
        Fixture fixture = fixture();
        Map<String, Object> targetRef = Map.of("domain", "nurl.ink", "shortUri", "mapped");
        Map<String, Object> payload = Map.of("enabled", false, "threshold", 80);
        Map<String, Object> evidence = Map.of("score", 95, "signals", List.of("BOT", "BURST"));
        AgentActionProposal proposal = proposal(
                "action-mapped",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-mapped",
                targetRef,
                "Mapped title",
                "Mapped summary",
                payload,
                evidence,
                "idem-mapped",
                "slot-mapped",
                "batch-mapped"
        );
        fixture.repository().propose(proposal, fixture.codec(), NOW, EXPIRE_TIME);
        LocalDateTime leaseUntil = NOW.plusMinutes(15);
        LocalDateTime confirmedTime = NOW.plusMinutes(2);
        LocalDateTime rejectedTime = NOW.plusMinutes(3);
        LocalDateTime updatedTime = NOW.plusMinutes(4);
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?,
                            version = ?,
                            execution_token = ?,
                            execution_lease_until = ?,
                            attempt_count = ?,
                            result_json = ?,
                            failure_code = ?,
                            failure_message = ?,
                            confirmed_by = ?,
                            confirmed_time = ?,
                            rejected_by = ?,
                            rejected_time = ?,
                            rejection_reason = ?,
                            rejection_review_action = ?,
                            update_time = ?
                        where action_id = ?
                        """,
                AgentActionStatus.FAILED.name(),
                7L,
                "execution-token",
                Timestamp.valueOf(leaseUntil),
                3,
                "{\"ok\":false}",
                "EXECUTION_FAILED",
                "stable failure",
                "confirm-user",
                Timestamp.valueOf(confirmedTime),
                "reject-user",
                Timestamp.valueOf(rejectedTime),
                "not approved",
                "ESCALATE",
                Timestamp.valueOf(updatedTime),
                proposal.actionId()
        );

        AgentPendingAction mapped = fixture.repository().findByActionId(proposal.actionId()).orElseThrow();
        AgentPendingAction expected = new AgentPendingAction(
                mapped.id(),
                proposal.actionId(),
                proposal.agentType(),
                proposal.actionType(),
                proposal.payloadVersion(),
                proposal.authorizationScope(),
                proposal.ownerUsername(),
                proposal.gid(),
                proposal.targetType(),
                proposal.targetKey(),
                fixture.codec().canonicalJson(targetRef),
                proposal.title(),
                proposal.summary(),
                fixture.codec().canonicalJson(payload),
                fixture.codec().hash(payload),
                fixture.codec().canonicalJson(evidence),
                proposal.idempotencyKey(),
                rawSha256(proposal.activeSlotKey()),
                AgentActionStatus.FAILED,
                EXPIRE_TIME,
                7L,
                "execution-token",
                leaseUntil,
                3,
                "{\"ok\":false}",
                "EXECUTION_FAILED",
                "stable failure",
                proposal.proposedBy(),
                "confirm-user",
                confirmedTime,
                "reject-user",
                rejectedTime,
                "not approved",
                "ESCALATE",
                proposal.traceId(),
                proposal.eventId(),
                proposal.batchId(),
                proposal.sessionId(),
                NOW,
                updatedTime
        );

        assertThat(mapped.id()).isPositive();
        assertThat(mapped).isEqualTo(expected);
        assertThat(fixture.repository().findByTypeAndIdempotencyKey(
                proposal.actionType().value(),
                proposal.idempotencyKey()
        )).contains(expected);
        assertThat(fixture.repository().findActiveBySlotKey(proposal.activeSlotKey()))
                .contains(expected);
        assertThat(fixture.repository().findByActionId("missing-action")).isEmpty();
        assertThat(fixture.repository().findByTypeAndIdempotencyKey("", "")).isEmpty();
        assertThat(fixture.repository().findActiveBySlotKey(" ")).isEmpty();
    }

    @Test
    void findLatestRejectedUsesActionTargetAndRejectedTimeAndPreservesReviewAction() {
        Fixture fixture = fixture();
        AgentActionProposal older = proposal(
                "action-rejected-older", "idem-rejected-older", "slot-rejected-older", Map.of("v", 1)
        );
        AgentActionProposal newer = proposal(
                "action-rejected-newer", "idem-rejected-newer", "slot-rejected-newer", Map.of("v", 2)
        );
        fixture.repository().propose(older, fixture.codec(), NOW, EXPIRE_TIME);
        fixture.repository().propose(newer, fixture.codec(), NOW, EXPIRE_TIME);
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?, target_key = ?, active_slot_key = null,
                            rejected_time = ?, rejection_review_action = ?
                        where action_id = ?
                        """,
                AgentActionStatus.REJECTED.name(),
                "nurl.ink/abc123",
                Timestamp.valueOf(NOW.minusDays(2)),
                "IGNORE",
                older.actionId()
        );
        fixture.jdbcTemplate().update("""
                        update t_agent_pending_action
                        set status = ?, target_key = ?, active_slot_key = null,
                            rejected_time = ?, rejection_review_action = ?
                        where action_id = ?
                        """,
                AgentActionStatus.REJECTED.name(),
                "nurl.ink/abc123",
                Timestamp.valueOf(NOW.minusDays(1)),
                "FALSE_POSITIVE",
                newer.actionId()
        );

        assertThat(fixture.repository().findLatestRejected(
                DISABLE_SHORT_LINK.value(),
                "nurl.ink/abc123"
        )).get().satisfies(action -> {
            assertThat(action.actionId()).isEqualTo(newer.actionId());
            assertThat(action.rejectionReviewAction()).isEqualTo("FALSE_POSITIVE");
            assertThat(action.rejectedTime()).isEqualTo(NOW.minusDays(1));
        });
        assertThat(fixture.repository().findLatestRejected(
                LIMIT_TIME_WINDOW.value(),
                "nurl.ink/abc123"
        )).isEmpty();
        assertThat(fixture.repository().findLatestRejected(DISABLE_SHORT_LINK.value(), " ")).isEmpty();
        assertThat(fixture.repository().findLatestRejected(null, "nurl.ink/abc123")).isEmpty();
    }

    @Test
    void insertedRowsUseRequiredInitialValuesAndInjectedTimestamps() {
        Fixture fixture = fixture();
        AgentActionProposal proposal = proposal(
                "action-initial",
                "idem-initial",
                "slot-initial",
                Map.of("enabled", false)
        );

        AgentPendingAction action = fixture.repository().propose(
                proposal,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ).action();

        assertThat(action.status()).isEqualTo(AgentActionStatus.PENDING);
        assertThat(action.version()).isEqualTo(1L);
        assertThat(action.executionToken()).isEmpty();
        assertThat(action.executionLeaseUntil()).isNull();
        assertThat(action.attemptCount()).isZero();
        assertThat(action.resultJson()).isEqualTo("{}");
        assertThat(action.failureCode()).isEmpty();
        assertThat(action.failureMessage()).isEmpty();
        assertThat(action.confirmedBy()).isEmpty();
        assertThat(action.confirmedTime()).isNull();
        assertThat(action.rejectedBy()).isEmpty();
        assertThat(action.rejectedTime()).isNull();
        assertThat(action.rejectionReason()).isEmpty();
        assertThat(action.rejectionReviewAction()).isNull();
        assertThat(action.expireTime()).isEqualTo(EXPIRE_TIME);
        assertThat(action.createTime()).isEqualTo(NOW);
        assertThat(action.updateTime()).isEqualTo(NOW);
    }

    @Test
    void insertSuccessWithoutReadableActionFailsAsAnInternalInvariantWithoutBusinessCodeOrSensitiveData() {
        JdbcAgentPendingActionRepository repository = new JdbcAgentPendingActionRepository(
                new InsertWithoutReadJdbcTemplate()
        );
        AgentActionProposal proposal = proposal(
                "sensitive-action-id",
                "sensitive-idempotency-key",
                "sensitive-active-slot",
                Map.of("secret", "payload-secret-marker")
        );

        Throwable thrown = catchThrowable(() -> repository.propose(
                proposal,
                codec(),
                NOW,
                EXPIRE_TIME
        ));

        assertThat(thrown)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Inserted agent action could not be loaded");
        assertThat(thrown.getMessage())
                .doesNotContain(
                        "ACTION_PERSISTENCE_FAILED",
                        "sensitive-action-id",
                        "sensitive-idempotency-key",
                        "sensitive-active-slot",
                        "payload-secret-marker",
                        "insert",
                        "select"
                );
    }

    @Test
    void pageAppliesCombinedFiltersOrderingPaginationAndBounds() {
        Fixture fixture = fixture();
        LocalDateTime pageNow = LocalDateTime.of(2026, 7, 11, 12, 0);
        insertForPage(fixture, "page-a1", "security-risk", DISABLE_SHORT_LINK, "gid-1",
                AgentActionStatus.PENDING, pageNow.plusMinutes(5));
        insertForPage(fixture, "page-a2", "security-risk", DISABLE_SHORT_LINK, "gid-1",
                AgentActionStatus.PENDING, pageNow.plusMinutes(5));
        insertForPage(fixture, "page-failed", "security-risk", DISABLE_SHORT_LINK, "gid-1",
                AgentActionStatus.FAILED, pageNow.plusMinutes(6));
        insertForPage(fixture, "page-type", "security-risk", LIMIT_TIME_WINDOW, "gid-1",
                AgentActionStatus.PENDING, pageNow.plusMinutes(4));
        insertForPage(fixture, "page-agent", "campaign-agent", DISABLE_SHORT_LINK, "gid-1",
                AgentActionStatus.PENDING, pageNow.plusMinutes(3));
        insertForPage(fixture, "page-gid", "security-risk", DISABLE_SHORT_LINK, "gid-2",
                AgentActionStatus.PENDING, pageNow.plusMinutes(2));

        AgentActionPage<AgentPendingAction> firstPage = fixture.repository().page(
                "gid-1",
                "security-risk",
                DISABLE_SHORT_LINK.value(),
                AgentActionStatus.PENDING,
                1,
                1
        );
        AgentActionPage<AgentPendingAction> secondPage = fixture.repository().page(
                "gid-1",
                "security-risk",
                DISABLE_SHORT_LINK.value(),
                AgentActionStatus.PENDING,
                2,
                1
        );
        AgentActionPage<AgentPendingAction> minimums = fixture.repository().page(
                " ",
                "",
                null,
                null,
                0,
                0
        );
        AgentActionPage<AgentPendingAction> maximum = fixture.repository().page(
                null,
                null,
                " ",
                null,
                1,
                101
        );

        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.pageNo()).isEqualTo(1);
        assertThat(firstPage.pageSize()).isEqualTo(1);
        assertThat(firstPage.records()).extracting(AgentPendingAction::actionId)
                .containsExactly("page-a2");
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(secondPage.records()).extracting(AgentPendingAction::actionId)
                .containsExactly("page-a1");
        assertThat(minimums.total()).isEqualTo(6);
        assertThat(minimums.pageNo()).isEqualTo(1);
        assertThat(minimums.pageSize()).isEqualTo(1);
        assertThat(minimums.records()).extracting(AgentPendingAction::actionId)
                .containsExactly("page-failed");
        assertThat(maximum.total()).isEqualTo(6);
        assertThat(maximum.pageSize()).isEqualTo(100);
        assertThat(maximum.records()).hasSize(6);
        assertThatThrownBy(() -> maximum.records().add(maximum.records().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invalidProposalInputsUseStablePayloadInvalidErrorWithoutWritingRows() {
        Fixture fixture = fixture();
        AgentActionProposal valid = proposal(
                "action-invalid",
                "idem-invalid",
                "slot-invalid",
                Map.of("secret", "payload-secret-marker")
        );

        assertInvalid(() -> fixture.repository().propose(null, fixture.codec(), NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(valid, null, NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(valid, fixture.codec(), null, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(valid, fixture.codec(), NOW, null));
        assertInvalid(() -> fixture.repository().propose(valid, fixture.codec(), NOW, NOW));
        assertInvalid(() -> fixture.repository().propose(valid, fixture.codec(), NOW, NOW.minusSeconds(1)));
        assertInvalid(() -> fixture.repository().propose(
                withIdentifiers(valid, " ", valid.agentType(), valid.actionType(), valid.idempotencyKey(), valid.activeSlotKey()),
                fixture.codec(), NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(
                withIdentifiers(valid, valid.actionId(), " ", valid.actionType(), valid.idempotencyKey(), valid.activeSlotKey()),
                fixture.codec(), NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(
                withIdentifiers(valid, valid.actionId(), valid.agentType(), null, valid.idempotencyKey(), valid.activeSlotKey()),
                fixture.codec(), NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(
                withIdentifiers(valid, valid.actionId(), valid.agentType(), valid.actionType(), " ", valid.activeSlotKey()),
                fixture.codec(), NOW, EXPIRE_TIME));
        assertInvalid(() -> fixture.repository().propose(
                withIdentifiers(valid, valid.actionId(), valid.agentType(), valid.actionType(), valid.idempotencyKey(), " "),
                fixture.codec(), NOW, EXPIRE_TIME));

        assertThat(countActions(fixture.jdbcTemplate())).isZero();
    }

    @Test
    void validationRequiresTheIdentifierSelectedByAuthorizationScope() {
        Fixture fixture = fixture();
        AgentActionProposal validGid = withAuthorizationScope(
                proposal("action-scope-gid", "idem-scope-gid", "slot-scope-gid", Map.of("v", 1)),
                AgentActionAuthorizationScope.GID,
                "",
                "gid-scope"
        );
        AgentActionProposal validOwner = withAuthorizationScope(
                proposal("action-scope-owner", "idem-scope-owner", "slot-scope-owner", Map.of("v", 2)),
                AgentActionAuthorizationScope.OWNER,
                "owner-scope",
                ""
        );
        AgentActionProposal invalidGid = withAuthorizationScope(
                proposal("action-scope-invalid-gid", "idem-scope-invalid-gid", "slot-scope-invalid-gid", Map.of("v", 3)),
                AgentActionAuthorizationScope.GID,
                "",
                " "
        );
        AgentActionProposal invalidOwner = withAuthorizationScope(
                proposal("action-scope-invalid-owner", "idem-scope-invalid-owner", "slot-scope-invalid-owner", Map.of("v", 4)),
                AgentActionAuthorizationScope.OWNER,
                " ",
                ""
        );

        AgentPendingAction gidAction = fixture.repository().propose(
                validGid,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ).action();
        AgentPendingAction ownerAction = fixture.repository().propose(
                validOwner,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ).action();
        assertInvalid(() -> fixture.repository().propose(
                invalidGid,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ));
        assertInvalid(() -> fixture.repository().propose(
                invalidOwner,
                fixture.codec(),
                NOW,
                EXPIRE_TIME
        ));

        assertThat(gidAction.authorizationScope()).isEqualTo(AgentActionAuthorizationScope.GID);
        assertThat(gidAction.gid()).isEqualTo("gid-scope");
        assertThat(gidAction.ownerUsername()).isEmpty();
        assertThat(ownerAction.authorizationScope()).isEqualTo(AgentActionAuthorizationScope.OWNER);
        assertThat(ownerAction.ownerUsername()).isEqualTo("owner-scope");
        assertThat(ownerAction.gid()).isEmpty();
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(2);
    }

    @Test
    void actionIdConflictRetriesOnlyOnceThenFailsAsAnInternalInvariant() {
        DataSource dataSource = dataSource();
        populate(dataSource);
        CountingJdbcTemplate jdbcTemplate = new CountingJdbcTemplate(dataSource);
        JdbcAgentPendingActionRepository repository = new JdbcAgentPendingActionRepository(jdbcTemplate);
        AgentActionPayloadCodec codec = codec();
        repository.propose(
                proposal("action-id-conflict", "idem-action-id-1", "slot-action-id-1", Map.of("v", 1)),
                codec,
                NOW,
                EXPIRE_TIME
        );
        jdbcTemplate.resetInsertAttempts();
        AgentActionProposal conflicting = proposal(
                "action-id-conflict",
                "idem-action-id-2",
                "slot-action-id-2",
                Map.of("v", 2)
        );

        IllegalStateException conflict = assertTimeout(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> repository.propose(conflicting, codec, NOW, EXPIRE_TIME)
                )
        );

        assertThat(conflict.getMessage())
                .isEqualTo("Agent action id conflicts with an existing proposal")
                .doesNotContain(
                        "ACTION_PAYLOAD_CONFLICT",
                        "action-id-conflict",
                        "idem-action-id-2",
                        "slot-action-id-2",
                        "insert",
                        "select"
                );
        assertThat(jdbcTemplate.insertAttempts()).isEqualTo(2);
        assertThat(countActions(jdbcTemplate)).isEqualTo(1);
    }

    @Test
    void unresolvedDuplicateUsesLockingReadsAndFailsWithoutExpandingBusinessConflictCodes() {
        AlwaysDuplicateWithoutRowsJdbcTemplate jdbcTemplate =
                new AlwaysDuplicateWithoutRowsJdbcTemplate();
        JdbcAgentPendingActionRepository repository = new JdbcAgentPendingActionRepository(jdbcTemplate);
        AgentActionProposal proposal = proposal(
                "sensitive-unresolved-action",
                "sensitive-unresolved-idempotency",
                "sensitive-unresolved-slot",
                Map.of("secret", "payload-secret-marker")
        );

        Throwable thrown = catchThrowable(() -> repository.propose(
                proposal,
                codec(),
                NOW,
                EXPIRE_TIME
        ));

        assertThat(thrown)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Agent action proposal conflict could not be resolved");
        assertThat(thrown.getMessage()).doesNotContain(
                "ACTION_PAYLOAD_CONFLICT",
                "sensitive-unresolved-action",
                "sensitive-unresolved-idempotency",
                "sensitive-unresolved-slot",
                "payload-secret-marker",
                "duplicate-key",
                "insert",
                "select"
        );
        assertThat(jdbcTemplate.insertAttempts()).isEqualTo(2);
        assertThat(jdbcTemplate.querySql()).hasSize(5).allSatisfy(sql ->
                assertThat(sql.toLowerCase(Locale.ROOT)).contains("for update")
        );
        assertThat(jdbcTemplate.querySql()).anySatisfy(sql ->
                assertThat(sql).contains("where action_type = ?")
        );
        assertThat(jdbcTemplate.querySql()).anySatisfy(sql ->
                assertThat(sql).contains("where active_slot_key = ?")
        );
        assertThat(jdbcTemplate.querySql()).anySatisfy(sql ->
                assertThat(sql).contains("where action_id = ?")
        );
    }

    @Test
    void concurrentSameIdempotencyProposalsCreateOneRowAndReturnOneExisting() throws Exception {
        Fixture fixture = fixture();
        AgentActionProposal first = proposal(
                "action-concurrent-idem-1",
                "idem-concurrent",
                "slot-concurrent-idem-1",
                Map.of("limit", 10)
        );
        AgentActionProposal second = proposal(
                "action-concurrent-idem-2",
                "idem-concurrent",
                "slot-concurrent-idem-2",
                Map.of("limit", 10)
        );

        List<AgentActionProposalResult> results = concurrentlyPropose(fixture, first, second);

        assertConcurrentDeduplication(fixture, results);
    }

    @Test
    void concurrentSameActiveSlotProposalsCreateOneRowAndReturnOneExisting() throws Exception {
        Fixture fixture = fixture();
        String slot = "slot-concurrent-active";
        AgentActionProposal first = proposal(
                "action-concurrent-slot-1",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                Map.of("domain", "nurl.ink"),
                "Disable",
                "High risk",
                Map.of("enabled", false),
                Map.of("score", 90),
                "idem-concurrent-slot-1",
                slot,
                "batch-concurrent-slot-1"
        );
        AgentActionProposal second = proposal(
                "action-concurrent-slot-2",
                "campaign-agent",
                PAUSE_PLACEMENT,
                "gid-2",
                Map.of("campaignId", "campaign-2"),
                "Pause",
                "Related risk",
                Map.of("paused", true),
                Map.of("score", 70),
                "idem-concurrent-slot-2",
                slot,
                "batch-concurrent-slot-2"
        );

        List<AgentActionProposalResult> results = concurrentlyPropose(fixture, first, second);

        assertConcurrentDeduplication(fixture, results);
        assertThat(fixture.repository().findActiveBySlotKey(slot)).isPresent();
    }

    @Test
    void repositoryDoesNotModifyProposalMaps() {
        Fixture fixture = fixture();
        Map<String, Object> targetRef = new LinkedHashMap<>();
        targetRef.put("domain", "nurl.ink");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", false);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("score", 88);
        AgentActionProposal proposal = proposal(
                "action-immutable",
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                targetRef,
                "Immutable",
                "No mutation",
                payload,
                evidence,
                "idem-immutable",
                "slot-immutable",
                "batch-immutable"
        );
        Map<String, Object> expectedTarget = Map.copyOf(targetRef);
        Map<String, Object> expectedPayload = Map.copyOf(payload);
        Map<String, Object> expectedEvidence = Map.copyOf(evidence);

        fixture.repository().propose(proposal, fixture.codec(), NOW, EXPIRE_TIME);

        assertThat(targetRef).isEqualTo(expectedTarget);
        assertThat(payload).isEqualTo(expectedPayload);
        assertThat(evidence).isEqualTo(expectedEvidence);
        assertThat(proposal.targetRef()).isEqualTo(expectedTarget);
        assertThat(proposal.payload()).isEqualTo(expectedPayload);
        assertThat(proposal.evidence()).isEqualTo(expectedEvidence);
    }

    private void insertForPage(
            Fixture fixture,
            String actionId,
            String agentType,
            AgentActionType actionType,
            String gid,
            AgentActionStatus status,
            LocalDateTime updateTime
    ) {
        AgentActionProposal proposal = proposal(
                actionId,
                agentType,
                actionType,
                gid,
                Map.of("target", actionId),
                actionId,
                "summary",
                Map.of("action", actionId),
                Map.of("score", 50),
                "idem-" + actionId,
                "slot-" + actionId,
                "batch-" + actionId
        );
        fixture.repository().propose(proposal, fixture.codec(), NOW, EXPIRE_TIME);
        fixture.jdbcTemplate().update(
                "update t_agent_pending_action set status = ?, update_time = ? where action_id = ?",
                status.name(),
                Timestamp.valueOf(updateTime),
                actionId
        );
    }

    private List<AgentActionProposalResult> concurrentlyPropose(
            Fixture fixture,
            AgentActionProposal first,
            AgentActionProposal second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<AgentActionProposalResult> firstFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return fixture.repository().propose(first, fixture.codec(), NOW, EXPIRE_TIME);
        });
        Future<AgentActionProposalResult> secondFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            return fixture.repository().propose(second, fixture.codec(), NOW, EXPIRE_TIME);
        });
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        try {
            return List.of(
                    firstFuture.get(10, TimeUnit.SECONDS),
                    secondFuture.get(10, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertConcurrentDeduplication(
            Fixture fixture,
            List<AgentActionProposalResult> results
    ) {
        assertThat(results).extracting(AgentActionProposalResult::created)
                .containsExactlyInAnyOrder(true, false);
        assertThat(results.stream().map(result -> result.action().actionId()).distinct())
                .hasSize(1);
        assertThat(countActions(fixture.jdbcTemplate())).isEqualTo(1);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(AgentActionException.class)
                .hasMessage("Agent action proposal is invalid")
                .extracting(throwable -> ((AgentActionException) throwable).code())
                .isEqualTo("ACTION_PAYLOAD_INVALID");
    }

    private AgentActionProposal withIdentifiers(
            AgentActionProposal source,
            String actionId,
            String agentType,
            AgentActionType actionType,
            String idempotencyKey,
            String activeSlotKey
    ) {
        return new AgentActionProposal(
                actionId,
                agentType,
                actionType,
                source.payloadVersion(),
                source.authorizationScope(),
                source.ownerUsername(),
                source.gid(),
                source.targetType(),
                source.targetKey(),
                source.targetRef(),
                source.title(),
                source.summary(),
                source.payload(),
                source.evidence(),
                idempotencyKey,
                activeSlotKey,
                source.proposedBy(),
                source.traceId(),
                source.eventId(),
                source.batchId(),
                source.sessionId()
        );
    }

    private AgentActionProposal withAuthorizationScope(
            AgentActionProposal source,
            AgentActionAuthorizationScope authorizationScope,
            String ownerUsername,
            String gid
    ) {
        return new AgentActionProposal(
                source.actionId(),
                source.agentType(),
                source.actionType(),
                source.payloadVersion(),
                authorizationScope,
                ownerUsername,
                gid,
                source.targetType(),
                source.targetKey(),
                source.targetRef(),
                source.title(),
                source.summary(),
                source.payload(),
                source.evidence(),
                source.idempotencyKey(),
                source.activeSlotKey(),
                source.proposedBy(),
                source.traceId(),
                source.eventId(),
                source.batchId(),
                source.sessionId()
        );
    }

    private AgentActionProposal proposal(
            String actionId,
            String idempotencyKey,
            String activeSlotKey,
            Map<String, Object> payload
    ) {
        return proposal(
                actionId,
                "security-risk",
                DISABLE_SHORT_LINK,
                "gid-1",
                Map.of("domain", "nurl.ink", "shortUri", actionId),
                "Action " + actionId,
                "Summary " + actionId,
                payload,
                Map.of("score", 90),
                idempotencyKey,
                activeSlotKey,
                "batch-" + actionId
        );
    }

    private AgentActionProposal proposal(
            String actionId,
            String agentType,
            AgentActionType actionType,
            String gid,
            Map<String, Object> targetRef,
            String title,
            String summary,
            Map<String, Object> payload,
            Map<String, Object> evidence,
            String idempotencyKey,
            String activeSlotKey,
            String batchId
    ) {
        return new AgentActionProposal(
                actionId,
                agentType,
                actionType,
                1,
                AgentActionAuthorizationScope.GID,
                "owner-user",
                gid,
                "SHORT_LINK",
                "nurl.ink/" + actionId,
                targetRef,
                title,
                summary,
                payload,
                evidence,
                idempotencyKey,
                activeSlotKey,
                "risk-analysis-worker",
                "trace-" + actionId,
                "event-" + actionId,
                batchId,
                "session-" + actionId
        );
    }

    private Fixture fixture() {
        DataSource dataSource = dataSource();
        populate(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return new Fixture(
                jdbcTemplate,
                new JdbcAgentPendingActionRepository(jdbcTemplate),
                codec()
        );
    }

    private DataSource dataSource() {
        String databaseName = "agent_pending_action_repository_"
                + UUID.randomUUID().toString().replace("-", "");
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "sa",
                ""
        );
    }

    private void populate(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
    }

    private AgentActionPayloadCodec codec() {
        return new AgentActionPayloadCodec(new ObjectMapper());
    }

    private long countActions(JdbcTemplate jdbcTemplate) {
        Long count = jdbcTemplate.queryForObject(
                "select count(1) from t_agent_pending_action",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static String rawSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent proposal");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent proposal was interrupted", ex);
        }
    }

    private record Fixture(
            JdbcTemplate jdbcTemplate,
            JdbcAgentPendingActionRepository repository,
            AgentActionPayloadCodec codec
    ) {
    }

    private static final class CountingJdbcTemplate extends JdbcTemplate {

        private int insertAttempts;

        private CountingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            if (sql.stripLeading().toLowerCase(Locale.ROOT)
                    .startsWith("insert into t_agent_pending_action")) {
                insertAttempts++;
            }
            return super.update(sql, args);
        }

        private int insertAttempts() {
            return insertAttempts;
        }

        private void resetInsertAttempts() {
            insertAttempts = 0;
        }
    }

    private static final class AlwaysDuplicateWithoutRowsJdbcTemplate extends JdbcTemplate {

        private final List<String> querySql = new ArrayList<>();

        private int insertAttempts;

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            insertAttempts++;
            throw new DuplicateKeyException("duplicate-key");
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
                throws DataAccessException {
            querySql.add(sql);
            return List.of();
        }

        private int insertAttempts() {
            return insertAttempts;
        }

        private List<String> querySql() {
            return List.copyOf(querySql);
        }
    }

    private static final class InsertWithoutReadJdbcTemplate extends JdbcTemplate {

        @Override
        public int update(String sql, Object... args) throws DataAccessException {
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args)
                throws DataAccessException {
            return List.of();
        }
    }
}
