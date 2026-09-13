package com.jupiter.shortlink.agent.riskprofile.source;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.List;
import java.util.Map;

/** Rehydrates a current owner for one persisted job scope; never trusts stored credentials. */
@Component
public class ScheduledRiskPrincipalClient {
    private final AgentProperties properties;
    private final BoundedHttpTransport transport;
    private final AgentAuthorityClient authority;

    public ScheduledRiskPrincipalClient(AgentProperties properties, BoundedHttpTransport transport,
            AgentAuthorityClient authority) {
        this.properties = properties;
        this.transport = transport;
        this.authority = authority;
    }

    public AgentPrincipal resolve(String tenantId, String gid) {
        if (tenantId == null || !tenantId.matches("[1-9][0-9]{0,18}")
                || gid == null || !gid.matches("[A-Za-z0-9_-]{1,64}"))
            throw new SecurityException("A persisted tenant and exact group are required");
        var uri = UriComponentsBuilder.fromHttpUrl(properties.getBusiness().getBaseUrl().replaceAll("/+$", "")
                + "/internal/short-link-admin/v1/agent-tools/risk/scheduled-scope")
                .queryParam("tenantId", tenantId).queryParam("gid", gid).build().encode().toUri();
        Map<String, Object> response = transport.exchange("GET", uri,
                authority.headers(AgentPrincipal.system(properties.getBusiness().getUsername())), null);
        if (response == null || !"0".equals(String.valueOf(response.get("code")))
                || !(response.get("data") instanceof Map<?, ?> data)
                || !tenantId.equals(data.get("tenantId")) || !List.of(gid).equals(data.get("gids")))
            throw new SecurityException("Scheduled profile ownership could not be authorized");
        if (!(data.get("username") instanceof String username))
            throw new SecurityException("Scheduled owner identity is missing");
        return new AgentPrincipal(tenantId, username, StatsEvidence.number(data.get("authVersion")), false);
    }
}
