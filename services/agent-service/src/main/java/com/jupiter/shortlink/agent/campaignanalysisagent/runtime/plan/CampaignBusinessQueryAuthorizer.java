package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact allowlist of frozen queries plus the existing proof-aware dynamic cohort gate. */
public final class CampaignBusinessQueryAuthorizer implements StatisticsJobFixedExecutor.QueryAuthorizer {
    private final RunDefinition definition;
    private final CampaignBusinessAuthorizer inputs;
    private final CampaignBusinessPlanFactory.Inspected frozen;
    private final List<CampaignDependencyAnalysisQueryAuthorizer> dependencies;

    public CampaignBusinessQueryAuthorizer(AgentAuthorityClient authority, CampaignBusinessPlanFactory plans,
            CampaignDependencyAnalysisPlanFactory dependencyPlans, CampaignBusinessAuthorizer inputs,
            RunDefinition definition) {
        this.definition = Objects.requireNonNull(definition); this.inputs = Objects.requireNonNull(inputs);
        this.frozen = plans.inspect(definition.caller(), FrozenCampaignRun.read(definition).inputs());
        this.dependencies = frozen.dependencies().stream().map(dependency ->
                new CampaignDependencyAnalysisQueryAuthorizer(authority, dependencyPlans, dependency.definition())).toList();
    }

    public boolean mayUse(AgentPrincipal current, String scopeRef, String periodsRef, Map<String,Object> request) {
        return authorize(current, scopeRef, periodsRef, request, true);
    }

    public boolean mayReadEvidence(AgentPrincipal current, String scopeRef, String periodsRef, Map<String,Object> request) {
        return authorize(current, scopeRef, periodsRef, request, false);
    }

    private boolean authorize(AgentPrincipal current, String scopeRef, String periodsRef, Map<String,Object> request,
                              boolean executing) {
        try {
            if (!CampaignBusinessAuthorizer.principal(definition.caller()).equals(current)
                    || !inputs.mayExecute(definition.caller(), FrozenCampaignRun.read(definition).inputs())) return false;
            if (request.containsKey("scope"))
                return dependencies.stream().anyMatch(gate -> executing ? gate.mayUse(current, scopeRef, periodsRef, request)
                        : gate.mayReadEvidence(current, scopeRef, periodsRef, request));
            if (!(request.get("requestId") instanceof String id) || !id.matches("stat_[a-f0-9]{64}")) return false;
            var body = new TreeMap<>(request); body.remove("requestId");
            return frozen.queries().stream().anyMatch(query -> query.scopeRef().equals(scopeRef)
                    && query.periodsRef().equals(periodsRef) && query.request().equals(body));
        } catch (RuntimeException denied) { return false; }
    }
}
