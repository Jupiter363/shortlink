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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignDueWorkStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextAction;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.NextActionKind;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
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

    @Test
    void preRunCancellationIsTerminalAndReadOnlyAcrossPreparationPhases() {
        for (String phase : List.of("PREPARED", "DISPATCHING", "NEEDS_INPUT")) {
            Fixture f = fixture();
            var first = f.service.run(fresh("first", selected("g1", "分析最近七天访问"), null));
            var reference = first.continuation().workRef();
            var original = f.requests.read(reference);
            String attempt = null;
            if (!phase.equals("PREPARED")) attempt = f.requests.begin(original, "a".repeat(64));
            if (phase.equals("NEEDS_INPUT")) {
                f.requests.complete(original, attempt, "{}");
                f.requests.callbackExited(original, attempt);
                f.requests.needsInput(original, "REQUIREMENTS_NEED_INPUT");
            }
            f.scheduled.clear();
            var stopped = f.service.run(command(Operation.CANCEL, first, null));
            assertThat(stopped.progress().executionStatus()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(stopped.progress().nextAction().kind()).isEqualTo(NextActionKind.NONE);
            assertThat(stopped.continuation()).isEqualTo(first.continuation());
            var saved = f.requests.read(reference);
            assertThat(saved.cancelled()).isTrue();
            assertThat(saved.state()).isEqualTo(phase);
            assertThat(saved.callbackActive()).isEqualTo(phase.equals("DISPATCHING"));
            assertThat(f.service.run(command(Operation.CONTINUE, first, "继续")).progress().executionStatus())
                    .isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(f.service.run(fresh("continue-command", selected("g1", "继续"), reference.runId()))
                    .progress().executionStatus()).isEqualTo(ExecutionStatus.CANCELLED);
            assertThat(f.service.progress(PRINCIPAL, SESSION, reference).progress().executionStatus())
                    .isEqualTo(ExecutionStatus.CANCELLED);
            f.service.run(command(Operation.CANCEL, first, null));
            assertThat(f.requests.read(reference)).isEqualTo(saved);
            assertThat(f.scheduled).isEmpty(); assertThat(f.woken).isEmpty();
            assertThat(f.rows("campaign_run_ledger")).isZero();
            f.assertNoExecution();
            f.authorized.set(false);
            assertThatThrownBy(() -> f.service.run(command(Operation.CANCEL, first, null))).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void blockedPreparationCanContinueOnlyItsOriginalUnissuedRequest() {
        Fixture f=fixture();
        var first=f.service.run(fresh("first",selected("g1","分析最近七天访问"),null));
        WorkRef reference=first.continuation().workRef();
        var original=f.requests.read(reference);
        f.block(reference);f.scheduled.clear();
        var stopped=f.due.entry(reference);

        var progress=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(progress.continuation()).isEqualTo(first.continuation());
        assertThat(progress.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(progress.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.CONTINUE,"PREPARATION_REQUIRES_ATTENTION",List.of()));
        assertThat(progress.answer()).contains("尚未发出模型调用");
        assertThat(f.requests.read(reference)).isEqualTo(original);
        assertThat(f.due.entry(reference)).isEqualTo(stopped);
        assertThat(f.woken).isEmpty();

        var resumed=f.service.run(command(Operation.CONTINUE,first,"继续"));
        assertThat(resumed.continuation()).isEqualTo(first.continuation());
        assertThat(f.woken).containsExactly(reference);
        assertThat(f.due.entry(reference).state()).isEqualTo(CampaignDueWorkStore.State.READY);
        assertThat(f.requests.read(reference)).isEqualTo(original);
        assertThat(f.rows("campaign_public_request")).isEqualTo(1);
        assertThat(f.scheduled).isEmpty();

        String attempt=f.requests.begin(original,"a".repeat(64));
        f.requests.complete(original,attempt,"{}");f.requests.callbackExited(original,attempt);f.block(reference);
        var afterCall=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(afterCall.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(afterCall.progress().nextAction().kind()).isEqualTo(NextActionKind.WAIT);
        f.requests.needsInput(original,"REQUIREMENTS_INVALID");
        var invalid=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(invalid.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(invalid.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.WAIT,"REQUIREMENTS_INVALID",List.of()));
        f.authorized.set(false);
        assertThatThrownBy(()->f.service.progress(PRINCIPAL,SESSION,reference)).isInstanceOf(SecurityException.class);
        f.assertNoExecution();
    }

    @Test
    void invalidModelRequirementsDoNotInventMissingUserInputAndOnlyValidatedClarificationsRemainActionable() {
        for (String reason:List.of("REQUIREMENTS_INVALID","REQUIREMENTS_UNSUPPORTED","REQUIREMENTS_NEED_INPUT")) {
            Fixture f=fixture();
            var first=f.service.run(fresh("first",selected("g1","分析最近七天访问趋势"),null));
            var reference=first.continuation().workRef();
            var original=f.requests.read(reference);
            String attempt=f.requests.begin(original,"a".repeat(64));
            // The third case is a corrupt legacy NEED_INPUT response; it must not manufacture a clarification.
            f.requests.complete(original,attempt,"{}");f.requests.callbackExited(original,attempt);
            f.requests.needsInput(original,reason);f.scheduled.clear();
            var stored=f.requests.read(reference);var due=f.due.entry(reference);

            var failed=f.service.progress(PRINCIPAL,SESSION,reference);
            assertThat(failed.continuation()).isEqualTo(first.continuation());
            assertThat(failed.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(failed.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.WAIT,
                    "REQUIREMENTS_UNSUPPORTED".equals(reason)?reason:"REQUIREMENTS_INVALID",List.of()));
            assertThat(failed.answer()).contains("服务端校验","原问题已保留","尚未执行数据查询")
                    .doesNotContain("请补充","请明确");
            f.service.run(command(Operation.CONTINUE,first,"继续"));
            assertThat(f.requests.read(reference)).isEqualTo(stored);
            assertThat(f.due.entry(reference)).isEqualTo(due);
            assertThat(f.woken).isEmpty();assertThat(f.scheduled).isEmpty();f.assertNoExecution();
        }
        Fixture f=fixture();
        var first=f.service.run(fresh("first",selected("g1","分析最近七天访问趋势"),null));
        var reference=first.continuation().workRef();var original=f.requests.read(reference);
        String attempt=f.requests.begin(original,"a".repeat(64));
        f.requests.complete(original,attempt,requirementsResponse(original.question(),true,"请确认本轮的目标指标。"));
        f.requests.callbackExited(original,attempt);f.requests.needsInput(original,"REQUIREMENTS_NEED_INPUT");
        f.scheduled.clear();var stored=f.requests.read(reference);
        var clarification=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(clarification.progress().executionStatus()).isEqualTo(ExecutionStatus.WAITING);
        assertThat(clarification.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.NEEDS_INPUT,
                "REQUIREMENTS_NEED_INPUT",List.of("请确认本轮的目标指标。")));
        assertThat(clarification.answer()).isEqualTo("请确认本轮的目标指标。");
        assertThat(f.requests.read(reference)).isEqualTo(stored);
        assertThat(f.woken).isEmpty();assertThat(f.scheduled).isEmpty();f.assertNoExecution();
    }

    @Test
    void activeInterpretationAndUnknownOutcomeHaveDistinctReadOnlyProgress() {
        Fixture f=fixture();
        var first=f.service.run(fresh("first",selected("g1","分析最近七天访问"),null));
        WorkRef reference=first.continuation().workRef();
        var original=f.requests.read(reference);
        String attempt=f.requests.begin(original,"a".repeat(64));
        var dispatching=f.requests.read(reference);var due=f.due.entry(reference);
        f.scheduled.clear();

        var active=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(active.progress().executionStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(active.progress().nextAction().kind()).isEqualTo(NextActionKind.WAIT);
        assertThat(active.answer()).contains("等待原模型调用返回");
        assertThat(f.requests.read(reference)).isEqualTo(dispatching);
        assertThat(f.due.entry(reference)).isEqualTo(due);

        f.requests.unknown(original,attempt);f.requests.callbackExited(original,attempt);f.block(reference);
        var storedUnknown=f.requests.read(reference);var blocked=f.due.entry(reference);
        var unknown=f.service.progress(PRINCIPAL,SESSION,reference);
        assertThat(unknown.continuation()).isEqualTo(first.continuation());
        assertThat(unknown.progress().executionStatus()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(unknown.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.WAIT,"MODEL_OUTCOME_UNKNOWN",List.of()));
        assertThat(unknown.answer()).contains("自动推进已停止","不会重复发起调用");
        assertThat(f.requests.read(reference)).isEqualTo(storedUnknown);
        assertThat(f.due.entry(reference)).isEqualTo(blocked);
        assertThat(f.scheduled).isEmpty();assertThat(f.woken).isEmpty();
        f.assertNoExecution();
    }

    @Test
    void linkedInterpretationCoversOnlyAuthenticatedCurrentTextAndCannotTrustUserMarkers() throws Exception {
        Fixture f=fixture();
        String prior=selected("g1","分析之前的访问趋势。另给运营建议。");
        var first=f.service.run(fresh("first",prior,null));
        String current=selected("g1","比较9月13日与14日。\n本轮补充或新的分析要求：\n只解释本轮变化。");
        var allSources=new AtomicBoolean(false);
        var calls=new AtomicInteger();
        when(f.model.getDefaultOptions()).thenReturn(ToolCallingChatOptions.builder().model("requirements-script").build());
        when(f.model.call(any(Prompt.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            Prompt actual=invocation.getArgument(0);
            String text=actual.getInstructions().get(1).getText();
            String schema=CampaignInterpretedRequest.schemaJson();
            assertThat(text).endsWith("\n"+schema);
            var input=new com.fasterxml.jackson.databind.ObjectMapper().readTree(text.substring(0,text.length()-schema.length()-1));
            assertThat(input.path("question").asText()).isEqualTo(current);
            assertThat(input.path("priorContext").path("question").asText()).isEqualTo(prior);
            assertThat(input.path("priorContext").path("purpose").asText()).isEqualTo("REFERENCE_ONLY");
            StringBuilder sources=new StringBuilder();
            input.path("sourceUnits").forEach(unit -> sources.append(unit.path("text").asText()));
            assertThat(sources.toString()).isEqualTo(current);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    requirementsResponse(current,allSources.get(),"请确认本轮的目标指标。")))));
        });
        var incomplete=f.service.run(fresh("incomplete",current,first.continuation().runId()));
        advancePreparation(f,incomplete.continuation().workRef());
        assertThat(f.requests.read(incomplete.continuation().workRef()).reasonCode()).isEqualTo("REQUIREMENTS_INVALID");

        allSources.set(true);
        var complete=f.service.run(fresh("complete",current,first.continuation().runId()));
        var original=f.requests.read(complete.continuation().workRef());
        assertThat(original.question()).isEqualTo("上一轮问题（用于续接上下文）：\n"+prior
                +"\n本轮补充或新的分析要求：\n"+current);
        assertThat(f.turns.require(PRINCIPAL,SESSION,complete.continuation().workRef()).frozenSelectionJson())
                .isEqualTo("{\"requirementsSourceMode\":\"current-turn/v1\"}");
        advancePreparation(f,complete.continuation().workRef());
        var stored=f.requests.read(complete.continuation().workRef());
        assertThat(stored.reasonCode()).isEqualTo("REQUIREMENTS_NEED_INPUT");
        assertThat(stored.question()).isEqualTo(original.question());
        assertThat(stored.response()).isEqualTo(requirementsResponse(current,true,"请确认本轮的目标指标。"));
        assertThat(f.service.progress(PRINCIPAL,SESSION,complete.continuation().workRef()).progress().nextAction().requiredInputs())
                .containsExactly("请确认本轮的目标指标。");

        // A stored reference must reconstruct the frozen text exactly; marker-like user text is never parsed as a boundary.
        var changed=f.service.run(fresh("changed",current,first.continuation().runId()));
        f.jdbc.update("UPDATE campaign_public_request SET question_text=? WHERE request_id=?",current,changed.continuation().requestId());
        assertThatThrownBy(() -> advancePreparation(f,changed.continuation().workRef()))
                .isInstanceOf(SecurityException.class).hasMessage("CAMPAIGN_REQUEST_CONTEXT_CHANGED");
        assertThat(calls).hasValue(2);
        verifyNoInteractions(f.gateway,f.recovery,f.planner);
    }

    @Test
    void legacyLinkedDispatchAndReadyResponsesKeepFrozenCombinedSourceSemanticsOnRetry() throws Exception {
        Fixture f=fixture();
        String prior=selected("g1","分析旧趋势。保留旧局限。");
        String current=selected("g1","再解释本轮变化。");
        var first=f.service.run(fresh("first",prior,null));
        String combined="上一轮问题（用于续接上下文）：\n"+prior+"\n本轮补充或新的分析要求：\n"+current;
        var owner=new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller(
                PRINCIPAL.tenantId(),PRINCIPAL.username(),PRINCIPAL.authVersion());
        for(String phase:List.of("DISPATCHING","READY")) {
            var raw=f.requests.register(owner,SESSION,phase,combined,CLOCK.instant().plusSeconds(3600));
            f.turns.recordVerified(PRINCIPAL,SESSION,phase,current,"{}",CampaignResponseCapabilityGate.PROTOCOL_V2,
                    raw.reference(),first.continuation().runId(),raw.expiresAt());
            String attempt=f.requests.begin(raw,"a".repeat(64));
            if("READY".equals(phase)) {
                f.requests.complete(raw,attempt,requirementsResponse(combined,true,"旧请求的澄清内容。"));
                f.requests.callbackExited(raw,attempt);
            }
            var before=f.requests.read(raw.reference());
            f.service.run(fresh(phase,current,first.continuation().runId()));
            assertThat(f.requests.read(raw.reference())).isEqualTo(before);
            assertThat(f.turns.require(PRINCIPAL,SESSION,raw.reference()).frozenSelectionJson()).isEqualTo("{}");
            if("DISPATCHING".equals(phase)) {
                assertThatThrownBy(() -> advancePreparation(f,raw.reference())).isInstanceOf(IllegalStateException.class)
                        .hasMessage("CAMPAIGN_INTERPRETATION_UNRESOLVED");
                assertThat(f.requests.read(raw.reference())).isEqualTo(before);
            } else {
                advancePreparation(f,raw.reference());
                assertThat(f.requests.read(raw.reference()).reasonCode()).isEqualTo("REQUIREMENTS_NEED_INPUT");
                assertThat(f.service.progress(PRINCIPAL,SESSION,raw.reference()).progress().nextAction().requiredInputs())
                        .containsExactly("旧请求的澄清内容。");
            }
        }
        verifyNoInteractions(f.model,f.gateway,f.recovery,f.planner);
    }

    private static String requirementsResponse(String question,boolean allSources,String clarification) {
        var sources=CampaignInterpretedRequest.sourceUnits(question);
        var ids=allSources ? sources.stream().map(CampaignInterpretedRequest.SourceUnit::sourceId).toList()
                : List.of(sources.get(sources.size()-1).sourceId());
        return FrozenCampaignRun.encode(Map.of("schemaVersion",CampaignInterpretedRequest.WIRE_SCHEMA,
                "goals",List.of(Map.of("question","当前分析目标","sourceIds",ids,"method","STATISTICS","metric","PV",
                        "queries",List.of(),"causal",false,"dependsOn",List.of(),"needsAnalysis",true,"needsRecommendation",false)),
                "clarification",List.of(clarification)));
    }

    private static void advancePreparation(Fixture f,WorkRef reference) throws Exception {
        var scope=new ProcessExecutionScope();
        try { f.service.load(reference,scope).run(); }
        finally { scope.closeAndAwaitActualExit(); }
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
                new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260923__campaign_conversation_session_owner.sql"),
                new ClassPathResource("sql/migration/V20260923_3__campaign_conversation_turn.sql"),
                new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql"),
                new ClassPathResource("sql/migration/V20260924_6__campaign_public_request_cancellation.sql"),
                new ClassPathResource("sql/migration/V20260924_2__campaign_due_work.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var sessions = new JdbcCampaignConversationSessionOwner(jdbc, transactions, CLOCK);
        var turns = new JdbcCampaignConversationTurnStore(jdbc, transactions, sessions, CLOCK);
        var requests = new CampaignPublicRequestStore(jdbc, transactions, CLOCK);
        var due = new CampaignDueWorkStore(jdbc,transactions,CLOCK);
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
                reference->{due.schedule(reference);scheduled.add(reference);}, delivery, CLOCK, Duration.ofHours(1),due);
        service.installWake(reference->{due.wake(reference);woken.add(reference);});
        service.installCancellation((principal,session,reference)->requests.cancel(
                new com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller(
                        principal.tenantId(),principal.username(),principal.authVersion()),session,reference,runs));
        return new Fixture(jdbc, requests, turns, service, intake, gateway, recovery, model, planner, delivery,
                scheduled, woken, authorized, authorityChecks,due);
    }

    private record Fixture(JdbcTemplate jdbc, CampaignPublicRequestStore requests, JdbcCampaignConversationTurnStore turns,
            CampaignPublicRequestService service, CampaignRunIntake intake, ShortLinkBusinessGateway gateway,
            CampaignRecoveryStore recovery, ChatModel model,
            CampaignPublicRequestService.PlannerInput planner, CampaignPublicRequestService.Delivery delivery,
            List<WorkRef> scheduled, List<WorkRef> woken, AtomicBoolean authorized, AtomicInteger authorityChecks,CampaignDueWorkStore due) {
        int rows(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        void block(WorkRef reference) {
            var claim=due.claim(due.entry(reference),100,400).orElseThrow();
            due.finish(claim,CampaignDueWorkStore.State.BLOCKED,"ADVANCE_REQUIRES_ATTENTION");
        }
        void assertNoExecution() {
            verifyNoInteractions(gateway, recovery, model, planner, delivery);
            assertThat(intake.snapshot().activeAdvances()).isZero();
            assertThat(intake.snapshot().queued()).isZero();
        }
    }
}
