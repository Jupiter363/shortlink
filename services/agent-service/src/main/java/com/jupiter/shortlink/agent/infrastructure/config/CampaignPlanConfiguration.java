package com.jupiter.shortlink.agent.infrastructure.config;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportNarrativeSynthesizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.conversation.JdbcCampaignConversationTurnStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit opt-in for the complete campaign path. Existing sessions are never silently rerouted. */
@Configuration(proxyBeanMethods=false)
@Profile("campaign-plan-v2")
public class CampaignPlanConfiguration {
    @Bean(destroyMethod="shutdown")
    public ExecutorService campaignExplorationCallbacks(
            @Value("${short-link.agent.campaign-statistics.active-advances:4}") int active) {
        return new ThreadPoolExecutor(active,active,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),task->{
            Thread thread=new Thread(task,"campaign-exploration-callback");thread.setDaemon(false);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    public CampaignStatisticsFixedRuntime.ExtensionFactory campaignBusinessExtension(ChatModel configuredModel,
            @Qualifier("campaignExplorationCallbacks") ExecutorService callbacks,
            @Value("${short-link.agent.campaign-statistics.dependency-skills-root:}") String skillsRoot) {
        if (skillsRoot.isBlank()) throw new IllegalArgumentException("CAMPAIGN_APPROVED_SKILLS_ROOT_REQUIRED");
        Path root=Path.of(skillsRoot);
        ChatModel model=campaignModel(configuredModel);
        return context -> {
            var dependencies=new CampaignDependencyAnalysisPlanFactory(root,context.clock());
            var plans=new CampaignBusinessPlanFactory(dependencies,context.clock());
            var catalog=plans.catalog();var contracts=plans.contracts();
            var options=model.getDefaultOptions();
            String modelName=options==null?"configured":Objects.toString(options.getModel(),"configured");
            String configuration=CampaignRunStore.sha256(List.of("campaign-business-model/v1",modelName,
                    plans.menu().configurationId(),options==null?"default":Objects.toString(options.getMaxTokens(),"default"),
                    options==null?"default":Objects.toString(options.getTemperature(),"default"))
                    .stream().map(value->value.length()+":"+value).collect(java.util.stream.Collectors.joining()));
            var limits=ModelInvocationRegistry.Limits.defaults();
            var models=new ModelInvocationRegistry(List.of(new ModelInvocationRegistry.Contract("campaign-model","1",configuration,
                    invocation->Set.of(NativeCampaignPlanner.POLICY_REF,CampaignExplorationRuntimeFactory.STATISTICS_POLICY)
                            .contains(invocation.policyRef()) && "1".equals(invocation.policyVersion())
                            && invocation.expiresAt().isAfter(context.clock().instant()))),limits);
            var scopes=new JdbcCampaignScopeStore(context.jdbc(),context.transactions(),context.clock(),context.runs());
            var selections=new JdbcCampaignDeclineSelectionStore(context.jdbc(),context.transactions(),context.clock(),context.runs());
            var calls=new JdbcCampaignExplorationCallStore(context.jdbc(),context.transactions(),context.clock());
            var artifacts=new AtomicReference<CampaignRunStore.ArtifactAuthorizer>();
            CampaignRunStore.ArtifactAuthorizer gate=(caller,value)->artifacts.get()!=null&&artifacts.get().mayRead(caller,value);
            var candidates=new JdbcCampaignExplorationCandidateStore(context.jdbc(),context.transactions(),context.clock(),
                    context.runs(),context.steps(),models,catalog,contracts,
                    new CompletionCriterionRegistry(List.of(CampaignExplorationRuntimeFactory.statisticsCompletionCheck())),gate);
            var skills=new JdbcCampaignSkillInvocationStore(context.jdbc(),context.transactions(),context.clock(),catalog,contracts,true);
            var exploration=new CampaignExplorationRuntimeFactory(context.jdbc(),context.transactions(),context.clock(),
                    context.runs(),context.steps(),calls,context.results(),candidates,skills,scopes,selections,root,
                    context.gateway(),models,model,context.saver(),callbacks,new CampaignExplorationRuntimeFactory.Settings(
                    "campaign-model","1",configuration,new NativeExplorationAdapter.Limits(2,256000,64000,256000,64,Duration.ofSeconds(60)),
                    ExplorationBudgetPolicy.defaults(),ExplorationRepeatPolicy.disabled()));
            var business=new CampaignBusinessProfile(context.runs(),context.steps(),scopes,context.results(),selections,
                    candidates,plans,dependencies,root,context.authority(),context.gateway(),context.saver(),exploration,
                    new CampaignBusinessProfile.Recovery(context.jdbc(),context.transactions(),context.clock(),context.runs()));
            artifacts.set(business.artifactAuthorizer());
            var planning=new CampaignRunIntake.PlanningProfile(CampaignBusinessProfile.REF,CampaignBusinessProfile.VERSION,
                    plans.menu(),model,models,"campaign-model","1",configuration,limits,PlanningProposal.Limits.defaults());
            return new CampaignStatisticsFixedRuntime.Extension(business.profile(),planning,plans::prepare);
        };
    }

    @Bean
    public CampaignPublicRequestStore campaignPublicRequests(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate tx,
            @Qualifier("campaignStatisticsClock") Clock clock) { return new CampaignPublicRequestStore(jdbc,tx,clock); }

    @Bean
    public CampaignDueWorkStore campaignDueWork(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate tx,
            @Qualifier("campaignStatisticsClock") Clock clock) { return new CampaignDueWorkStore(jdbc,tx,clock); }

    @Bean
    public CampaignReportDeliveryService campaignReports(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate tx,
            @Qualifier("campaignStatisticsClock") Clock clock,CampaignStatisticsFixedRuntime runtime,ChatModel configuredModel,
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient authority) {
        ChatModel model=campaignModel(configuredModel);
        var artifacts=runtime.extension().profile().artifactAuthorizer();
        CampaignReportDeliveryService.RunAccess access=(caller,definition)-> {
            try {
                runtime.principals().resolve(caller,definition.sessionId());
                return caller.equals(definition.caller()) && runtime.extension().profile().runAuthorizer()
                        .mayExecute(caller,FrozenCampaignRun.read(definition).inputs());
            } catch (RuntimeException denied) { return false; }
        };
        var rows=new CampaignArtifactReportRows(runtime.runs(),runtime.results(),
                new JdbcCampaignDeclineSelectionStore(jdbc,tx,clock,runtime.runs()));
        return new CampaignReportDeliveryService(jdbc,tx,clock,runtime.runs(),runtime.steps(),
                new JdbcReportLifecycleStore(jdbc,tx,clock),artifacts,access,rows,
                new CampaignReportNarrativeSynthesizer(runtime.runs(),new JdbcCampaignReportSynthesisStore(jdbc,tx,clock),
                        model,artifacts,access::mayRead,ModelInvocationRegistry.Limits.defaults()),
                new CampaignSealedReportAccess(jdbc,clock,runtime.runs(),authority,access));
    }

    @Bean
    public CampaignBusinessReplanService campaignReplanService(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate tx,
            @Qualifier("campaignStatisticsClock") Clock clock,CampaignStatisticsFixedRuntime runtime,
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient authority) {
        var profile=runtime.extension().profile();
        var planning=runtime.extension().planning();
        var plans=new CampaignBusinessPlanFactory(runtime.dependencyPlans(),clock);
        var inputs=new CampaignBusinessAuthorizer(authority,plans);
        var candidates=new JdbcCampaignExplorationCandidateStore(jdbc,tx,clock,runtime.runs(),runtime.steps(),
                planning.models(),profile.catalog(),profile.contracts(),
                new CompletionCriterionRegistry(List.of(CampaignExplorationRuntimeFactory.statisticsCompletionCheck())),
                profile.artifactAuthorizer());
        var consumers=CampaignReplanStatisticsRecovery.consumerAuthorizer(jdbc,definition->
                new CampaignBusinessQueryAuthorizer(authority,plans,runtime.dependencyPlans(),inputs,definition));
        return new CampaignBusinessReplanService(jdbc,tx,clock,runtime.runs(),candidates,planning.models(),profile,
                runtime.principals(),consumers,planning.menu());
    }

    @Bean
    public CampaignPublicRequestService campaignRequestService(JdbcTemplate jdbc,
            @Qualifier("campaignStatisticsTransactionTemplate") TransactionTemplate tx,
            @Qualifier("campaignStatisticsClock") Clock clock,CampaignStatisticsFixedRuntime runtime,
            CampaignPublicRequestStore requests,CampaignDueWorkStore due,CampaignReportDeliveryService reports,ChatModel configuredModel,
            CampaignBusinessReplanService replans) {
        ChatModel model=campaignModel(configuredModel);
        var turns=new JdbcCampaignConversationTurnStore(jdbc,tx,new JdbcCampaignConversationSessionOwner(jdbc,tx,clock),clock);
        var service=new CampaignPublicRequestService(requests,turns,runtime.principals(),runtime.intake(),model,
                runtime.extension().inputs(),CampaignBusinessProfile.REF,CampaignBusinessProfile.VERSION,due::schedule,
                new CampaignPublicDeliveryAdapter(runtime,reports,requests),clock,Duration.ofHours(24),due);
        service.installWake(due::wake);
        service.installCancellation((principal,session,reference)->{
            var caller=new CampaignRunStore.Caller(principal.tenantId(),principal.username(),principal.authVersion());
            if (!principal.equals(runtime.principals().resolve(caller,session)))
                throw new SecurityException("CAMPAIGN_CANCEL_ACCESS_DENIED");
            requests.cancel(caller,session,reference,runtime.runs());
        });
        runtime.intake().installPreparation(service);
        runtime.intake().installRevisionResolver(new CampaignReplanDefinitionResolver(jdbc,tx,clock)::resolve);
        runtime.intake().observeAdvances((caller,runId,scope)->runtime.runs().loadRun(caller,runId)
                .filter(run->FrozenCampaignRun.read(run.definition()).assessment().capabilityCatalogVersion()
                        .equals(runtime.extension().profile().catalog().version()))
                .ifPresent(run->{
                    var planning=runtime.extension().planning();
                    try {
                        replans.advanceCurrent(runtime.principals().resolve(caller,run.definition().sessionId()),
                                run.definition().sessionId(),runId,scope,model,new CampaignBusinessReplanService.ModelSettings(
                                        planning.modelRef(),planning.modelVersion(),planning.configurationHash(),
                                        planning.modelLimits(),planning.proposalLimits()));
                    } catch (Exception failed) { throw new IllegalStateException("CAMPAIGN_REPLAN_UNRESOLVED",failed); }
                    runtime.runs().loadRun(caller,runId).ifPresent(current->reports.publishCurrent(caller,current.token(),scope));
                }));
        return service;
    }

    @Bean
    public CampaignSessionRecoveryService campaignSessionRecoveryService(JdbcTemplate jdbc,
            CampaignStatisticsFixedRuntime runtime, com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient authority) {
        return new CampaignSessionRecoveryService(jdbc, authority, runtime.principals());
    }

    private static ChatModel campaignModel(ChatModel configured) {
        return configured instanceof com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekSpringAiChatModel deepSeek
                ? deepSeek.forCampaignPlan() : configured;
    }

    @Bean(initMethod="start",destroyMethod="close")
    public CampaignPublicWorkScheduler campaignWorkScheduler(JdbcTemplate jdbc,CampaignPublicRequestStore requests,
            CampaignDueWorkStore due,CampaignStatisticsFixedRuntime runtime,CampaignPublicRequestService initialized,
            @Qualifier("campaignStatisticsClock") Clock clock) {
        return new CampaignPublicWorkScheduler(jdbc,requests,due,runtime.intake(),clock);
    }
}
