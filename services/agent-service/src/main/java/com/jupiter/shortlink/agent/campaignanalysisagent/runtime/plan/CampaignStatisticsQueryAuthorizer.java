package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignStatisticsCurrentInputAuthorizer.DateRange;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Checks the exact frozen current-group request against live account and resource ownership before I/O. */
public final class CampaignStatisticsQueryAuthorizer implements StatisticsJobFixedExecutor.QueryAuthorizer {
    private static final Set<String> REQUIRED = Set.of("requestId", "gid", "queryKind", "startDate", "endDate");
    private static final Set<String> OPTIONAL = Set.of("fullShortUrl");
    private final AgentAuthorityClient authority;

    public CampaignStatisticsQueryAuthorizer(AgentAuthorityClient authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @Override
    public boolean mayUse(AgentPrincipal current, String scopeRef, String periodsRef,
                          Map<String, Object> frozenRequest) {
        try {
            if (current == null || current.system() || frozenRequest == null
                    || !frozenRequest.keySet().containsAll(REQUIRED)
                    || !frozenRequest.keySet().stream().allMatch(key -> REQUIRED.contains(key) || OPTIONAL.contains(key)))
                return false;
            String gid = CampaignStatisticsCurrentInputAuthorizer.parseGroupId(scopeRef);
            DateRange period = CampaignStatisticsCurrentInputAuthorizer.parsePeriod(periodsRef);
            if (!gid.equals(frozenRequest.get("gid"))
                    || !period.startDate().toString().equals(frozenRequest.get("startDate"))
                    || !period.endDate().toString().equals(frozenRequest.get("endDate"))
                    || !(frozenRequest.get("requestId") instanceof String requestId)
                    || !requestId.matches("stat_[a-f0-9]{64}")
                    || !Set.of("METRICS", "LINK_METRICS").contains(frozenRequest.get("queryKind")))
                return false;
            Object urlValue = frozenRequest.get("fullShortUrl");
            if (frozenRequest.containsKey("fullShortUrl")
                    && (!(urlValue instanceof String url) || url.isBlank() || url.length() > 2048 || url.contains("://")))
                return false;
            AgentPrincipal verified = authority.verifyCurrentPrincipal(current);
            if (!current.equals(verified)) return false;
            if (urlValue == null) {
                var page = authority.resolveGroupMembersPage(current, gid, null, null);
                if (page == null) return false;
                page.requireMatches(new com.jupiter.shortlink.contract.GroupMembersPage.Request(gid, null, null),
                        current.tenantId(), current.username(), current.authVersion());
                return true;
            }
            String url = (String) urlValue;
            var resolved = authority.resolvePage(current, gid, url, null, null, null);
            if (!current.tenantId().equals(resolved.tenantId())
                    || resolved.nextCursor() != null || resolved.links().size() != 1) return false;
            Map<String, Object> link = resolved.links().get(0);
            return gid.equals(link.get("gid")) && ("https://" + url).equals(link.get("fullShortUrl"));
        } catch (RuntimeException denied) {
            return false;
        }
    }
}
