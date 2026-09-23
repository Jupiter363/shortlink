package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignScopeCollector;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignReplanStatisticsRecovery;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One business profile composes the established fixed and native ReAct adapters under one intake. */
public final class CampaignBusinessProfile {
    public static final String REF = "campaign-business";
    public static final String VERSION = "1";
    private static final CampaignPlanRuntimeRegistry.SaverKey SAVER = new CampaignPlanRuntimeRegistry.SaverKey("campaign-mysql-graph", "1");
    private final CampaignRunIntake.Profile profile;
    private final CampaignBusinessArtifactAuthorizer artifacts;
    public record Recovery(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock, JdbcCampaignRunStore runs) {}

    public CampaignBusinessProfile(CampaignRunStore runs, CampaignStepStore steps, CampaignScopeStore scopes,
            CampaignStatisticsResultStore results, CampaignDeclineSelectionStore selections,
            CampaignExplorationCandidateStore candidates, CampaignBusinessPlanFactory plans,
            CampaignDependencyAnalysisPlanFactory dependencyPlans, Path approvedSkillsRoot, AgentAuthorityClient authority,
            ShortLinkBusinessGateway gateway, BaseCheckpointSaver saver, CampaignExplorationRuntimeFactory exploration) {
        this(runs,steps,scopes,results,selections,candidates,plans,dependencyPlans,approvedSkillsRoot,authority,gateway,saver,exploration,null);
    }

    public CampaignBusinessProfile(CampaignRunStore runs, CampaignStepStore steps, CampaignScopeStore scopes,
            CampaignStatisticsResultStore results, CampaignDeclineSelectionStore selections,
            CampaignExplorationCandidateStore candidates, CampaignBusinessPlanFactory plans,
            CampaignDependencyAnalysisPlanFactory dependencyPlans, Path approvedSkillsRoot, AgentAuthorityClient authority,
            ShortLinkBusinessGateway gateway, BaseCheckpointSaver saver, CampaignExplorationRuntimeFactory exploration, Recovery recovery) {
        var inputGate = new CampaignBusinessAuthorizer(authority, plans);
        var consumers = recovery == null ? null : new JdbcCampaignStatisticsConsumerStore(recovery.jdbc(),recovery.transactions(),recovery.clock(),recovery.runs());
        var consumerGrant = recovery == null ? null : CampaignReplanStatisticsRecovery.consumerAuthorizer(recovery.jdbc(),
                definition -> new CampaignBusinessQueryAuthorizer(authority,plans,dependencyPlans,inputGate,definition));
        var receiver = recovery == null ? null : new StatisticsJobResultReceiver(runs,results,gateway,recovery.clock(),consumers);
        artifacts = new CampaignBusinessArtifactAuthorizer(runs, scopes, plans, dependencyPlans, inputGate, authority, exploration,
                recovery == null ? null : CampaignReplanStatisticsRecovery.artifactAuthorizer(recovery.jdbc(),runs,consumers,consumerGrant));
        var catalog = plans.catalog(); var contracts = plans.contracts();
        var collector = new CampaignScopeCollector(runs, scopes, authority);
        profile = new CampaignRunIntake.Profile(REF, VERSION, catalog, contracts, inputGate, artifacts,
                referenceGate(authority), context -> {
            var definition = context.token().definition();
            var parsed = plans.validateDefinition(definition);
            var frozen = FrozenCampaignRun.read(definition);
            var queryGate = new CampaignBusinessQueryAuthorizer(authority, plans, dependencyPlans, inputGate, definition);
            var statistics = new StatisticsJobFixedExecutor(definition, context.principal(), gateway, queryGate);
            var adopted = recovery == null ? null : CampaignReplanStatisticsRecovery.open(recovery.jdbc(),recovery.transactions(),recovery.clock(),
                    context.token(),context.principal(),runs,steps,consumers,receiver,consumerGrant,gateway,queryGate);
            var collection = new ScopeCollectionFixedExecutor(context.token(), context.principal(), runs, steps, scopes,
                    collector, (principal, gid) -> principal.equals(context.principal())
                    && parsed.dependencies().stream().anyMatch(value -> value.request().gid().equals(gid))
                    && context.runAuthorizer().mayExecute(definition.caller(), frozen.inputs()), context.artifactAuthorizer());
            // The business menu offers several independent scenarios. Instantiate fixed Skills only
            // when this exact frozen plan uses them; their required-step/pin checks remain strict.
            var selection = uses(frozen, FrozenDeclineSelection.REF_V2)
                    ? new DeclineSelectionSkill(context.token(), context.principal(), approvedSkillsRoot,
                        runs, steps, scopes, results, selections, gateway, queryGate, context.artifactAuthorizer(), FrozenDeclineSelection.REF_V2)
                    : null;
            var dimension = uses(frozen, FrozenDimensionChange.REF_V2)
                    ? new DimensionChangeSkill(context.token(), context.principal(), approvedSkillsRoot,
                        runs, steps, selections, results, gateway, queryGate, context.artifactAuthorizer(), FrozenDimensionChange.REF_V2)
                    : null;
            if (!statistics.authorized() || !collection.authorized()
                    || selection != null && !selection.authorized() || dimension != null && !dimension.authorized())
                throw new SecurityException("BUSINESS_EXECUTION_ACCESS_DENIED");
            collection.prepareRecovery();
            if (selection != null) selection.prepareRecovery();
            if (dimension != null) dimension.prepareRecovery();
            if (adopted != null) adopted.prepareRecovery();
            var localExploration = exploration.open(context.token(), context.principal(), catalog, contracts,
                    context.inputAuthorizer(), context.artifactAuthorizer(), queryGate, context.scope(), parsed.expiresAt());
            List<PersistentPlanDriver.FixedExecutor> executors = new ArrayList<>(List.of(
                    adopted == null ? statistics.registration() : adopted.registration(statistics), collection.registration()));
            if (selection != null) executors.add(selection.registration());
            if (dimension != null) executors.add(dimension.registration());
            var registry = new CampaignPlanRuntimeRegistry(new CampaignPlanRuntimeRegistry.Versions(catalog.version(),
                    "business-artifacts/v1", "business-executors/v1", FrozenCampaignRun.RUNNER, FrozenCampaignRun.TOPOLOGY),
                    catalog, contracts, executors,
                    localExploration.registrations(), candidates, SAVER);
            var compiled = new CampaignPlanRuntimeFactory(registry).open(new CampaignPlanRuntimeFactory.Request(
                    definition.caller(), definition.sessionId(), context.token(), runs, steps, context.runAuthorizer(),
                    context.artifactAuthorizer(), context.inputAuthorizer(), context.scope()))
                    .compile(new CampaignPlanRuntimeRegistry.SaverBinding(SAVER, saver));
            var targets = new LinkedHashMap<String,StatisticsJobResultReceiver.Target>();
            for (var source : List.of(statistics.resultTargets(),
                    selection == null ? Map.<String,StatisticsJobResultReceiver.Target>of() : selection.resultTargets(),
                    dimension == null ? Map.<String,StatisticsJobResultReceiver.Target>of() : dimension.resultTargets(),
                    localExploration.resultTargets()))
                source.forEach((id, target) -> {
                    if (targets.putIfAbsent(id, target) != null) throw new IllegalArgumentException("BUSINESS_RESULT_TARGET_CONFLICT");
                });
            return new CampaignRecoveryCoordinator.Runtime(compiled.driver(), compiled.graph(), targets, context.artifactAuthorizer());
        }, plans::validateDefinition, plans::normalizeProposal);
    }

