package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rechecks the frozen query contract and live resources immediately before statistics I/O.
 * The Skill must separately prove the original/selected shard's published artifact provenance.
 */
public final class CampaignDependencyAnalysisQueryAuthorizer implements StatisticsJobFixedExecutor.QueryAuthorizer {
    private static final Set<String> LINK_FIELDS = Set.of(
            "requestId", "gid", "queryKind", "startDate", "endDate", "scope");
    private static final Set<String> DIMENSION_FIELDS = Set.of(
            "requestId", "gid", "queryKind", "startDate", "endDate", "scope", "dimensions", "filters");
    private final AgentAuthorityClient authority;
    private final RunDefinition definition;
    private final FrozenCampaignRun frozen;
    private final CampaignDependencyAnalysisPlanFactory.Request request;
    private final CampaignDependencyAnalysisAuthorizer runAuthorizer;

    public CampaignDependencyAnalysisQueryAuthorizer(AgentAuthorityClient authority,
            CampaignDependencyAnalysisPlanFactory plans, RunDefinition definition) {
        this.authority = Objects.requireNonNull(authority);
        this.definition = Objects.requireNonNull(definition);
        this.frozen = FrozenCampaignRun.read(definition);
        if (!definition.runId().equals(frozen.inputs().runId())
                || !frozen.plan().inputSetRef().equals(frozen.inputs().inputSetRef()))
            throw new IllegalArgumentException("DEPENDENCY_QUERY_DEFINITION_INVALID");
        // Construction only parses the frozen query. Actual execution checks the current method
        // files below; inspecting a published artifact must not repeatedly resolve executable paths.
        this.request = Objects.requireNonNull(plans).inspectDefinition(frozen.inputs());
        this.runAuthorizer = new CampaignDependencyAnalysisAuthorizer(authority, plans);
    }

    @Override
    public boolean mayUse(AgentPrincipal current, String scopeRef, String periodsRef,
                          Map<String, Object> frozenRequest) {
        return authorize(current, scopeRef, periodsRef, frozenRequest, true);
    }

    /** Published evidence is not permission to execute the Skill again. Membership stays live. */
    public boolean mayReadEvidence(AgentPrincipal current, String scopeRef, String periodsRef,
                                  Map<String, Object> frozenRequest) {
        return authorize(current, scopeRef, periodsRef, frozenRequest, false);
    }

    private boolean authorize(AgentPrincipal current, String scopeRef, String periodsRef,
                              Map<String, Object> frozenRequest, boolean executing) {
        try {
            if (current == null || current.system() || frozenRequest == null
                    || !new AgentPrincipal(definition.caller().tenantId(), definition.caller().subject(),
                            definition.caller().authVersion(), false).equals(current)
                    || !request.gid().equals(frozenRequest.get("gid"))
                    || !(frozenRequest.get("requestId") instanceof String requestId)
                    || !(frozenRequest.get("scope") instanceof Map<?, ?> scopeValue)) return false;
            if ("baseline".equals(periodsRef)) {
                if (!request.baselineStart().equals(frozenRequest.get("startDate"))
                        || !request.baselineEnd().equals(frozenRequest.get("endDate"))) return false;
            } else if ("target".equals(periodsRef)) {
                if (!request.targetStart().equals(frozenRequest.get("startDate"))
                        || !request.targetEnd().equals(frozenRequest.get("endDate"))) return false;
            } else return false;

            FrozenQueryScope scope = FrozenQueryScope.fromMap(scopeValue);
            if (!scope.parentScopeRef().equals(scopeRef) || scope.linkIds().isEmpty()
                    || scope.linkIds().size() > FrozenQueryScope.SHARD_SIZE) return false;
            if ("LINK_METRICS".equals(frozenRequest.get("queryKind"))) {
                if (!LINK_FIELDS.equals(frozenRequest.keySet()) || !requestId.matches("decline_stat_[a-f0-9]{64}")
                        || !scopeRef.matches("scope-[a-f0-9]{64}")) return false;
            } else if ("DIMENSION_BREAKDOWN".equals(frozenRequest.get("queryKind"))) {
                if (!DIMENSION_FIELDS.equals(frozenRequest.keySet()) || !requestId.matches("dimension_stat_[a-f0-9]{64}")
                        || !scopeRef.matches("selected-scope-[a-f0-9]{64}")
                        || !request.dimensions().equals(frozenRequest.get("dimensions"))
                        || !request.filters().equals(frozenRequest.get("filters"))) return false;
            } else return false;

            // Both paths revalidate expiry, account and group. Only execution needs live method
            // files; evidence reads keep checking the exact published enumeration version.
            if (!(executing ? runAuthorizer.mayExecute(definition.caller(), frozen.inputs())
                    : runAuthorizer.mayReadEvidence(definition.caller(), frozen.inputs(), scope.enumerationVersion()))) return false;
            var resolved = authority.resolvePage(current, request.gid(), null, scope.linkIds(), null,
                    scope.enumerationVersion());
            if (resolved == null || !current.tenantId().equals(resolved.tenantId())
                    || !scope.enumerationVersion().equals(resolved.ownershipVersion())
                    || resolved.nextCursor() != null || resolved.links() == null
                    || resolved.links().size() != scope.linkIds().size()) return false;
            Set<Long> actual = new HashSet<>();
            for (Map<String, Object> link : resolved.links()) {
                if (link == null || !request.gid().equals(link.get("gid"))) return false;
                long id = positiveIntegralId(link.get("linkId"));
                if (!actual.add(id)) return false;
            }
            return actual.equals(new HashSet<>(scope.linkIds()));
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static long positiveIntegralId(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger))
            throw new IllegalArgumentException("DEPENDENCY_QUERY_MEMBER_INVALID");
        long id = new BigInteger(value.toString()).longValueExact();
        if (id <= 0) throw new IllegalArgumentException("DEPENDENCY_QUERY_MEMBER_INVALID");
        return id;
    }
}
