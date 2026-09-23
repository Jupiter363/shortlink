package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.*;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Native model responses and the original remote job cross a real committed revision. */
class CampaignBusinessReplanServiceTest {
    static final Clock CLOCK = CampaignParentCoverageTest.CLOCK;
    static final Instant EXPIRY = CLOCK.instant().plusSeconds(3600);
    static final Caller OWNER = new Caller("1001","analyst",7);
    static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001","analyst",7,false);
    static final String RUN="replan-business", SESSION="replan-session", CONFIG="d".repeat(64);
    static final String SCOPE="scope-frozen", PERIODS="period-september";
    static final String CONTRACT=CampaignStatisticsResultStore.SCHEMA_VERSION;

    @Test void nativeSignalRevisesAndAdoptsOriginalFrozenJobThenContinuesWithoutResubmission() throws Exception {
        var f=new Fixture(false);
        assertEquals(2,f.runtime(f.initial).graph().advance().advancedSteps());
        assertEquals(CampaignStepStore.StepStatus.WAITING,f.steps.step(f.initial,"collect").orElseThrow().status());
        assertEquals(CampaignExplorationCandidateStore.Verdict.REPLAN_REQUESTED,f.candidates.assessment(f.initial,"explore").orElseThrow().verdict());
        assertEquals(1,f.gateway.submits); assertEquals(1,f.model.calls); assertEquals(1,f.count("campaign_statistics_consumer"));
        var original=FrozenStatisticsJobQuery.resolve(f.initial.definition(),StatisticsJobFixedExecutor.REF).get("collect");
        var originalChild=f.runs.child(f.initial,original.child().childId()).orElseThrow();

        var result=f.service.advanceCurrent(PRINCIPAL,SESSION,RUN,f.scope,f.model,f.settings);
        assertEquals("APPLIED",result.state(),result.reason()); assertEquals(2,f.model.calls);
        var current=f.runs.loadRun(OWNER,RUN).orElseThrow(); assertEquals(2,current.definition().revision());
        assertEquals(current.definition(),new CampaignReplanDefinitionResolver(f.base.jdbc,f.base.transactions,CLOCK)
                .resolve(f.initial.definition(),current));
        assertEquals(1,f.count("campaign_replan_receipt")); assertEquals(2,f.count("campaign_statistics_consumer"));
        assertEquals("APPLIED",f.base.jdbc.queryForObject("SELECT model_state FROM campaign_replan_model WHERE run_id=?",String.class,RUN));
        assertThrows(IllegalStateException.class,()->f.runs.beginReconciliation(f.initial,original.child().childId()));

        var next=f.steps.acquireRun(current.token());
        var waiting=f.runtime(next); assertEquals(1,waiting.graph().advance().advancedSteps());
        assertEquals("ADOPTED_STATISTICS_WAITING",f.steps.step(next,"collect").orElseThrow().reason());
        assertEquals(1,f.gateway.submits); assertEquals(0,f.runs.children(next).size());
        f.gateway.ready=true;
        next=f.steps.acquireRun(next);
        var finished=f.runtime(next); assertEquals(1,finished.graph().advance().advancedSteps());
        var complete=f.steps.step(next,"collect").orElseThrow();
        assertEquals(CampaignStepStore.StepStatus.SUCCEEDED,complete.status(),complete.reason());
        assertEquals(Map.of("pages",original.target().artifactId()),complete.outputs());
        assertEquals(1,f.gateway.submits); assertEquals(1,f.gateway.pages); assertEquals(2,f.model.calls);
        String adoptedId=CampaignStatisticsConsumerStore.consumerId(next.definition(),"collect",
                CampaignStatisticsConsumerStore.bindingId(OWNER,originalChild.jobId()));
        var source=f.consumers.resolve(next,adoptedId,f.consumerGrant);
        var binding=f.runs.child(source.sourceToken(),originalChild.spec().childId()).orElseThrow();
        assertEquals(originalChild.spec(),binding.spec()); assertEquals(originalChild.jobId(),binding.jobId());
        assertEquals(1,f.runs.readArtifact(OWNER,original.target().artifactId(),f.artifacts).metadata().revision());
        assertEquals(0,finished.graph().advance().advancedSteps());
        assertEquals("NOT_REQUESTED",f.service.advanceCurrent(PRINCIPAL,SESSION,RUN,f.scope,f.model,f.settings).state());
        f.allowed.set(false);
        assertThrows(SecurityException.class,()->f.runs.readArtifact(OWNER,original.target().artifactId(),f.artifacts));
        assertEquals(1,f.gateway.submits); assertEquals(2,f.model.calls); f.exited();
    }

