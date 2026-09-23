package com.jupiter.shortlink.agent.harness.runtime;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import java.util.Objects;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Always-on downgrade fence, independent of the optional runner. It only denies an existing
 * durable identity; absence never grants access to legacy execution. No tasks or model calls.
 */
@Component
public final class CampaignRuntimeIdentityGuard {
    private final JdbcTemplate jdbc;
    private final AgentAuthorityClient authority;

    public CampaignRuntimeIdentityGuard(JdbcTemplate jdbc, AgentAuthorityClient authority) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.authority = Objects.requireNonNull(authority);
    }

    public boolean recordedRequest(AgentRunRequest request) {
        if (request.requestKey() == null || !schemaPresent()) return false;
        var expected = request.principal();
        if (expected == null || expected.system()) throw new SecurityException("CAMPAIGN_USER_PRINCIPAL_REQUIRED");
        var current = authority.verifyCurrentPrincipal(expected);
        if (!expected.equals(current)) throw new SecurityException("CAMPAIGN_PRINCIPAL_CHANGED");
        // Never use request.username, message text or HTTP body fields as the persisted owner.
        var caller = new Caller(current.tenantId(), current.username(), current.authVersion());
        var identity = JdbcCampaignRunIntakeStore.identity(caller, request.sessionId(), request.requestKey());
        String requestId = "request-" + identity.requestId().substring("intake-".length());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_public_request WHERE request_id=? AND run_id=?"
                        + " AND tenant_id=? AND subject_name=? AND session_id=? AND request_key=?",
                Integer.class, requestId, identity.runId(), caller.tenantId(), caller.subject(), request.sessionId(), request.requestKey());
        if (count == null) throw new IllegalStateException("CAMPAIGN_RUNTIME_IDENTITY_UNAVAILABLE");
        return count > 0;
    }

    /** No negative cache: applying the migration must take effect on the next request. */
    private boolean schemaPresent() {
        Boolean exists = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                    null, new String[] {"TABLE"})) {
                while (tables.next()) {
                    if ("campaign_public_request".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                }
                return false;
            }
        });
        // Metadata and query failures deliberately propagate; an outage cannot authorize downgrade.
        if (exists == null) throw new IllegalStateException("CAMPAIGN_RUNTIME_IDENTITY_UNAVAILABLE");
        return exists;
    }
}
