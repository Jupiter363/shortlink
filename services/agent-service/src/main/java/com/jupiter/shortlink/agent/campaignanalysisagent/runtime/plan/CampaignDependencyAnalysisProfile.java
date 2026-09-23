package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignScopeCollector;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Three existing native executors sharing their host intake's admission and recovery lifecycle. */
public final class CampaignDependencyAnalysisProfile {
    public static final String PROFILE_REF = "campaign-dependency-analysis";
    public static final String PROFILE_VERSION = "1";
    private static final CampaignPlanRuntimeRegistry.SaverKey SAVER_KEY =
            new CampaignPlanRuntimeRegistry.SaverKey("campaign-mysql-graph", "1");

    private final CampaignRunIntake.Profile profile;
    private final CampaignDependencyAnalysisArtifactAuthorizer artifacts;

    public CampaignDependencyAnalysisProfile(CampaignRunStore runs, CampaignStepStore steps,
            CampaignScopeStore scopes, CampaignStatisticsResultStore results,
            CampaignDeclineSelectionStore selections, CampaignDependencyAnalysisPlanFactory plans,
            Path approvedSkillsRoot, AgentAuthorityClient authority, ShortLinkBusinessGateway gateway,
            BaseCheckpointSaver saver) {
        Objects.requireNonNull(runs); Objects.requireNonNull(steps); Objects.requireNonNull(scopes);
        Objects.requireNonNull(results); Objects.requireNonNull(selections); Objects.requireNonNull(plans);
        Objects.requireNonNull(approvedSkillsRoot); Objects.requireNonNull(authority);
        Objects.requireNonNull(gateway); Objects.requireNonNull(saver);
        var runGate = new CampaignDependencyAnalysisAuthorizer(authority, plans);
        this.artifacts = new CampaignDependencyAnalysisArtifactAuthorizer(runs, scopes, runGate);
        var catalog = CampaignDependencyAnalysisPlanFactory.catalog();
        var contracts = CampaignDependencyAnalysisPlanFactory.contracts();
        var collector = new CampaignScopeCollector(runs, scopes, authority);
        this.profile = new CampaignRunIntake.Profile(PROFILE_REF, PROFILE_VERSION, catalog, contracts,
                runGate, artifacts, periodsGate(authority), context -> {
            var definition = context.token().definition();
            var frozen = FrozenCampaignRun.read(definition);
            var request = plans.inspect(frozen.inputs());
            var queryGate = new CampaignDependencyAnalysisQueryAuthorizer(authority, plans, definition);
            var collection = new ScopeCollectionFixedExecutor(context.token(), context.principal(),
                    runs, steps, scopes, collector,
                    (principal, gid) -> context.principal().equals(principal) && request.gid().equals(gid)
                            && context.runAuthorizer().mayExecute(definition.caller(), frozen.inputs()),
                    context.artifactAuthorizer());
            var selection = new DeclineSelectionSkill(context.token(), context.principal(), approvedSkillsRoot,
                    runs, steps, scopes, results, selections, gateway, queryGate,
                    context.artifactAuthorizer(), FrozenDeclineSelection.REF_V2);
            var dimension = new DimensionChangeSkill(context.token(), context.principal(), approvedSkillsRoot,
                    runs, steps, selections, results, gateway, queryGate,
                    context.artifactAuthorizer(), FrozenDimensionChange.REF_V2);
            if (!collection.authorized() || !selection.authorized() || !dimension.authorized())
                throw new SecurityException("DEPENDENCY_EXECUTION_ACCESS_DENIED");
            collection.prepareRecovery();
            selection.prepareRecovery();
            dimension.prepareRecovery();
            var registry = new CampaignPlanRuntimeRegistry(new CampaignPlanRuntimeRegistry.Versions(
                    catalog.version(), "dependency-artifacts/v1", "dependency-executors/v1",
                    FrozenCampaignRun.RUNNER, FrozenCampaignRun.TOPOLOGY), catalog, contracts,
                    List.of(collection.registration(), selection.registration(), dimension.registration()),
                    List.of(), null, SAVER_KEY);
            var prepared = new CampaignPlanRuntimeFactory(registry).open(new CampaignPlanRuntimeFactory.Request(
                    definition.caller(), definition.sessionId(), context.token(), runs, steps,
                    context.runAuthorizer(), context.artifactAuthorizer(), context.inputAuthorizer(), context.scope()));
            var compiled = prepared.compile(new CampaignPlanRuntimeRegistry.SaverBinding(SAVER_KEY, saver));
            var targets = new LinkedHashMap<String, StatisticsJobResultReceiver.Target>(selection.resultTargets());
            dimension.resultTargets().forEach((id, target) -> {
                if (targets.putIfAbsent(id, target) != null)
                    throw new IllegalArgumentException("DEPENDENCY_RESULT_TARGET_CONFLICT");
            });
            return new CampaignRecoveryCoordinator.Runtime(compiled.driver(), compiled.graph(),
                    targets, context.artifactAuthorizer());
        });
    }

    public CampaignRunIntake.Profile profile() { return profile; }
    public CampaignDependencyAnalysisArtifactAuthorizer artifactAuthorizer() { return artifacts; }

    // The run gate validates the whole exact frozen set (including group, expiry and pins).
    // Period values themselves carry no resource grant. StepBindings resolves them from that set.
    private static StepBindings.CurrentInputAuthorizer periodsGate(AgentAuthorityClient authority) {
        return (caller, type, value) -> {
            try {
                if (caller == null || !FrozenDeclineSelection.INPUTS_V2.get("periods").type().equals(type)
                        || !(value instanceof String ref)) return false;
                String[] parts = ref.split(":", -1);
                if (parts.length != 5 || !"period-pair.v1".equals(parts[0])) return false;
                var baseline = new CampaignParentCoverage.Period("baseline", parts[1], parts[2], "Asia/Shanghai");
                var target = new CampaignParentCoverage.Period("target", parts[3], parts[4], "Asia/Shanghai");
                if (!LocalDate.parse(baseline.endDate()).isBefore(LocalDate.parse(target.startDate()))) return false;
                var principal = new AgentPrincipal(caller.tenantId(), caller.subject(), caller.authVersion(), false);
                return principal.equals(authority.verifyCurrentPrincipal(principal));
            } catch (RuntimeException denied) { return false; }
        };
    }
}
