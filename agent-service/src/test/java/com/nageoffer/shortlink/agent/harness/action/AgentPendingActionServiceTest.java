package com.nageoffer.shortlink.agent.harness.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutor;
import com.nageoffer.shortlink.agent.harness.action.executor.AgentActionExecutorRegistry;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionClaim;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposalResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.repository.JdbcAgentPendingActionRepository;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionAuthorizationService;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadCodec;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadConflictException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionViewEnricher;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class AgentPendingActionServiceTest {

    private static final AgentActionType TYPE = new AgentActionType("risk.disable-short-link");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 11, 10, 0);
    private static final AgentActionActor ACTOR = new AgentActionActor(
            "reviewer", "user-secret", "Real Name", "gid-1"
    );

    @Test
    void proposeValidatesTtlAndUsesTheInjectedClock() {
        Fixture fixture = fixture(List.of());
        AgentActionProposal proposal = proposal();
        AgentPendingAction stored = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        when(fixture.repository().propose(
                eq(proposal), eq(fixture.codec()), eq(NOW), eq(NOW.plusMinutes(5))
        )).thenReturn(new AgentActionProposalResult(stored, true));

        AgentPendingActionView view = fixture.service().propose(proposal, Duration.ofMinutes(5));
        assertThat(view.actionId()).isEqualTo(stored.actionId());
        assertThatThrownBy(() -> fixture.service().propose(proposal, null))
                .isInstanceOfSatisfying(AgentActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACTION_PAYLOAD_INVALID"));
        assertThatThrownBy(() -> fixture.service().propose(proposal, Duration.ZERO))
                .isInstanceOfSatisfying(AgentActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACTION_PAYLOAD_INVALID"));
    }

    @Test
    void proposeReturnsTheUnifiedEnrichedAndFinallySanitizedView() {
        AgentActionViewEnricher enricher = (action, result) -> {
            result.put("message", "client 2001:db8::1 token=secret user=reviewer");
            result.put("ipHash", "hash-secret");
            return result;
        };
        Fixture fixture = fixture(List.of(), List.of(enricher));
        AgentPendingAction stored = action(
                AgentActionStatus.PENDING, 1L, "{\"safe\":true}", "", ""
        );
        when(fixture.repository().propose(
                eq(proposal()), eq(fixture.codec()), eq(NOW), eq(NOW.plusMinutes(5))
        )).thenReturn(new AgentActionProposalResult(stored, true));

        AgentPendingActionView view = fixture.service().propose(
                proposal(), Duration.ofMinutes(5)
        );

        assertThat(view.result())
                .containsEntry("safe", true)
                .containsEntry("message", "client *** token=*** user=***")
                .doesNotContainKey("ipHash");
        assertThat(view.toString()).doesNotContain(
                "payloadJson", "evidenceJson", "executionToken", "token-secret", "user-secret",
                "Real Name", "reviewer", "hash-secret", "2001:db8::1"
        );
    }

    @Test
    void detailAuthorizesBeforeBuildingASanitizedView() {
        Fixture fixture = fixture(List.of());
        AgentPendingAction stored = action(
                AgentActionStatus.EXECUTED,
                3L,
                "{\"message\":\"completed at 12:30:45 from 2001:db8::1\",\"ipHash\":\"secret\"}",
                "",
                ""
        );
        when(fixture.repository().findByActionId("action-1")).thenReturn(Optional.of(stored));

        AgentPendingActionView view = fixture.service().detail("action-1", ACTOR);

        assertThat(view.result().get("message")).isEqualTo("completed at 12:30:45 from ***");
        assertThat(view.result()).doesNotContainKey("ipHash");
        assertThatThrownBy(() -> fixture.service().detail(
                "action-1", new AgentActionActor("reviewer", null, null, "wrong-gid")
        )).isInstanceOfSatisfying(AgentActionException.class,
                ex -> assertThat(ex.code()).isEqualTo("ACTION_SCOPE_FORBIDDEN"));
    }

    @Test
    void executedAndExecutingConfirmationsAreIdempotentAndDoNotCallExecutor() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction executed = action(AgentActionStatus.EXECUTED, 9L, "{\"ok\":true}", "", "");
        AgentPendingAction executing = action(AgentActionStatus.EXECUTING, 4L, "{}", "", "");
        when(fixture.repository().findByActionId("executed")).thenReturn(Optional.of(executed));
        when(fixture.repository().findByActionId("executing")).thenReturn(Optional.of(executing));

        assertThat(fixture.service().confirm("executed", 1L, ACTOR, "note").status())
                .isEqualTo(AgentActionStatus.EXECUTED);
        assertThat(fixture.service().confirm("executing", 4L, ACTOR, "note").status())
                .isEqualTo(AgentActionStatus.EXECUTING);
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void successfulConfirmationUsesClaimTokenAndClaimedVersion() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(
                new AgentActionExecutionResult(Map.of("state", "DONE"))
        );
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction claimedAction = action(AgentActionStatus.EXECUTING, 2L, "{}", "", "");
        AgentPendingAction executed = action(AgentActionStatus.EXECUTED, 3L,
                "{\"state\":\"DONE\"}", "", "");
        when(fixture.repository().findByActionId("action-1"))
                .thenReturn(Optional.of(pending), Optional.of(executed));
        when(fixture.repository().claimForExecution(
                "action-1", 1L, "opaque-token", NOW, Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.of(new AgentActionClaim(claimedAction, "opaque-token", 2L)));
        when(fixture.repository().completeExecution(
                "action-1", "opaque-token", 2L, "{\"state\":\"DONE\"}", NOW
        )).thenReturn(true);

        AgentPendingActionView view = fixture.service().confirm("action-1", 1L, ACTOR, "approved");

        assertThat(view.status()).isEqualTo(AgentActionStatus.EXECUTED);
        verify(executor).execute(claimedAction, new AgentActionExecutionContext(ACTOR, "approved"));
        verify(fixture.repository()).completeExecution(
                "action-1", "opaque-token", 2L, "{\"state\":\"DONE\"}", NOW
        );
    }

    @Test
    void pageRequiresMatchingGidBeforeRepositoryAccessAndAuthorizesEveryRow() {
        Fixture fixture = fixture(List.of());
        assertThatThrownBy(() -> fixture.service().page(
                "gid-1", null, null, null, 1, 20,
                new AgentActionActor("reviewer", null, null, "wrong")
        )).isInstanceOfSatisfying(AgentActionException.class,
                ex -> assertThat(ex.code()).isEqualTo("ACTION_SCOPE_FORBIDDEN"));
        verify(fixture.repository(), never()).page(any(), any(), any(), any(), any(Integer.class), any(Integer.class));

        AgentPendingAction allowed = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        when(fixture.repository().page("gid-1", null, null, null, 1, 20))
                .thenReturn(new AgentActionPage<>(List.of(allowed), 1L, 1, 20));
        assertThat(fixture.service().page("gid-1", null, null, null, 1, 20, ACTOR).records())
                .hasSize(1);
    }

    @Test
    void ownerScopeRequiresTheMatchingNonBlankUsername() {
        Fixture fixture = fixture(List.of());
        AgentPendingAction ownerAction = withScope(
                action(AgentActionStatus.PENDING, 1L, "{}", "", ""),
                AgentActionAuthorizationScope.OWNER,
                "owner-user"
        );
        when(fixture.repository().findByActionId("owner-action"))
                .thenReturn(Optional.of(ownerAction));

        assertThat(fixture.service().detail(
                "owner-action", new AgentActionActor("owner-user", null, null, null)
        ).status()).isEqualTo(AgentActionStatus.PENDING);
        assertThatThrownBy(() -> fixture.service().detail(
                "owner-action", new AgentActionActor("other-user", null, null, "gid-1")
        )).isInstanceOfSatisfying(AgentActionException.class,
                ex -> assertThat(ex.code()).isEqualTo("ACTION_SCOPE_FORBIDDEN"));
        assertThatThrownBy(() -> fixture.service().detail(
                "owner-action", new AgentActionActor(" ", null, null, "gid-1")
        )).isInstanceOfSatisfying(AgentActionException.class,
                ex -> assertThat(ex.code()).isEqualTo("ACTION_SCOPE_FORBIDDEN"));
    }

    @Test
    void rejectValidatesReviewActionAndResolvesSuccessfulCas() {
        Fixture fixture = fixture(List.of());
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction rejected = action(AgentActionStatus.REJECTED, 2L, "{}", "", "");
        when(fixture.repository().findByActionId("action-1"))
                .thenReturn(Optional.of(pending), Optional.of(rejected));
        when(fixture.repository().reject(
                "action-1", 1L, "reviewer", "not approved", "IGNORE", NOW
        )).thenReturn(true);

        assertThat(fixture.service().reject(
                "action-1", 1L, ACTOR, "not approved", "IGNORE"
        ).status()).isEqualTo(AgentActionStatus.REJECTED);
        when(fixture.repository().findByActionId("invalid-review"))
                .thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> fixture.service().reject(
                "invalid-review", 1L, ACTOR, "reason", "DELETE"
        )).isInstanceOfSatisfying(AgentActionException.class,
                ex -> assertThat(ex.code()).isEqualTo("ACTION_REVIEW_ACTION_INVALID"));
    }

    @Test
    void blankReviewActionIsNormalizedToNullForRepositoryCas() {
        Fixture fixture = fixture(List.of());
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction rejected = action(AgentActionStatus.REJECTED, 2L, "{}", "", "");
        when(fixture.repository().findByActionId("blank-review"))
                .thenReturn(Optional.of(pending), Optional.of(rejected));
        when(fixture.repository().reject(
                "blank-review", 1L, "reviewer", "reason", null, NOW
        )).thenReturn(true);

        fixture.service().reject("blank-review", 1L, ACTOR, "reason", "  ");

        verify(fixture.repository()).reject(
                "blank-review", 1L, "reviewer", "reason", null, NOW
        );
    }

    @Test
    void payloadConflictPropagatesUnchanged() {
        Fixture fixture = fixture(List.of());
        AgentActionPayloadConflictException conflict = new AgentActionPayloadConflictException();
        when(fixture.repository().propose(any(), any(), any(), any())).thenThrow(conflict);

        assertThatThrownBy(() -> fixture.service().propose(proposal(), Duration.ofMinutes(5)))
                .isSameAs(conflict);
    }

    @Test
    void missingExecutorAndNonReplaySafeFailedClaimUseStableErrors() {
        Fixture missing = fixture(List.of());
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        when(missing.repository().findByActionId("action-1")).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> missing.service().confirm("action-1", 1L, ACTOR, null))
                .isInstanceOfSatisfying(AgentActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACTION_EXECUTOR_UNAVAILABLE"));

        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(false);
        Fixture nonReplaySafe = fixture(List.of(executor));
        AgentPendingAction failed = action(
                AgentActionStatus.FAILED, 4L, "{}", "ACTION_EXECUTION_FAILED", "failed"
        );
        when(nonReplaySafe.repository().findByActionId("action-1"))
                .thenReturn(Optional.of(failed), Optional.of(failed));
        when(nonReplaySafe.repository().claimForExecution(
                "action-1", 4L, "opaque-token", NOW, Duration.ofSeconds(17), false, "reviewer"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> nonReplaySafe.service().confirm("action-1", 4L, ACTOR, null))
                .isInstanceOfSatisfying(AgentActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACTION_NOT_CONFIRMABLE"));
    }

    @Test
    void claimRaceReturnsExecutedViewOrVersionConflictAccordingToLatestState() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction executed = action(AgentActionStatus.EXECUTED, 3L, "{\"ok\":true}", "", "");
        when(fixture.repository().findByActionId("race-executed"))
                .thenReturn(Optional.of(pending), Optional.of(executed));
        when(fixture.repository().claimForExecution(
                "race-executed", 1L, "opaque-token", NOW, Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.empty());
        assertThat(fixture.service().confirm("race-executed", 1L, ACTOR, null).status())
                .isEqualTo(AgentActionStatus.EXECUTED);

        AgentPendingAction changed = action(AgentActionStatus.PENDING, 2L, "{}", "", "");
        when(fixture.repository().findByActionId("race-version"))
                .thenReturn(Optional.of(pending), Optional.of(changed));
        when(fixture.repository().claimForExecution(
                "race-version", 1L, "opaque-token", NOW, Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.empty());
        assertThatThrownBy(() -> fixture.service().confirm("race-version", 1L, ACTOR, null))
                .isInstanceOfSatisfying(AgentActionException.class,
                        ex -> assertThat(ex.code()).isEqualTo("ACTION_VERSION_CONFLICT"));
    }

    @Test
    void claimMissWithLatestExecutingReturnsViewWithoutCallingExecutor() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction executing = action(AgentActionStatus.EXECUTING, 2L, "{}", "", "");
        when(fixture.repository().findByActionId("race-executing"))
                .thenReturn(Optional.of(pending), Optional.of(executing));
        when(fixture.repository().claimForExecution(
                "race-executing", 1L, "opaque-token", NOW,
                Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.empty());

        assertThat(fixture.service().confirm("race-executing", 1L, ACTOR, null).status())
                .isEqualTo(AgentActionStatus.EXECUTING);
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void rejectedAndExpiredActionsAreNotConfirmable() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        Fixture fixture = fixture(List.of(executor));
        for (AgentActionStatus status : List.of(
                AgentActionStatus.REJECTED,
                AgentActionStatus.EXPIRED
        )) {
            String actionId = status.name().toLowerCase();
            when(fixture.repository().findByActionId(actionId))
                    .thenReturn(Optional.of(action(status, 2L, "{}", "", "")));
            assertThatThrownBy(() -> fixture.service().confirm(actionId, 2L, ACTOR, null))
                    .isInstanceOfSatisfying(AgentActionException.class,
                            ex -> assertThat(ex.code()).isEqualTo("ACTION_NOT_CONFIRMABLE"));
        }
        verify(executor, never()).execute(any(), any());
    }

    @Test
    void executorFailureUsesClaimFencingAndDoesNotLeakTheOriginalMessage() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        when(executor.execute(any(), any())).thenThrow(new IllegalStateException(
                "SQL token=secret user=reviewer at 2001:db8::1 "
                        + "url=https://access-token@example.com/path"
        ));
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = action(AgentActionStatus.PENDING, 1L, "{}", "", "");
        AgentPendingAction claimedAction = action(AgentActionStatus.EXECUTING, 2L, "{}", "", "");
        when(fixture.repository().findByActionId("action-1")).thenReturn(Optional.of(pending));
        when(fixture.repository().claimForExecution(
                "action-1", 1L, "opaque-token", NOW, Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.of(new AgentActionClaim(claimedAction, "opaque-token", 2L)));

        assertThatThrownBy(() -> fixture.service().confirm("action-1", 1L, ACTOR, null))
                .isInstanceOfSatisfying(AgentActionException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("ACTION_EXECUTION_FAILED");
                    assertThat(ex.getMessage())
                            .doesNotContain("SQL", "secret", "reviewer", "2001:db8::1");
                });
        verify(fixture.repository()).failExecution(
                "action-1", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED",
                "SQL token=*** user=*** at *** url=https://***@example.com/path",
                NOW
        );

        AgentPendingAction failed = action(
                AgentActionStatus.FAILED,
                3L,
                "{}",
                "ACTION_EXECUTION_FAILED",
                "SQL token=*** user=*** at *** url=https://***@example.com/path"
        );
        when(fixture.repository().findByActionId("action-1")).thenReturn(Optional.of(failed));
        AgentPendingActionView failedView = fixture.service().detail("action-1", ACTOR);
        assertThat(failedView.failure().get("message"))
                .isEqualTo("SQL token=*** user=*** at *** url=https://***@example.com/path");
        assertThat(failedView.toString()).doesNotContain("access-token");
    }

    @Test
    void completedExecutionViewFailureNeverAttemptsToFailTheExecutedAction() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(AgentActionExecutionResult.empty());
        AgentActionViewEnricher throwingEnricher = (action, result) -> {
            throw new IllegalStateException("enricher token=secret");
        };
        Fixture fixture = fixture(List.of(executor), List.of(throwingEnricher));
        AgentPendingAction pending = withActionId(
                action(AgentActionStatus.PENDING, 1L, "{}", "", ""),
                "complete-view-failure"
        );
        AgentPendingAction claimed = withActionId(
                action(AgentActionStatus.EXECUTING, 2L, "{}", "", ""),
                "complete-view-failure"
        );
        AgentPendingAction executed = withActionId(
                action(AgentActionStatus.EXECUTED, 3L, "{}", "", ""),
                "complete-view-failure"
        );
        when(fixture.repository().findByActionId("complete-view-failure"))
                .thenReturn(Optional.of(pending), Optional.of(executed));
        when(fixture.repository().claimForExecution(
                "complete-view-failure", 1L, "opaque-token", NOW,
                Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.of(new AgentActionClaim(claimed, "opaque-token", 2L)));
        when(fixture.repository().completeExecution(
                "complete-view-failure", "opaque-token", 2L, "{}", NOW
        )).thenReturn(true);

        assertThatThrownBy(() -> fixture.service().confirm(
                "complete-view-failure", 1L, ACTOR, null
        )).isInstanceOfSatisfying(AgentActionException.class, ex -> {
            assertThat(ex.code()).isEqualTo("ACTION_EXECUTION_FAILED");
            assertThat(ex.getMessage()).doesNotContain("secret");
        });
        verify(fixture.repository(), never()).failExecution(
                "complete-view-failure", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED", "Agent action execution failed", NOW
        );
    }

    @Test
    void completeFalseAndLostFailureFenceReturnStableErrorWithoutRetryingExecution() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(AgentActionExecutionResult.empty());
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = withActionId(
                action(AgentActionStatus.PENDING, 1L, "{}", "", ""),
                "complete-false"
        );
        AgentPendingAction claimed = withActionId(
                action(AgentActionStatus.EXECUTING, 2L, "{}", "", ""),
                "complete-false"
        );
        when(fixture.repository().findByActionId("complete-false"))
                .thenReturn(Optional.of(pending));
        when(fixture.repository().claimForExecution(
                "complete-false", 1L, "opaque-token", NOW,
                Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.of(new AgentActionClaim(claimed, "opaque-token", 2L)));
        when(fixture.repository().completeExecution(
                "complete-false", "opaque-token", 2L, "{}", NOW
        )).thenReturn(false);
        when(fixture.repository().failExecution(
                "complete-false", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED", "Agent action execution failed", NOW
        )).thenReturn(false);

        assertThatThrownBy(() -> fixture.service().confirm(
                "complete-false", 1L, ACTOR, null
        )).isInstanceOfSatisfying(AgentActionException.class, ex -> {
            assertThat(ex.code()).isEqualTo("ACTION_EXECUTION_FAILED");
            assertThat(ex).hasMessage("Agent action execution failed");
        });
        verify(executor, times(1)).execute(any(), any());
        verify(fixture.repository(), times(1)).completeExecution(
                "complete-false", "opaque-token", 2L, "{}", NOW
        );
        verify(fixture.repository(), times(1)).failExecution(
                "complete-false", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED", "Agent action execution failed", NOW
        );
    }

    @Test
    void completeAndFailureFenceExceptionsStillReturnOnlyStableExecutionError() {
        AgentActionExecutor executor = mock(AgentActionExecutor.class);
        when(executor.actionType()).thenReturn(TYPE);
        when(executor.replaySafe()).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(AgentActionExecutionResult.empty());
        Fixture fixture = fixture(List.of(executor));
        AgentPendingAction pending = withActionId(
                action(AgentActionStatus.PENDING, 1L, "{}", "", ""),
                "complete-throws"
        );
        AgentPendingAction claimed = withActionId(
                action(AgentActionStatus.EXECUTING, 2L, "{}", "", ""),
                "complete-throws"
        );
        when(fixture.repository().findByActionId("complete-throws"))
                .thenReturn(Optional.of(pending));
        when(fixture.repository().claimForExecution(
                "complete-throws", 1L, "opaque-token", NOW,
                Duration.ofSeconds(17), true, "reviewer"
        )).thenReturn(Optional.of(new AgentActionClaim(claimed, "opaque-token", 2L)));
        when(fixture.repository().completeExecution(
                "complete-throws", "opaque-token", 2L, "{}", NOW
        )).thenThrow(new IllegalStateException("SQL token=complete-secret"));
        when(fixture.repository().failExecution(
                "complete-throws", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED", "SQL token=***", NOW
        )).thenThrow(new IllegalStateException("SQL token=failure-secret"));

        assertThatThrownBy(() -> fixture.service().confirm(
                "complete-throws", 1L, ACTOR, null
        )).isInstanceOfSatisfying(AgentActionException.class, ex -> {
            assertThat(ex.code()).isEqualTo("ACTION_EXECUTION_FAILED");
            assertThat(ex).hasMessage("Agent action execution failed");
            assertThat(ex.toString()).doesNotContain("complete-secret", "failure-secret", "SQL");
        });
        verify(executor, times(1)).execute(any(), any());
        verify(fixture.repository(), times(1)).completeExecution(
                "complete-throws", "opaque-token", 2L, "{}", NOW
        );
        verify(fixture.repository(), times(1)).failExecution(
                "complete-throws", "opaque-token", 2L,
                "ACTION_EXECUTION_FAILED", "SQL token=***", NOW
        );
    }

    @Test
    void enrichersRunInOrderOnDefensiveMapsBeforeFinalSanitization() {
        Map<String, Object> firstInput = new java.util.LinkedHashMap<>();
        AgentActionViewEnricher first = (action, result) -> {
            firstInput.putAll(result);
            result.put("step", "first");
            result.put("token", "secret-token");
            return result;
        };
        AgentActionViewEnricher second = (action, result) -> {
            assertThat(result).containsEntry("step", "first");
            result.put("message", "completed at 12:30:45 via ::1");
            return result;
        };
        Fixture fixture = fixture(List.of(), List.of(first, second));
        AgentPendingAction stored = action(
                AgentActionStatus.EXECUTED, 3L, "{\"base\":true}", "", ""
        );
        when(fixture.repository().findByActionId("executed")).thenReturn(Optional.of(stored));

        AgentPendingActionView view = fixture.service().detail("executed", ACTOR);

        assertThat(firstInput).containsExactly(Map.entry("base", true));
        assertThat(view.result())
                .containsEntry("base", true)
                .containsEntry("step", "first")
                .containsEntry("message", "completed at 12:30:45 via ***")
                .doesNotContainKey("token");
    }

    @Test
    void malformedStoredJsonFailsEveryViewEntryWithStablePayloadError() {
        Fixture fixture = fixture(List.of());
        AgentPendingAction malformed = malformedResultAction();
        when(fixture.repository().findByActionId("malformed")).thenReturn(Optional.of(malformed));

        assertThatThrownBy(() -> fixture.service().detail("malformed", ACTOR))
                .isInstanceOfSatisfying(AgentActionException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("ACTION_PAYLOAD_INVALID");
                    assertThat(ex.getMessage()).doesNotContain("stored-secret");
                });
    }

    @Test
    void executorRunsAfterRepositoryClaimTransactionHasEnded() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service_tx_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(dataSource);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionTestConfiguration.class);
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource)
            );
            context.registerBean(
                    "pendingActionRepository",
                    JdbcAgentPendingActionRepository.class,
                    () -> new JdbcAgentPendingActionRepository(new JdbcTemplate(dataSource))
            );
            context.refresh();
            JdbcAgentPendingActionRepository repository =
                    context.getBean(JdbcAgentPendingActionRepository.class);
            AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
            repository.propose(proposal(), codec, NOW, NOW.plusHours(1));
            AgentActionExecutor executor = new AgentActionExecutor() {
                @Override
                public AgentActionType actionType() {
                    return TYPE;
                }

                @Override
                public boolean replaySafe() {
                    return true;
                }

                @Override
                public AgentActionExecutionResult execute(
                        AgentPendingAction action,
                        AgentActionExecutionContext executionContext
                ) {
                    transactionActive.set(
                            TransactionSynchronizationManager.isActualTransactionActive()
                    );
                    return AgentActionExecutionResult.empty();
                }
            };
            AgentProperties properties = new AgentProperties();
            properties.getAction().setExecutionLeaseSeconds(17);
            AgentPendingActionService service = new AgentPendingActionService(
                    repository,
                    codec,
                    new AgentActionExecutorRegistry(List.of(executor)),
                    new AgentActionAuthorizationService(),
                    properties,
                    List.of(),
                    Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE),
                    () -> "opaque-token"
            );

            assertThat(service.confirm("action-1", 1L, ACTOR, null).status())
                    .isEqualTo(AgentActionStatus.EXECUTED);
            assertThat(transactionActive).isFalse();
        }
    }

    private Fixture fixture(List<AgentActionExecutor> executors) {
        return fixture(executors, List.of());
    }

    private Fixture fixture(
            List<AgentActionExecutor> executors,
            List<AgentActionViewEnricher> enrichers
    ) {
        JdbcAgentPendingActionRepository repository = mock(JdbcAgentPendingActionRepository.class);
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
        AgentProperties properties = new AgentProperties();
        properties.getAction().setExecutionLeaseSeconds(17);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        AgentPendingActionService service = new AgentPendingActionService(
                repository,
                codec,
                new AgentActionExecutorRegistry(executors),
                new AgentActionAuthorizationService(),
                properties,
                enrichers,
                clock,
                () -> "opaque-token"
        );
        return new Fixture(repository, codec, service);
    }

    private AgentPendingAction malformedResultAction() {
        AgentPendingAction source = action(AgentActionStatus.EXECUTED, 3L, "{}", "", "");
        return new AgentPendingAction(
                source.id(), "malformed", source.agentType(), source.actionType(),
                source.payloadVersion(), source.authorizationScope(), source.ownerUsername(),
                source.gid(), source.targetType(), source.targetKey(), source.targetRefJson(),
                source.title(), source.summary(), source.payloadJson(), source.payloadHash(),
                source.evidenceJson(), source.idempotencyKey(), source.activeSlotKey(),
                source.status(), source.expireTime(), source.version(), source.executionToken(),
                source.executionLeaseUntil(), source.attemptCount(), "stored-secret-not-json",
                source.failureCode(), source.failureMessage(), source.proposedBy(),
                source.confirmedBy(), source.confirmedTime(), source.rejectedBy(),
                source.rejectedTime(), source.rejectionReason(), source.rejectionReviewAction(),
                source.traceId(), source.eventId(), source.batchId(), source.sessionId(),
                source.createTime(), source.updateTime()
        );
    }

    private AgentPendingAction withScope(
            AgentPendingAction source,
            AgentActionAuthorizationScope scope,
            String ownerUsername
    ) {
        return new AgentPendingAction(
                source.id(), "owner-action", source.agentType(), source.actionType(),
                source.payloadVersion(), scope, ownerUsername, source.gid(), source.targetType(),
                source.targetKey(), source.targetRefJson(), source.title(), source.summary(),
                source.payloadJson(), source.payloadHash(), source.evidenceJson(),
                source.idempotencyKey(), source.activeSlotKey(), source.status(), source.expireTime(),
                source.version(), source.executionToken(), source.executionLeaseUntil(),
                source.attemptCount(), source.resultJson(), source.failureCode(),
                source.failureMessage(), source.proposedBy(), source.confirmedBy(),
                source.confirmedTime(), source.rejectedBy(), source.rejectedTime(),
                source.rejectionReason(), source.rejectionReviewAction(), source.traceId(),
                source.eventId(), source.batchId(), source.sessionId(), source.createTime(),
                source.updateTime()
        );
    }

    private AgentPendingAction withActionId(AgentPendingAction source, String actionId) {
        return new AgentPendingAction(
                source.id(), actionId, source.agentType(), source.actionType(),
                source.payloadVersion(), source.authorizationScope(), source.ownerUsername(),
                source.gid(), source.targetType(), source.targetKey(), source.targetRefJson(),
                source.title(), source.summary(), source.payloadJson(), source.payloadHash(),
                source.evidenceJson(), source.idempotencyKey(), source.activeSlotKey(),
                source.status(), source.expireTime(), source.version(), source.executionToken(),
                source.executionLeaseUntil(), source.attemptCount(), source.resultJson(),
                source.failureCode(), source.failureMessage(), source.proposedBy(),
                source.confirmedBy(), source.confirmedTime(), source.rejectedBy(),
                source.rejectedTime(), source.rejectionReason(), source.rejectionReviewAction(),
                source.traceId(), source.eventId(), source.batchId(), source.sessionId(),
                source.createTime(), source.updateTime()
        );
    }

    private AgentActionProposal proposal() {
        return new AgentActionProposal(
                "action-1", "security-risk", TYPE, 1, AgentActionAuthorizationScope.GID,
                "owner", "gid-1", "SHORT_LINK", "nurl.ink/abc",
                Map.of("domain", "nurl.ink"), "Disable", "Summary", Map.of("enabled", false),
                Map.of("riskScore", 91), "idem-1", "slot-1", "worker",
                "trace-1", "event-1", "batch-1", "session-1"
        );
    }

    private AgentPendingAction action(
            AgentActionStatus status,
            long version,
            String resultJson,
            String failureCode,
            String failureMessage
    ) {
        String actionId = status == AgentActionStatus.EXECUTED ? "executed"
                : status == AgentActionStatus.EXECUTING && version == 4L ? "executing" : "action-1";
        return new AgentPendingAction(
                1L, actionId, "security-risk", TYPE, 1, AgentActionAuthorizationScope.GID,
                "owner", "gid-1", "SHORT_LINK", "nurl.ink/abc",
                "{\"domain\":\"nurl.ink\",\"ip\":\"2001:db8::1\"}", "Disable", "Summary",
                "{\"secret\":\"payload\"}", "hash", "{\"riskScore\":91}", "idem", "slot",
                status, NOW.plusHours(1), version,
                status == AgentActionStatus.EXECUTING ? "token-secret" : "", null, 1,
                resultJson, failureCode, failureMessage, "worker", "reviewer", NOW,
                status == AgentActionStatus.REJECTED ? "reviewer" : "", null,
                status == AgentActionStatus.REJECTED ? "not approved" : "", "IGNORE",
                "trace", "event", "batch", "session", NOW.minusMinutes(1), NOW
        );
    }

    private record Fixture(
            JdbcAgentPendingActionRepository repository,
            AgentActionPayloadCodec codec,
            AgentPendingActionService service
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionTestConfiguration {
    }
}