    @Test void unknownNativeReplanOutcomeNeverDispatchesAnotherModelOrRevision() throws Exception {
        var f=new Fixture(true);
        f.runtime(f.initial).graph().advance();
        assertThrows(Exception.class,()->f.service.advanceCurrent(PRINCIPAL,SESSION,RUN,f.scope,f.model,f.settings));
        assertEquals(2,f.model.calls); assertEquals(1,f.runs.loadRun(OWNER,RUN).orElseThrow().definition().revision());
        assertEquals("UNKNOWN",f.base.jdbc.queryForObject("SELECT model_state FROM campaign_replan_model WHERE run_id=?",String.class,RUN));
        assertEquals("BLOCKED",f.service.advanceCurrent(PRINCIPAL,SESSION,RUN,f.scope,f.model,f.settings).state());
        assertEquals(2,f.model.calls); assertEquals(0,f.count("campaign_replan_receipt")); f.exited();
    }

    static final class Fixture {
        final CampaignParentCoverageTest.Fixture base=new CampaignParentCoverageTest.Fixture();
        final JdbcCampaignRunStore runs;
        final CampaignStepStore steps;
        final CampaignStatisticsResultStore results;
        final CampaignStatisticsConsumerStore consumers;
        final CampaignExplorationCandidateStore candidates;
        final CampaignExplorationRuntimeFactory exploration;
        final CampaignStatisticsConsumerStore.Authorizer consumerGrant;
        final ArtifactAuthorizer artifacts;
        final AtomicBoolean allowed=new AtomicBoolean(true);
        final ProcessExecutionScope scope=new ProcessExecutionScope();
        final Gateway gateway=new Gateway(this);
        final Model model;
        final CapabilityCatalog catalog;
        final ArtifactContractRegistry contracts=new ArtifactContractRegistry(List.of(StatisticsJobFixedExecutor.artifactContract()));
        final RunToken initial;
        final CampaignBusinessReplanService service;
        final CampaignBusinessReplanService.ModelSettings settings=new CampaignBusinessReplanService.ModelSettings("scripted","1",CONFIG,
                ModelInvocationRegistry.Limits.defaults(),PlanningProposal.Limits.defaults());
        final FrozenQueryScope frozenScope;
        final PlanSpec.Step collect;
        final PlanningAssessment.Requirement requirement=new PlanningAssessment.Requirement("delivery","goal",PlanningAssessment.RequirementKind.DELIVERY,
                true,"delivery","1",Map.of());
        Fixture(boolean unknown) throws Exception {
            String hash=FrozenQueryScope.memberHash(List.of(1L));
            frozenScope=new FrozenQueryScope(FrozenQueryScope.SCHEMA,"FROZEN_SET",SCOPE,hash,1,"a".repeat(64),
                    FrozenQueryScope.shardIdFor(SCOPE,0,hash),0,1,hash,List.of(1L));
            new ResourceDatabasePopulator(
                    resource("V20260919_3__campaign_run_owner.sql"),resource("V20260920_2__campaign_statistics_release.sql"),
                    resource("V20260920_3__campaign_submission_deferral.sql"),resource("V20260920_8__campaign_model_invocation.sql"),
                    resource("V20260920_9__campaign_exploration_call.sql"),resource("V20260920_10__campaign_exploration_ledger.sql"),
                    resource("V20260920_11__campaign_exploration_budget.sql"),resource("V20260920_12__campaign_skill_invocation.sql"),
                    resource("V20260920_13__campaign_skill_observation.sql"),resource("V20260920_14__campaign_exploration_candidate.sql"),
                    resource("V20260920_19__campaign_statistics_consumers.sql"),resource("V20260920_21__campaign_replan_receipt.sql"),
                    resource("V20260924_4__campaign_report_synthesis.sql"),resource("V20260924_5__campaign_replan_model.sql"))
                    .execute(base.jdbc.getDataSource());
            runs=new JdbcCampaignRunStore(base.jdbc,base.transactions,CLOCK);
            steps=new JdbcCampaignStepStore(base.jdbc,base.transactions,CLOCK);
            results=new JdbcCampaignStatisticsResultStore(base.jdbc,base.transactions,CLOCK);
            consumers=new JdbcCampaignStatisticsConsumerStore(base.jdbc,base.transactions,CLOCK,runs);
            consumerGrant=CampaignReplanStatisticsRecovery.consumerAuthorizer(base.jdbc,definition->this::queryAllowed);
            var adopted=CampaignReplanStatisticsRecovery.artifactAuthorizer(base.jdbc,runs,consumers,consumerGrant);
            artifacts=(caller,metadata)-> {
                if(!allowed.get()||!OWNER.equals(caller))return false;
                var current=runs.loadRun(caller,metadata.runId()).orElseThrow();
                if(metadata.revision()<current.definition().revision())return adopted.mayRead(caller,metadata);
                var bound=FrozenStatisticsJobQuery.resolve(current.definition(),StatisticsJobFixedExecutor.REF).get("collect");
                return bound!=null && metadata.owner().equals(caller) && metadata.ref().artifactId().equals(bound.target().artifactId())
                        && metadata.childId().equals(bound.child().childId()) && metadata.actionId().equals(bound.child().actionId())
                        && runs.child(current.token(),metadata.childId()).orElseThrow().state()==ChildState.READY;
            };
            var policy=CampaignExplorationRuntimeFactory.statisticsPolicy();
            catalog=new CapabilityCatalog(){
                public String version(){return "replan-catalog/v1";}
                public Optional<Capability> capability(PlanSpec.ExecutorRef ref){return StatisticsJobFixedExecutor.REF.equals(ref)?Optional.of(StatisticsJobFixedExecutor.capability()):Optional.empty();}
                public Optional<Policy> policy(String ref,String version){return policy.policyRef().equals(ref)&&policy.policyVersion().equals(version)?Optional.of(policy):Optional.empty();}
                public Optional<Criterion> criterion(String ref,String version){return Optional.of(new Criterion(ref,version,PlanningAssessment.RequirementKind.DELIVERY,Parameters.none(),Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE)));}
            };
            Map<String,PlanBinding> bindings=Map.of("scope",PlanBinding.input("scope"),"periods",PlanBinding.input("periods"),"query",PlanBinding.input("query"));
            collect=new PlanSpec.Step("collect",List.of("goal"),PlanSpec.ExecutionMode.FIXED,StatisticsJobFixedExecutor.REF,null,List.of(),bindings,Map.of(),CONTRACT);
            var explore=new PlanSpec.Step("explore",List.of("goal"),PlanSpec.ExecutionMode.REACT,null,
                    new PlanSpec.ExplorationPolicy(policy.policyRef(),policy.policyVersion(),List.of(StatisticsJobFixedExecutor.REF),SCOPE,PERIODS,
                            policy.completionCriteria(),policy.terminationPolicyRef()),List.of(),bindings,Map.of(),CONTRACT);
            var plan=new PlanSpec(PlanSpec.SCHEMA_VERSION,"replan-plan",1,RUN,"replan-inputs",
                    List.of(new PlanSpec.Goal("goal","Read authorized statistics",true,"Supply observed statistics evidence")),List.of(collect,explore));
            var query=new LinkedHashMap<String,Object>(Map.of("schemaVersion",FrozenStatisticsJobQuery.SCHEMA,"scopeRef",SCOPE,"periodsRef",PERIODS,
                    "scopeKind","FROZEN_SET","gid","g1","queryKind","ACCESS_RECORDS","startDate","2026-09-01","endDate","2026-09-01","businessTimezone","Asia/Shanghai"));
            query.put("scope",frozenScope.asMap());
            var inputs=new FrozenInputSet(plan.inputSetRef(),RUN,FrozenStatisticsJobQuery.INPUTS,Map.of("scope",SCOPE,"periods",PERIODS,"query",query));
            var assessment=new PlanningAssessment(plan.planId(),1,catalog.version(),List.of(requirement),coverage("explore"),List.of());
            initial=steps.acquireRun(runs.createRun(FrozenCampaignRun.freeze(plan,inputs,assessment).definition(OWNER,SESSION)));
            var models=new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("scripted","1",CONFIG,
                    invocation->Set.of(policy.policyRef(),NativeCampaignPlanner.POLICY_REF).contains(invocation.policyRef())&&"1".equals(invocation.policyVersion()))));
            candidates=new JdbcCampaignExplorationCandidateStore(base.jdbc,base.transactions,CLOCK,runs,steps,models,catalog,contracts,
                    new CompletionCriterionRegistry(List.of(CampaignExplorationRuntimeFactory.statisticsCompletionCheck())),artifacts);
            model=new Model(this,unknown);
            exploration=new CampaignExplorationRuntimeFactory(base.jdbc,base.transactions,CLOCK,runs,steps,
                    new JdbcCampaignExplorationCallStore(base.jdbc,base.transactions,CLOCK),results,candidates,
                    new JdbcCampaignSkillInvocationStore(base.jdbc,base.transactions,CLOCK,catalog,contracts),base.scopes,
                    new JdbcCampaignDeclineSelectionStore(base.jdbc,base.transactions,CLOCK,runs),new ClassPathResource("campaign-skills").getFile().toPath(),
                    gateway,models,model,new MemorySaver(),Runnable::run,new CampaignExplorationRuntimeFactory.Settings("scripted","1",CONFIG,
                        new NativeExplorationAdapter.Limits(0,65536,32768,65536,8,Duration.ofSeconds(10)),ExplorationBudgetPolicy.defaults(),ExplorationRepeatPolicy.disabled()));
            var profile=new CampaignRunIntake.Profile("replan-test","1",catalog,contracts,(caller,values)->allowed.get()&&OWNER.equals(caller),
                    artifacts,this::inputAllowed,context->runtime(context.token()));
            Map<String,Object> schema=Map.of("type","object","properties",Map.of(),"required",List.of(),"additionalProperties",false);
            var menu=PlanningProposal.menu(catalog,List.of(new PlanningProposal.CapabilityOffer(StatisticsJobFixedExecutor.REF,"Read frozen statistics",schema)),
                    List.of(new PlanningProposal.PolicyOffer(policy.policyRef(),policy.policyVersion(),"Explore statistics",schema)));
            service=new CampaignBusinessReplanService(base.jdbc,base.transactions,CLOCK,runs,candidates,models,profile,
                    (caller,session)->allowed.get()&&OWNER.equals(caller)&&SESSION.equals(session)?PRINCIPAL:null,consumerGrant,menu);
        }
        CampaignRecoveryCoordinator.Runtime runtime(RunToken token) throws Exception {
            var adapter=new StatisticsJobFixedExecutor(token.definition(),PRINCIPAL,gateway,this::queryAllowed);
            var recovery=CampaignReplanStatisticsRecovery.open(base.jdbc,base.transactions,CLOCK,token,PRINCIPAL,runs,steps,consumers,
                    new StatisticsJobResultReceiver(runs,results,gateway,CLOCK,consumers),consumerGrant,gateway,this::queryAllowed);
            recovery.prepareRecovery();
            var react=exploration.open(token,PRINCIPAL,catalog,contracts,this::inputAllowed,artifacts,this::queryAllowed,scope,EXPIRY);
            var driver=new PersistentPlanDriver(token,runs,steps,catalog,contracts,List.of(recovery.registration(adapter)),
                    (caller,values)->allowed.get()&&OWNER.equals(caller),artifacts,this::inputAllowed,react.registrations(),candidates,scope);
            return new CampaignRecoveryCoordinator.Runtime(driver,driver.compile(new MemorySaver()),adapter.resultTargets());
        }
        boolean inputAllowed(Caller caller,TypeRef type,Object value){return allowed.get()&&OWNER.equals(caller)
                && (FrozenStatisticsJobQuery.SCOPE_TYPE.equals(type)?SCOPE.equals(value):FrozenStatisticsJobQuery.PERIODS_TYPE.equals(type)&&PERIODS.equals(value));}
        boolean queryAllowed(AgentPrincipal principal,String scope,String periods,Map<String,Object> request){
            if(!allowed.get()||!PRINCIPAL.equals(principal)||!SCOPE.equals(scope)||!PERIODS.equals(periods))return false;
            var body=new TreeMap<>(request);body.remove("requestId");
            var expected=new TreeMap<>(FrozenStatisticsJobQuery.resolve(initial.definition(),StatisticsJobFixedExecutor.REF).get("collect").request());expected.remove("requestId");
            return FrozenCampaignRun.encode(body).equals(FrozenCampaignRun.encode(expected));
        }
        int count(String table){return base.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        void exited(){for(String table:List.of("campaign_child_ledger","campaign_step_ledger","campaign_exploration_call","campaign_replan_model"))
            assertEquals(0,base.jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE callback_active=TRUE",Integer.class));}
    }
    static final class Model implements ChatModel {
        final Fixture f;final boolean unknown;int calls;
        Model(Fixture f,boolean unknown){this.f=f;this.unknown=unknown;}
        public ChatResponse call(Prompt prompt){
            calls++;
            String response;
            if(calls==1)response=new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION,ExplorationCandidate.Kind.REQUEST_REPLAN,
                    "The fixed query already covers the original requirement",List.of(),null,null,"Keep collect and remove redundant exploration",null).encode();
            else {
                assertEquals(2,calls,"Unknown or completed planner turns cannot be dispatched again");
                if(unknown)throw new IllegalStateException("external outcome unknown");
                response=new PlanningProposal(PlanningProposal.SCHEMA_VERSION,List.of(f.collect),coverage("collect"),List.of()).encode();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
    static final class Gateway implements ShortLinkBusinessGateway {
        final Fixture f;int submits,pages;boolean ready;
        Gateway(Fixture f){this.f=f;}
        public ToolResult get(String path,ToolContext context,Map<String,Object> query){throw new AssertionError("legacy GET");}
        public ToolResult post(String path,ToolContext context,Map<String,Object> query){throw new AssertionError("legacy POST");}
        public ToolResult submitStatisticsJob(ToolContext c,Map<String,Object> request){throw new AssertionError("mutable submission");}
        public ToolResult submitFrozenStatisticsJob(ToolContext c,Map<String,Object> request){submits++;assertEquals(1,submits);return ToolResult.success(Map.of("jobId","original-job","state","RUNNING"));}
        public ToolResult readStatisticsJob(ToolContext context,String id){assertEquals("original-job",id);return ToolResult.success(Map.of("jobId",id,"state",ready?"SUCCEEDED":"RUNNING",
                "rowCount",ready?1:0,"pageCount",ready?1:0,"expiresAt",EXPIRY.toEpochMilli()));}
        public ToolResult readStatisticsJobPage(ToolContext context,String id,int page,int size){
            pages++;assertEquals("original-job",id);assertEquals(0,page);assertEquals(500,size);
            var meta=new LinkedHashMap<String,Object>();
            meta.put("snapshotId",id);meta.put("queryKind","ACCESS_RECORDS");meta.put("gid","g1");
            meta.put("linkIds",List.of(1L));meta.put("groupScopeComplete",false);meta.put("scopeProof",f.frozenScope.proof("b".repeat(64)));
            meta.put("requestedStart",LocalDate.parse("2026-09-01").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            meta.put("requestedEnd",LocalDate.parse("2026-09-02").atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            meta.put("effectiveEnd",meta.get("requestedEnd"));meta.put("businessTimezone","Asia/Shanghai");
            meta.put("recoveryEpoch","epoch-1");meta.put("metricVersion","click-v1");meta.put("sourceCut",Map.of("manifestSelectionHash","hash-1"));
            meta.put("manifestVersion",Map.of("selectionHash","hash-1"));meta.put("snapshotExpiresAt",EXPIRY.toEpochMilli());meta.put("totalRows",1);
            meta.put("pageIndex",0);meta.put("nextPageIndex",null);meta.put("completeness","PARTIAL");meta.put("collectionQuality",Map.of("status","UNKNOWN"));
            return ToolResult.success(Map.of("items",List.of(Map.of("linkId",1L)),"metrics",Map.of(),"meta",meta));
        }
    }
    static List<PlanningAssessment.CoverageBinding> coverage(String step){return List.of(new PlanningAssessment.CoverageBinding("delivery",List.of(new PlanningAssessment.EvidenceOutput(step,"pages"))));}
    static ClassPathResource resource(String name){return new ClassPathResource("sql/migration/"+name);}
}
