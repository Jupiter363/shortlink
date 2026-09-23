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
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.*;
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
            var rejected=f.adapter.read(PRINCIPAL,"session",f.reference).orElseThrow();
            assertThat(rejected.progress().nextAction().kind()).isEqualTo(NextActionKind.NEEDS_INPUT);
            assertThat(rejected.answer()).contains("尚未执行数据查询");
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

    private static Fixture fixture() {
        var source=new JdbcDataSource(); source.setURL("jdbc:h2:mem:public_delivery_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var migrations=List.of("V20260919__campaign_run_ledger.sql","V20260919_2__campaign_step_ledger.sql",
                "V20260919_3__campaign_run_owner.sql","V20260920__campaign_statistics_result.sql",
                "V20260920_3__campaign_submission_deferral.sql","V20260920_17__campaign_run_intake.sql",
                "V20260920_18__campaign_planning_request.sql","V20260923__campaign_conversation_session_owner.sql",
                "V20260923_2__campaign_advance_outcome.sql","V20260924_3__campaign_public_request.sql",
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
        return new Fixture(jdbc,runtime,model,gateway,raw.reference(),header.requestId(),new CampaignPublicDeliveryAdapter(runtime,reports,requests));
    }
    private record Fixture(JdbcTemplate jdbc,CampaignStatisticsFixedRuntime runtime,ChatModel model,ShortLinkBusinessGateway gateway,
            WorkRef reference,String planningId,CampaignPublicDeliveryAdapter adapter) implements AutoCloseable {
        void planningState(String state) {
            jdbc.update("UPDATE campaign_planning_request SET request_state=?,attempt_id='prior-attempt',invocation_hash=?,invocation_json='{}',"
                    +"response_hash=?,response_json=? WHERE request_id=?",state,"a".repeat(64),
                    "REJECTED".equals(state)?"b".repeat(64):null,"REJECTED".equals(state)?"{}":null,planningId);
        }
        @Override public void close() {runtime.close();}
    }
}
