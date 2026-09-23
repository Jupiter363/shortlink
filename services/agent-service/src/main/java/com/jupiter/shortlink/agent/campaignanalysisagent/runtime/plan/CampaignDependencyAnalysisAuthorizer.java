package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.Map;
import java.util.Objects;

/** Live owner/group gate for the exact dependency-analysis input grammar. */
public final class CampaignDependencyAnalysisAuthorizer implements PersistentPlanDriver.RunAuthorizer {
    private final AgentAuthorityClient authority;
    private final CampaignDependencyAnalysisPlanFactory plans;

    public CampaignDependencyAnalysisAuthorizer(AgentAuthorityClient authority,
            CampaignDependencyAnalysisPlanFactory plans) {
        this.authority = Objects.requireNonNull(authority);
        this.plans = Objects.requireNonNull(plans);
    }

    @Override
    public boolean mayExecute(Caller caller, FrozenInputSet inputs) {
        return mayExecute(caller, inputs, null);
    }

    /** Execution rechecks the live approved method files as well as current account/group authority. */
    public boolean mayExecute(Caller caller, FrozenInputSet inputs, String expectedEnumerationVersion) {
        return authorize(caller, inputs, expectedEnumerationVersion, true);
    }

    /** Reading frozen evidence grants no Skill execution; source membership must still be current. */
    public boolean mayReadEvidence(Caller caller, FrozenInputSet inputs, String expectedEnumerationVersion) {
        return authorize(caller, inputs, expectedEnumerationVersion, false);
    }

    private boolean authorize(Caller caller, FrozenInputSet inputs, String expectedEnumerationVersion,
            boolean executing) {
        try {
            if (caller == null || inputs == null || (!executing && expectedEnumerationVersion == null)
                    || (expectedEnumerationVersion != null
                    && !expectedEnumerationVersion.matches("[a-f0-9]{64}"))) return false;
            var request = executing ? plans.inspect(inputs) : plans.inspectDefinition(inputs);
            AgentPrincipal expected = new AgentPrincipal(caller.tenantId(), caller.subject(),
                    caller.authVersion(), false);
            AgentPrincipal current = authority.verifyCurrentPrincipal(expected);
            if (!expected.equals(current)) return false;
            GroupMembersPage page = authority.resolveGroupMembersPage(current, request.gid(), null, null);
            return page != null && current.tenantId().equals(page.tenantId())
                    && current.username().equals(page.subjectId())
                    && current.authVersion() == page.authVersion()
                    && request.gid().equals(page.gid())
                    && (expectedEnumerationVersion == null || expectedEnumerationVersion.equals(page.ownershipVersion()));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    /** Bind every current-input decision to this immutable set and recheck live rights each time. */
    public StepBindings.CurrentInputAuthorizer inputAuthorizer(FrozenInputSet inputs) {
        Objects.requireNonNull(inputs);
        return (caller, type, frozenValue) -> {
            if (caller == null || type == null || frozenValue == null) return false;
            try {
                if (!mayExecute(caller, inputs)) return false;
                for (Map.Entry<String, Port> entry : inputs.inputContracts().entrySet()) {
                    if (type.equals(entry.getValue().type())
                            && Objects.equals(frozenValue, inputs.inputValues().get(entry.getKey()))) return true;
                }
                return false;
            } catch (RuntimeException denied) {
                return false;
            }
        };
    }
}