    public CampaignRunIntake.Profile profile() { return profile; }
    public CampaignBusinessArtifactAuthorizer artifactAuthorizer() { return artifacts; }

    private static boolean uses(FrozenCampaignRun frozen, PlanSpec.ExecutorRef executor) {
        return frozen.plan().steps().stream().anyMatch(step -> step.executionMode() == PlanSpec.ExecutionMode.FIXED
                && executor.equals(step.executor()));
    }

    private static StepBindings.CurrentInputAuthorizer referenceGate(AgentAuthorityClient authority) {
        var statistics = new CampaignStatisticsCurrentInputAuthorizer(authority);
        return (caller, type, value) -> {
            if (statistics.mayUse(caller, type, value)) return true;
            try {
                if (!FrozenStatisticsJobQuery.PERIODS_TYPE.equals(type) || !(value instanceof String pair)) return false;
                String[] parts = pair.split(":", -1);
                if (parts.length != 5 || !"period-pair.v1".equals(parts[0])) return false;
                CampaignStatisticsCurrentInputAuthorizer.parsePeriod("period.v1:" + parts[1] + ":" + parts[2]);
                CampaignStatisticsCurrentInputAuthorizer.parsePeriod("period.v1:" + parts[3] + ":" + parts[4]);
                if (!LocalDate.parse(parts[2]).isBefore(LocalDate.parse(parts[3]))) return false;
                var expected = CampaignBusinessAuthorizer.principal(caller);
                return expected.equals(authority.verifyCurrentPrincipal(expected));
            } catch (RuntimeException denied) { return false; }
        };
    }
}
