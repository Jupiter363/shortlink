package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsQueryPlan;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Current run gate for the frozen comparison/ranking input grammar, separate from session ownership. */
public final class CampaignStatisticsRunAuthorizer implements PersistentPlanDriver.RunAuthorizer {
    private static final Set<String> QUERY_FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef",
            "scopeKind", "gid", "startDate", "endDate", "businessTimezone", "queryKind");
    private static final Set<String> COMPARISON_FIELDS = Set.of("schemaVersion", "kind", "queryPlanId");
    private static final Set<String> RANKING_FIELDS = Set.of("schemaVersion", "kind", "queryPlanId", "metric", "limit");
    private static final Set<String> RANKING_METRICS = Set.of("pv", "uv", "uip");
    private static final Port OPERATION_PORT = new Port(CampaignStatisticsPlanFactory.OPERATION_TYPE, true);

    private record ScopeKey(String gid, String fullShortUrl) {}
    private record PeriodKey(String startDate, String endDate) {}
    private record Query(String gid, String fullShortUrl, String startDate, String endDate) {
        ScopeKey scope() { return new ScopeKey(gid, fullShortUrl); }
        PeriodKey period() { return new PeriodKey(startDate, endDate); }
    }
    private record Parsed(List<Query> queries) {}

    private final AgentAuthorityClient authority;

    public CampaignStatisticsRunAuthorizer(AgentAuthorityClient authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @Override
    public boolean mayExecute(Caller caller, FrozenInputSet inputs) {
        try {
            Parsed parsed = parse(inputs);
            AgentPrincipal expected = new AgentPrincipal(caller.tenantId(), caller.subject(), caller.authVersion(), false);
            AgentPrincipal current = authority.verifyCurrentPrincipal(expected);
            if (!expected.equals(current)) return false;
            Map<String, GroupMembersPage> groups = new LinkedHashMap<>();
            Set<ScopeKey> checkedUrls = new LinkedHashSet<>();
            for (Query query : parsed.queries()) {
                GroupMembersPage page = groups.get(query.gid());
                if (page == null) {
                    page = authority.resolveGroupMembersPage(current, query.gid(), null, null);
                    if (page == null || !current.tenantId().equals(page.tenantId())
                            || !current.username().equals(page.subjectId())
                            || current.authVersion() != page.authVersion()
                            || !query.gid().equals(page.gid())) return false;
                    groups.put(query.gid(), page);
                }
                if (!query.fullShortUrl().isEmpty() && checkedUrls.add(query.scope())) {
                    var resolved = authority.resolvePage(current, query.gid(), query.fullShortUrl(), null,
                            null, page.ownershipVersion());
                    if (resolved == null || !current.tenantId().equals(resolved.tenantId())
                            || !page.ownershipVersion().equals(resolved.ownershipVersion())
                            || resolved.nextCursor() != null || resolved.links().size() != 1) return false;
                    Map<String, Object> link = resolved.links().get(0);
                    if (!query.gid().equals(link.get("gid"))
                            || !("https://" + query.fullShortUrl()).equals(link.get("fullShortUrl"))) return false;
                }
            }
            return true;
        } catch (RuntimeException denied) {
            return false;
        }
    }

    private static Parsed parse(FrozenInputSet inputs) {
        require(inputs != null && inputs.inputValues() != null && inputs.inputContracts() != null);
        Map<String, Object> values = inputs.inputValues();
        Map<String, Port> contracts = inputs.inputContracts();
        require(values.size() >= 4 && values.size() <= 49 && (values.size() - 1) % 3 == 0
                && values.keySet().equals(contracts.keySet())
                && OPERATION_PORT.equals(contracts.get("operation")));
        int count = (values.size() - 1) / 3;
        Map<String, Object> operation = object(values.get("operation"));
        require(CampaignStatisticsPlanFactory.OPERATION_SCHEMA.equals(operation.get("schemaVersion")));
        String kind = string(operation.get("kind"));
        String queryPlanId = string(operation.get("queryPlanId"));
        require(queryPlanId.matches("[a-f0-9]{64}"));
        String expectedQueryKind;
        if ("COMPARISON".equals(kind)) {
            require(operation.keySet().equals(COMPARISON_FIELDS) && count >= 2);
            expectedQueryKind = "METRICS";
        } else if ("RANKING".equals(kind)) {
            require(operation.keySet().equals(RANKING_FIELDS) && count == 1);
            expectedQueryKind = "LINK_METRICS";
        } else throw invalid();

        List<Query> queries = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String scopeKey = "scope-" + index, periodsKey = "periods-" + index,
                    queryKey = "query-" + index;
            require(FrozenStatisticsJobQuery.INPUTS.get("scope").equals(contracts.get(scopeKey))
                    && FrozenStatisticsJobQuery.INPUTS.get("periods").equals(contracts.get(periodsKey))
                    && FrozenStatisticsJobQuery.INPUTS.get("query").equals(contracts.get(queryKey)));
            String scopeRef = string(values.get(scopeKey));
            String periodsRef = string(values.get(periodsKey));
            Map<String, Object> descriptor = object(values.get(queryKey));
            Set<String> allowed = new LinkedHashSet<>(QUERY_FIELDS);
            if (descriptor.containsKey("fullShortUrl")) allowed.add("fullShortUrl");
            require(descriptor.keySet().equals(allowed)
                    && FrozenStatisticsJobQuery.SCHEMA.equals(descriptor.get("schemaVersion"))
                    && "CURRENT_GROUP".equals(descriptor.get("scopeKind"))
                    && "Asia/Shanghai".equals(descriptor.get("businessTimezone"))
                    && expectedQueryKind.equals(descriptor.get("queryKind")));
            String gid = string(descriptor.get("gid"));
            require(gid.matches("[A-Za-z0-9_-]{1,64}"));
            String start = string(descriptor.get("startDate"));
            String end = string(descriptor.get("endDate"));
            String url = descriptor.containsKey("fullShortUrl") ? string(descriptor.get("fullShortUrl")) : "";
            require(scopeRef.equals("current-group.v1:" + gid)
                    && periodsRef.equals("period.v1:" + start + ":" + end)
                    && !url.contains("://"));
            FrozenStatisticsJobQuery.prepareRequest(scopeRef, periodsRef, descriptor);
            queries.add(new Query(gid, url, start, end));
        }
        require(values.size() == 1 + 3 * queries.size());
        if ("COMPARISON".equals(kind)) comparison(queries, queryPlanId);
        else ranking(operation, queries.get(0), queryPlanId);
        return new Parsed(List.copyOf(queries));
    }

    private static void comparison(List<Query> queries, String queryPlanId) {
        Map<ScopeKey, Map<String, Object>> scopes = new LinkedHashMap<>();
        Map<PeriodKey, Map<String, Object>> periods = new LinkedHashMap<>();
        for (Query query : queries) {
            scopes.putIfAbsent(query.scope(), Map.of("gid", query.gid(),
                    "fullShortUrl", query.fullShortUrl()));
            periods.putIfAbsent(query.period(), Map.of("startDate", query.startDate(),
                    "endDate", query.endDate()));
        }
        var plan = CampaignStatisticsQueryPlan.create(List.copyOf(scopes.values()), List.copyOf(periods.values()));
        require(queryPlanId.equals(plan.planId()) && plan.queries().size() == queries.size());
        for (int index = 0; index < queries.size(); index++) {
            Query actual = queries.get(index);
            var expected = plan.queries().get(index);
            require(actual.gid().equals(expected.scope().gid())
                    && actual.fullShortUrl().equals(expected.scope().fullShortUrl())
                    && actual.startDate().equals(expected.period().startDate().toString())
                    && actual.endDate().equals(expected.period().endDate().toString()));
        }
    }

    private static void ranking(Map<String, Object> operation, Query query, String queryPlanId) {
        require(query.fullShortUrl().isEmpty());
        String metric = string(operation.get("metric"));
        require(RANKING_METRICS.contains(metric));
        Object rawLimit = operation.get("limit");
        require(rawLimit instanceof Integer || rawLimit instanceof Long);
        long number = ((Number) rawLimit).longValue();
        require(number >= 1 && number <= 50);
        String expected = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                "ranking-query-plan/v1", query.gid(), query.startDate(), query.endDate(), metric, (int) number)));
        require(queryPlanId.equals(expected));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance));
        return (Map<String, Object>) value;
    }

    private static String string(Object value) {
        require(value instanceof String text && !text.isBlank() && text.equals(text.trim())
                && text.chars().noneMatch(Character::isISOControl));
        return (String) value;
    }

    private static void require(boolean allowed) { if (!allowed) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("STATISTICS_RUN_INPUT_INVALID"); }
}
