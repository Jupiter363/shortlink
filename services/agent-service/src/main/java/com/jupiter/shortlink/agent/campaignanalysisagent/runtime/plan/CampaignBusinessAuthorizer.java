package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.HashSet;
import java.util.Objects;

/** A compound request is authorized query by query, never merely because its caller matches. */
public final class CampaignBusinessAuthorizer implements PersistentPlanDriver.RunAuthorizer {
    private final AgentAuthorityClient authority;
    private final CampaignBusinessPlanFactory plans;

    public CampaignBusinessAuthorizer(AgentAuthorityClient authority, CampaignBusinessPlanFactory plans) {
        this.authority = Objects.requireNonNull(authority); this.plans = Objects.requireNonNull(plans);
    }

    public boolean mayExecute(Caller caller, FrozenInputSet inputs) {
        try {
            var parsed = plans.inspect(caller, inputs);
            var principal = principal(caller);
            if (!principal.equals(authority.verifyCurrentPrincipal(principal))) return false;
            var groups = new HashSet<String>();
            for (var query : parsed.queries()) {
                String gid = (String) query.request().get("gid");
                if (groups.add(gid) && !group(principal, gid, null)) return false;
                if (query.request().get("fullShortUrl") instanceof String url) {
                    var resolved = authority.resolvePage(principal, gid, url, null, null, null);
                    if (resolved == null || !principal.tenantId().equals(resolved.tenantId())
                            || resolved.nextCursor() != null || resolved.links() == null || resolved.links().size() != 1
                            || !gid.equals(resolved.links().get(0).get("gid"))
                            || !("https://" + url).equals(resolved.links().get(0).get("fullShortUrl"))) return false;
                }
            }
            for (var dependency : parsed.dependencies())
                if (groups.add(dependency.request().gid()) && !group(principal, dependency.request().gid(), null)) return false;
            return true;
        } catch (RuntimeException denied) { return false; }
    }

    public boolean mayReadEvidence(Caller caller, FrozenInputSet inputs, String gid, String enumerationVersion) {
        try {
            if (enumerationVersion == null || !enumerationVersion.matches("[a-f0-9]{64}")
                    || !mayExecute(caller, inputs)
                    || plans.inspect(caller, inputs).dependencies().stream().noneMatch(value -> value.request().gid().equals(gid)))
                return false;
            return group(principal(caller), gid, enumerationVersion);
        } catch (RuntimeException denied) { return false; }
    }

    public StepBindings.CurrentInputAuthorizer inputAuthorizer(FrozenInputSet inputs) {
        return (caller, type, value) -> mayExecute(caller, inputs)
                && inputs.inputContracts().entrySet().stream().anyMatch(entry -> type.equals(entry.getValue().type())
                && Objects.equals(value, inputs.inputValues().get(entry.getKey())));
    }

    private boolean group(AgentPrincipal principal, String gid, String expectedVersion) {
        GroupMembersPage page = authority.resolveGroupMembersPage(principal, gid, null, null);
        if (page == null) return false;
        page.requireMatches(new GroupMembersPage.Request(gid, null, null),
                principal.tenantId(), principal.username(), principal.authVersion());
        return expectedVersion == null || expectedVersion.equals(page.ownershipVersion());
    }

    static AgentPrincipal principal(Caller caller) {
        return new AgentPrincipal(caller.tenantId(), caller.subject(), caller.authVersion(), false);
    }
}
