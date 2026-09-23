package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignResponseCapabilityGate;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignDueWorkStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.*;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunRequest;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignPublicDeliveryAdapterTest {
    private static final Caller OWNER=new Caller("1001","analyst",7);
    private static final AgentPrincipal PRINCIPAL=new AgentPrincipal("1001","analyst",7,false);
    private static final Instant NOW=Instant.parse("2026-09-24T00:00:00Z");
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);

    @Test
    void unknownAndRejectedPlanningRemainVisibleWithoutRunOrRedispatchAndCannotChangeRequestIdentity() {
        try (Fixture f=fixture()) {
            f.planningState("UNKNOWN");
            var unknown=f.adapter.read(PRINCIPAL,"session",f.reference).orElseThrow();
            assertThat(unknown.progress().executionStatus()).isEqualTo(ExecutionStatus.UNKNOWN);
            assertThat(unknown.progress().nextAction().reasonCode()).isEqualTo("MODEL_OUTCOME_UNKNOWN");
            assertThat(unknown.continuation()).isEqualTo(new AgentRunRequest.Continuation(f.reference.runId(),f.reference.workId()));
            assertThat(unknown.answer()).contains("不会重复发送");
            f.planningState("REJECTED");
            var saved=f.runtime.intake().planningReceipt(PRINCIPAL,new WorkRef(f.reference.runId(),f.planningId));
            var rejected=f.adapter.read(PRINCIPAL,"session",f.reference).orElseThrow();
            assertThat(rejected.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(rejected.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.WAIT,"PLANNING_UNRESOLVED",List.of()));
            assertThat(rejected.answer()).contains("尚未执行数据查询","未通过服务端校验","原问题已保留")
                    .doesNotContain("请补充","请明确");
            assertThat(rejected.continuation()).isEqualTo(unknown.continuation());
            assertThat(f.runtime.intake().planningReceipt(PRINCIPAL,new WorkRef(f.reference.runId(),f.planningId))).isEqualTo(saved);
            assertThatThrownBy(()->f.adapter.read(PRINCIPAL,"other-session",f.reference)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->f.adapter.read(PRINCIPAL,"session",new WorkRef(f.reference.runId(),"request-forged")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_ledger",Integer.class)).isZero();
            verifyNoInteractions(f.model,f.gateway);
        }
    }

    @Test
    void emptyPlanWithInputGapsFinishesAsNeedsInputInsteadOfWaitingForNonexistentSteps() {
        try (Fixture f=fixture()) {
            var identity=JdbcCampaignRunIntakeStore.identity(OWNER,"session","key");
            var plan=new PlanSpec(PlanSpec.SCHEMA_VERSION,identity.planId(),1,f.reference.runId(),"inputs",
                    List.of(new PlanSpec.Goal("goal","分析",true,"回答问题")),List.of());
            var assessment=new PlanningAssessment(identity.planId(),1,"catalog",List.of(new PlanningAssessment.Requirement(
                    "requirement","goal",PlanningAssessment.RequirementKind.DATA,true,"data","1",Map.of())),List.of(),
                    List.of(new PlanningAssessment.Gap("requirement",PlanningAssessment.GapReason.NEEDS_INPUT,"请指定待分析分组。")));
            f.runtime.runs().createRun(FrozenCampaignRun.freeze(plan,new FrozenInputSet("inputs",f.reference.runId(),Map.of(),Map.of()),assessment)
                    .definition(OWNER,"session"));
            var result=f.adapter.read(PRINCIPAL,"session",f.reference).orElseThrow();
            assertThat(result.progress().executionStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(result.progress().nextAction().kind()).isEqualTo(NextActionKind.NEEDS_INPUT);
            assertThat(result.progress().nextAction().requiredInputs()).containsExactly("请指定待分析分组。");
            assertThat(result.report()).isNull();
            assertThat(result.progress().goals()).containsExactly(new AgentRunResult.GoalProgress(
                    "goal","分析",GoalAssessment.Status.NEEDS_INPUT,List.of("请指定待分析分组。")));
            assertThat(result.answer()).contains("尚未形成可交付结果");
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle",Integer.class)).isZero();
            verifyNoInteractions(f.model,f.gateway);
        }
    }

    @Test
    void blockedAcceptedStatisticsReadPreservesIdentityAndOnlyUntouchedExecutionCanContinue() {
        try (Fixture f=fixture()) {
            var token=f.statisticsRun();
            f.block();
            var paused=f.due.entry(f.reference);
            var request=f.requests.read(f.reference);
            // A plan whose ledger has not initialized is not a completed empty plan.
            var beforeInitialization=f.service.progress(PRINCIPAL,"session",f.reference);
            assertThat(beforeInitialization.progress().nextAction().kind()).isEqualTo(NextActionKind.CONTINUE);
            f.initializeStatistics(token);
            var steps=f.runtime.steps().snapshot(OWNER,f.reference.runId());

            var progress=f.service.progress(PRINCIPAL,"session",f.reference);
            assertThat(progress.continuation()).isEqualTo(new AgentRunRequest.Continuation(f.reference.runId(),f.reference.workId()));
            assertThat(progress.progress().executionStatus()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(progress.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.CONTINUE,"ADVANCE_REQUIRES_ATTENTION",List.of()));
            assertThat(progress.answer()).contains("已暂停","尚未派发");
            assertThat(f.runtime.steps().snapshot(OWNER,f.reference.runId())).isEqualTo(steps);
            assertThat(f.due.entry(f.reference)).isEqualTo(paused);
            assertThat(f.requests.read(f.reference)).isEqualTo(request);
            assertThat(f.woken).isEmpty();
            assertThatThrownBy(()->f.adapter.read(PRINCIPAL,"session",f.reference,
                    new CampaignDueWorkStore.Entry(new WorkRef(f.reference.runId(),"request-forged"),
                            CampaignDueWorkStore.State.BLOCKED,0,0,1,"ADVANCE_REQUIRES_ATTENTION")))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->f.service.progress(PRINCIPAL,"other-session",f.reference)).isInstanceOf(SecurityException.class);

            var continued=f.service.run(f.continueRequest());
            assertThat(continued.continuation()).isEqualTo(progress.continuation());
            assertThat(f.woken).containsExactly(f.reference);
            assertThat(f.due.entry(f.reference).state()).isEqualTo(CampaignDueWorkStore.State.READY);
            assertThat(f.runtime.steps().snapshot(OWNER,f.reference.runId())).isEqualTo(steps);
            assertThat(f.requests.read(f.reference)).isEqualTo(request);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger",Integer.class)).isZero();
            verifyNoInteractions(f.model,f.gateway);
            when(f.authority.verifyCurrentPrincipal(any())).thenThrow(new SecurityException("ACCOUNT_REVOKED"));
            assertThatThrownBy(()->f.service.progress(PRINCIPAL,"session",f.reference)).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void blockedAcceptedInFlightAndUnknownJobsStayReadOnlyAndCannotBeWokenByContinue() {
        try (Fixture f=fixture()) {
            var token=f.statisticsRun(); f.initializeStatistics(token); f.block();
            f.runtime.runs().prepareAction(token,new ActionSpec("statistics-action","statistics","TOOL","statistics.job","1","{}"));
            f.runtime.runs().prepareChild(token,new ChildSpec("statistics-child","statistics-action",ChildMode.ASYNC,"original-job-request",
                    new WireRequest("POST","/internal/statistics/jobs","{}")));
            for (String state:List.of("DISPATCHING","UNRESOLVED")) {
                f.jdbc.update("UPDATE campaign_child_ledger SET child_state=?,callback_active=?,attempt_id='original-attempt',"
                        +"attempt_version=1,unresolved_reason=? WHERE child_id='statistics-child'",state,
                        state.equals("DISPATCHING"),state.equals("UNRESOLVED")?"SUBMISSION_UNRESOLVED":null);
                var before=f.runtime.runs().children(token); var paused=f.due.entry(f.reference);
                var progress=f.service.progress(PRINCIPAL,"session",f.reference);
                assertThat(progress.progress().executionStatus()).isEqualTo(ExecutionStatus.UNKNOWN);
                assertThat(progress.progress().nextAction()).isEqualTo(new NextAction(NextActionKind.WAIT,"ADVANCE_REQUIRES_ATTENTION",List.of()));
                assertThat(progress.answer()).contains("已暂停","不会重复派发");
                assertThat(f.service.run(f.continueRequest()).continuation()).isEqualTo(progress.continuation());
                assertThat(f.runtime.runs().children(token)).isEqualTo(before);
                assertThat(f.due.entry(f.reference)).isEqualTo(paused);
                assertThat(f.woken).isEmpty();
            }
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_public_request",Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_child_ledger",Integer.class)).isEqualTo(1);
            verifyNoInteractions(f.model,f.gateway);
        }
    }

    private static Fixture fixture() {
        var source=new JdbcDataSource(); source.setURL("jdbc:h2:mem:public_delivery_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var migrations=List.of("V20260919__campaign_run_ledger.sql","V20260919_2__campaign_step_ledger.sql",
                "V20260919_3__campaign_run_owner.sql","V20260920__campaign_statistics_result.sql",
                "V20260920_3__campaign_submission_deferral.sql","V20260920_17__campaign_run_intake.sql",
                "V20260920_18__campaign_planning_request.sql","V20260923__campaign_conversation_session_owner.sql",
                "V20260923_3__campaign_conversation_turn.sql","V20260924_2__campaign_due_work.sql",
                "V20260923_2__campaign_advance_outcome.sql","V20260924_3__campaign_public_request.sql",
                "V20260924_6__campaign_public_request_cancellation.sql",
                "V20260920_22__campaign_report_lifecycle.sql","V20260921__campaign_run_result_binding.sql");
        new ResourceDatabasePopulator(migrations.stream().map(name->new ClassPathResource("sql/migration/"+name))
                .toArray(ClassPathResource[]::new)).execute(source);
        var jdbc=new JdbcTemplate(source); var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var authority=mock(AgentAuthorityClient.class); when(authority.verifyCurrentPrincipal(any())).thenReturn(PRINCIPAL);
        var gateway=mock(ShortLinkBusinessGateway.class); var model=mock(ChatModel.class);
        var catalog=mock(CapabilityCatalog.class); when(catalog.version()).thenReturn("catalog");
        var runtime=new CampaignStatisticsFixedRuntime(jdbc,tx,CLOCK,authority,gateway,new MemorySaver(),"public-delivery-test",
                new ProcessCapacityExecutor.Limits(1,1,1,2),null,context->{
            var profile=new CampaignRunIntake.Profile("public","1",catalog,new ArtifactContractRegistry(List.of()),
                    (caller,inputs)->OWNER.equals(caller),(caller,artifact)->false,(caller,type,value)->false,
                    execution->{throw new AssertionError("A read cannot execute a plan");});
            var planning=new CampaignRunIntake.PlanningProfile("public","1",new PlanningProposal.Menu("catalog",List.of(),List.of()),
                    model,new ModelInvocationRegistry(List.of()),"model","1","a".repeat(64),
                    ModelInvocationRegistry.Limits.defaults(),PlanningProposal.Limits.defaults());
            return new CampaignStatisticsFixedRuntime.Extension(profile,planning,(caller,session,key,candidate,expiry)->{
                throw new AssertionError("A read cannot prepare a model call");});
        });
        runtime.principals().bindCurrent(PRINCIPAL,"session");
        var requests=new CampaignPublicRequestStore(jdbc,tx,CLOCK);
        var raw=requests.register(OWNER,"session","key","分析",NOW.plusSeconds(3600));
        var intake=new JdbcCampaignRunIntakeStore(jdbc,tx,CLOCK,runtime.runs(),CampaignRunStore.Limits.defaults().definitionBytes());
        var planning=new JdbcCampaignPlanningStore(jdbc,tx,CLOCK,intake,PlanningProposal.Limits.defaults().requestBytes(),ModelInvocationRegistry.Limits.defaults());
        var header=planning.register(OWNER,"session","key","public","1","model","1","a".repeat(64),"{}",NOW.plusSeconds(3600));
        String attempt=requests.begin(raw,"b".repeat(64)); requests.complete(raw,attempt,"{}"); requests.callbackExited(raw,attempt);
        requests.bind(raw,new WorkRef(header.runId(),header.requestId()));
        var reports=new CampaignReportDeliveryService(jdbc,tx,CLOCK,runtime.runs(),runtime.steps(),new JdbcReportLifecycleStore(jdbc,tx,CLOCK),
                (caller,artifact)->false,(caller,definition)->OWNER.equals(caller),
                new CampaignArtifactReportRows(runtime.runs(),runtime.results(),mock(CampaignDeclineSelectionStore.class)));
        var adapter=new CampaignPublicDeliveryAdapter(runtime,reports,requests);
        var turns=new JdbcCampaignConversationTurnStore(jdbc,tx,new JdbcCampaignConversationSessionOwner(jdbc,tx,CLOCK),CLOCK);
        turns.recordVerified(PRINCIPAL,"session","key","分析","{}",CampaignResponseCapabilityGate.PROTOCOL_V2,
                raw.reference(),null,raw.expiresAt());
        var due=new CampaignDueWorkStore(jdbc,tx,CLOCK); due.schedule(raw.reference());
        List<WorkRef> woken=new ArrayList<>();
        var service=new CampaignPublicRequestService(requests,turns,runtime.principals(),runtime.intake(),model,
                (caller,session,key,candidate,expiry)->{throw new AssertionError("A read cannot prepare work");},
                "public","1",ignored->{throw new AssertionError("Existing reads cannot register new work");},adapter,CLOCK,Duration.ofHours(1),due);
        service.installWake(reference->{woken.add(reference);due.wake(reference);});
        return new Fixture(jdbc,runtime,model,gateway,raw.reference(),header.requestId(),adapter,requests,due,service,woken,authority);
    }
    private record Fixture(JdbcTemplate jdbc,CampaignStatisticsFixedRuntime runtime,ChatModel model,ShortLinkBusinessGateway gateway,
            WorkRef reference,String planningId,CampaignPublicDeliveryAdapter adapter,CampaignPublicRequestStore requests,
            CampaignDueWorkStore due,CampaignPublicRequestService service,List<WorkRef> woken,AgentAuthorityClient authority) implements AutoCloseable {
        RunToken statisticsRun() {
            var identity=JdbcCampaignRunIntakeStore.identity(OWNER,"session","key");
            var step=new PlanSpec.Step("statistics",List.of("goal"),PlanSpec.ExecutionMode.FIXED,
                    new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL,"statistics.job","1"),null,List.of(),Map.of(),Map.of(),"statistics-output");
            var plan=new PlanSpec(PlanSpec.SCHEMA_VERSION,identity.planId(),1,reference.runId(),"inputs",
                    List.of(new PlanSpec.Goal("goal","访问统计",true,"回答问题")),List.of(step));
            var assessment=new PlanningAssessment(identity.planId(),1,"catalog",List.of(),List.of(),List.of());
            var token=runtime.runs().createRun(FrozenCampaignRun.freeze(plan,
                    new FrozenInputSet("inputs",reference.runId(),Map.of(),Map.of()),assessment).definition(OWNER,"session"));
            // This read fixture represents a completed planning call. Keep all attempt, response,
            // and acceptance columns coherent with the real ledger's CHECK constraints.
            jdbc.update("UPDATE campaign_planning_request SET request_state='ACCEPTED',callback_active=FALSE,"
                            +"attempt_id='completed-planning-attempt',invocation_json='{}',invocation_hash=?,"
                            +"response_json='{}',response_hash=?,definition_hash=?,intake_request_id=? WHERE request_id=?",
                    CampaignRunStore.sha256("{}"),CampaignRunStore.sha256("{}"),
                    token.definition().definitionHash(),identity.requestId(),planningId);
            return token;
        }
        void initializeStatistics(RunToken token) {
            runtime.steps().initialize(token,List.of(new CampaignStepStore.StepSpec("statistics","{}",List.of(),Set.of(),Set.of())));
        }
        void block() {
            var claim=due.claim(due.entry(reference),1,1).orElseThrow();
            due.finish(claim,CampaignDueWorkStore.State.BLOCKED,"ADVANCE_REQUIRES_ATTENTION");
        }
        AgentRunRequest continueRequest() {
            return new AgentRunRequest("session","campaign-analysis",PRINCIPAL.username(),"继续",PRINCIPAL,"key",
                    Set.of(CampaignResponseCapabilityGate.CAPABILITY_V2),AgentRunRequest.Operation.CONTINUE,
                    new AgentRunRequest.Continuation(reference.runId(),reference.workId()),null);
        }
        void planningState(String state) {
            jdbc.update("UPDATE campaign_planning_request SET request_state=?,attempt_id='prior-attempt',invocation_hash=?,invocation_json='{}',"
                    +"response_hash=?,response_json=? WHERE request_id=?",state,"a".repeat(64),
                    "REJECTED".equals(state)?"b".repeat(64):null,"REJECTED".equals(state)?"{}":null,planningId);
        }
        @Override public void close() {runtime.close();}
    }
}
