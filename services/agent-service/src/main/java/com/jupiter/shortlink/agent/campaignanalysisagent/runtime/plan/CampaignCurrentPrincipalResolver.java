package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.Objects;

/**
 * Revalidates the current Admin account and the independently persisted conversation owner.
 * A stored Run header or client-provided session id alone is never an authority source.
 */
public final class CampaignCurrentPrincipalResolver implements CampaignRunIntake.CurrentPrincipalResolver {
    private final AgentAuthorityClient authority;
    private final JdbcCampaignConversationSessionOwner sessions;

    public CampaignCurrentPrincipalResolver(AgentAuthorityClient authority,
                                            JdbcCampaignConversationSessionOwner sessions) {
        this.authority = Objects.requireNonNull(authority);
        this.sessions = Objects.requireNonNull(sessions);
    }

    /** Call only from a trusted request entry, before registering the first durable Run. */
    public AgentPrincipal bindCurrent(AgentPrincipal expected, String sessionId) {
        AgentPrincipal current = authority.verifyCurrentPrincipal(expected);
        sessions.bindVerified(current, sessionId);
        sessions.requireOwner(caller(current), sessionId);
        return current;
    }

    /** Background recovery is read-only with respect to session ownership. */
    @Override
    public AgentPrincipal resolve(Caller expected, String sessionId) {
        Objects.requireNonNull(expected);
        sessions.requireOwner(expected, sessionId);
        AgentPrincipal current = authority.verifyCurrentPrincipal(
                new AgentPrincipal(expected.tenantId(), expected.subject(), expected.authVersion(), false));
        if (!expected.equals(caller(current)))
            throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        sessions.requireOwner(expected, sessionId);
        return current;
    }

    private static Caller caller(AgentPrincipal principal) {
        if (principal == null || principal.system())
            throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        return new Caller(principal.tenantId(), principal.username(), principal.authVersion());
    }
}
