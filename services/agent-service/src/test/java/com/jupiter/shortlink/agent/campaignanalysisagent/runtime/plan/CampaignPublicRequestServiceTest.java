package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest.Operation;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignPublicRequestServiceTest {
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final String SESSION = "public-session";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void explicitAndNaturalProgressAreReadOnlyAndKeepExactRequestIdentity() {
        Fixture f = fixture();
        AgentRunResult first = f.service.run(fresh("first", selected("g1", "分析 9 月 1 日至 7 日访问趋势"), null));
        WorkRef reference = first.continuation().workRef();
        var frozen = f.requests.read(reference);
        var turn = f.turns.require(PRINCIPAL, SESSION, reference);
        f.scheduled.clear();
        int priorChecks = f.authorityChecks.get();

        AgentRunResult explicit = f.service.run(command(Operation.PROGRESS, first, "查看进度"));
        AgentRunResult natural = f.service.run(fresh("read-command", selected("g1", "查看结果？"), reference.runId()));
        AgentRunResult direct = f.service.progress(PRINCIPAL, SESSION, reference);

        assertThat(explicit.continuation()).isEqualTo(first.continuation());
        assertThat(natural.continuation()).isEqualTo(first.continuation());
        assertThat(direct.continuation()).isEqualTo(first.continuation());
        assertThat(f.requests.read(reference)).isEqualTo(frozen);
        assertThat(f.turns.require(PRINCIPAL, SESSION, reference)).isEqualTo(turn);
        assertThat(f.rows("campaign_public_request")).isEqualTo(1);
        assertThat(f.rows("campaign_conversation_turn")).isEqualTo(1);
        assertThat(f.scheduled).isEmpty();
        assertThat(f.woken).isEmpty();
        assertThat(f.authorityChecks.get()).isGreaterThanOrEqualTo(priorChecks + 3);
        f.assertNoExecution();
    }

    @Test
    void explicitAndNaturalContinueWakeOriginalWorkWithoutNewModelOrRequest() {
        Fixture f = fixture();
        AgentRunResult first = f.service.run(fresh("first", selected("g1", "分析最近七天访问"), null));
        WorkRef reference = first.continuation().workRef();
        var original = f.requests.read(reference);
        f.scheduled.clear();

        AgentRunResult explicit = f.service.run(command(Operation.CONTINUE, first, "继续"));
        AgentRunResult natural = f.service.run(fresh("continue-command", selected("g1", "继续分析。"), reference.runId()));

        assertThat(explicit.continuation()).isEqualTo(first.continuation());
        assertThat(natural.continuation()).isEqualTo(first.continuation());
        assertThat(f.woken).containsExactly(reference, reference);
        assertThat(f.scheduled).isEmpty();
        assertThat(f.requests.read(reference)).isEqualTo(original);
        assertThat(f.rows("campaign_public_request")).isEqualTo(1);
        assertThat(f.rows("campaign_conversation_turn")).isEqualTo(1);
        f.assertNoExecution();
    }

    @Test
    void changedPeriodAndScopeCreateNewLinkedRunsRetainAllThreeTurnsAndRejectRevokedAccess() {
        Fixture f = fixture();
        String originalQuestion = selected("g1", "分析 9 月 1 日至 7 日访问趋势并提供优化建议");
        String secondQuestion = selected("g1", "再比较 9 月 8 日至 14 日，保留原期间作为基期");
        String thirdQuestion = selected("g2", "继续");
        AgentRunResult first = f.service.run(fresh("first", originalQuestion, null));
        var firstRequest = f.requests.read(first.continuation().workRef());
        AgentRunResult second = f.service.run(fresh("second", secondQuestion, first.continuation().runId()));
        AgentRunResult third = f.service.run(fresh("third", thirdQuestion, second.continuation().runId()));

        assertThat(second.continuation().runId()).isNotEqualTo(first.continuation().runId());
        assertThat(third.continuation().runId()).isNotIn(first.continuation().runId(), second.continuation().runId());
        assertThat(f.scheduled).containsExactly(first.continuation().workRef(), second.continuation().workRef(), third.continuation().workRef());
        assertThat(f.woken).isEmpty();
        assertThat(f.requests.read(first.continuation().workRef())).isEqualTo(firstRequest);
        assertThat(f.requests.read(second.continuation().workRef()).question()).contains(originalQuestion, secondQuestion);
        assertThat(f.requests.read(third.continuation().workRef()).question()).contains(originalQuestion, secondQuestion, thirdQuestion);
        assertThat(f.turns.require(PRINCIPAL, SESSION, second.continuation().workRef()).previousRunId()).isEqualTo(first.continuation().runId());
        assertThat(f.turns.require(PRINCIPAL, SESSION, third.continuation().workRef()).previousRunId()).isEqualTo(second.continuation().runId());
        assertThat(f.turns.require(PRINCIPAL, SESSION, third.continuation().workRef()).originalQuestion()).isEqualTo(thirdQuestion);

        f.scheduled.clear();
        f.authorized.set(false);
        assertThatThrownBy(() -> f.service.progress(PRINCIPAL, SESSION, third.continuation().workRef()))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.service.run(command(Operation.CONTINUE, third, "继续")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.service.run(fresh("fourth", "再加入设备分析", third.continuation().runId())))
                .isInstanceOf(SecurityException.class);
        assertThat(f.rows("campaign_public_request")).isEqualTo(3);
        assertThat(f.rows("campaign_conversation_turn")).isEqualTo(3);
        assertThat(f.scheduled).isEmpty();
        assertThat(f.woken).isEmpty();
        f.assertNoExecution();
    }

    @Test
    void cancellationDelegatesOnceToExistingRunWithoutWakingOrExecutingWork() {
        Fixture f = fixture();
        AgentRunResult first = f.service.run(fresh("first", selected("g1", "分析最近七天访问"), null));
        WorkRef reference = first.continuation().workRef();
        var registered = f.requests.read(reference);
        String attempt = f.requests.begin(registered, "a".repeat(64));
        f.requests.complete(registered, attempt, "{}");
        f.requests.callbackExited(registered, attempt);
        f.requests.bind(registered, new WorkRef(reference.runId(), "intake-existing"));
        AgentRunResult cancelled = new AgentRunResult(SESSION, "cancelled-trace", "分析已停止", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), null, first.continuation(),
                new AgentRunResult.Progress(reference.runId(), "existing-plan", 1, ExecutionStatus.CANCELLED, NextAction.none()));
        when(f.delivery.read(PRINCIPAL, SESSION, reference)).thenReturn(Optional.of(cancelled));
        var cancellation = mock(CampaignPublicRequestService.Cancellation.class);
        f.service.installCancellation(cancellation);
        f.scheduled.clear();

        AgentRunResult result = f.service.run(command(Operation.CANCEL, first, null));

        assertThat(result).isEqualTo(cancelled);
        verify(cancellation).cancel(PRINCIPAL, SESSION, reference);
        verifyNoMoreInteractions(cancellation);
        assertThat(f.scheduled).isEmpty();
        assertThat(f.woken).isEmpty();
        assertThat(f.rows("campaign_public_request")).isEqualTo(1);
        assertThat(f.rows("campaign_conversation_turn")).isEqualTo(1);
        verifyNoInteractions(f.gateway, f.recovery, f.model, f.planner);
        assertThat(f.intake.snapshot().activeAdvances()).isZero();
    }

    private static AgentRunRequest fresh(String key, String question, String previous) {
        return new AgentRunRequest(SESSION, "campaign-analysis", PRINCIPAL.username(), question, PRINCIPAL, key,
                Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2), Operation.NEW, null, previous);
    }

    private static AgentRunRequest command(Operation operation, AgentRunResult previous, String message) {
        return new AgentRunRequest(SESSION, "campaign-analysis", PRINCIPAL.username(), message, PRINCIPAL, "first",
                Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2), operation, previous.continuation(), null);
    }

    private static String selected(String gid, String question) {
        return "分析范围：分组「测试分组」；gid=" + gid + ";\n" + question;
    }

    private static Fixture fixture() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:public_service_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("sql/migration/V20260923__campaign_conversation_session_owner.sql"),
                new ClassPathResource("sql/migration/V20260923_3__campaign_conversation_turn.sql"),
                new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var sessions = new JdbcCampaignConversationSessionOwner(jdbc, transactions, CLOCK);
        var turns = new JdbcCampaignConversationTurnStore(jdbc, transactions, sessions, CLOCK);
        var requests = new CampaignPublicRequestStore(jdbc, transactions, CLOCK);
        var authorized = new AtomicBoolean(true);
        var authorityChecks = new AtomicInteger();
        var authority = mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any())).thenAnswer(call -> {
            authorityChecks.incrementAndGet();
            assertThat((AgentPrincipal) call.getArgument(0)).isEqualTo(PRINCIPAL);
            if (!authorized.get()) throw new SecurityException("ACCOUNT_REVOKED");
            return PRINCIPAL;
        });
        var principals = new CampaignCurrentPrincipalResolver(authority, sessions);
        var runs = new JdbcCampaignRunStore(jdbc, transactions, CLOCK);
        var intakeStore = new JdbcCampaignRunIntakeStore(jdbc, transactions, CLOCK, runs, 1024 * 1024);
        var gateway = mock(ShortLinkBusinessGateway.class);
        var recovery = mock(CampaignRecoveryStore.class);
        var intake = new CampaignRunIntake(intakeStore, runs, recovery,
                new StatisticsSubmissionReconciler(runs, gateway), null, null, List.of(), principals,
                new ProcessCapacityExecutor.Limits(1, 1, 1, 1), work -> {
                    throw new AssertionError("Public request registration/read must not execute background work");
                });
        var model = mock(ChatModel.class);
        var planner = mock(CampaignPublicRequestService.PlannerInput.class);
        var delivery = mock(CampaignPublicRequestService.Delivery.class);
        List<WorkRef> scheduled = new ArrayList<>();
        List<WorkRef> woken = new ArrayList<>();
        var service = new CampaignPublicRequestService(requests, turns,
                principals, intake, model, planner, "business", "1",
                scheduled::add, delivery, CLOCK, Duration.ofHours(1));
        service.installWake(woken::add);
        return new Fixture(jdbc, requests, turns, service, intake, gateway, recovery, model, planner, delivery,
                scheduled, woken, authorized, authorityChecks);
    }

    private record Fixture(JdbcTemplate jdbc, CampaignPublicRequestStore requests, JdbcCampaignConversationTurnStore turns,
            CampaignPublicRequestService service, CampaignRunIntake intake, ShortLinkBusinessGateway gateway,
            CampaignRecoveryStore recovery, ChatModel model,
            CampaignPublicRequestService.PlannerInput planner, CampaignPublicRequestService.Delivery delivery,
            List<WorkRef> scheduled, List<WorkRef> woken, AtomicBoolean authorized, AtomicInteger authorityChecks) {
        int rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void assertNoExecution() {
            verifyNoInteractions(gateway, recovery, model, planner, delivery);
            assertThat(intake.snapshot().activeAdvances()).isZero();
            assertThat(intake.snapshot().queued()).isZero();
        }
    }
}
