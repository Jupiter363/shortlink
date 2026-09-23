package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsQueryPlan;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignStatisticsRunAuthorizerTest {
    private static final Caller OWNER = new Caller("1001", "zhangsan", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "zhangsan", 7, false);
    private static final String VERSION = "a".repeat(64);
    private static final String START = "2026-09-01";
    private static final String END = "2026-09-02";

    @Test
    void comparisonAcceptsOnlyCurrentOwnedGroupsAndExactOperationMembership() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        allowGroup(authority);
        var inputs = comparison(List.of(Map.of("gid", "g1")), List.of(
                Map.of("startDate", START, "endDate", END),
                Map.of("startDate", "2026-09-03", "endDate", "2026-09-04")));
        assertThat(new CampaignStatisticsRunAuthorizer(authority).mayExecute(OWNER, inputs)).isTrue();
        verify(authority, times(1)).verifyCurrentPrincipal(PRINCIPAL);
        verify(authority, times(1)).resolveGroupMembersPage(PRINCIPAL, "g1", null, null);
    }

    @Test
    void malformedReferencesAndOperationCannotReachAuthority() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        var inputs = comparison(List.of(Map.of("gid", "g1")), List.of(
                Map.of("startDate", START, "endDate", END),
                Map.of("startDate", "2026-09-03", "endDate", "2026-09-04")));
        var authorizer = new CampaignStatisticsRunAuthorizer(authority);
        assertThat(authorizer.mayExecute(OWNER, changed(inputs, "scope-1", "current-group.v1:g2"))).isFalse();
        assertThat(authorizer.mayExecute(OWNER, changed(inputs, "periods-1", "period.v1:2026-01-01:2026-01-02"))).isFalse();
        Map<String, Object> operation = new LinkedHashMap<>(operation(inputs));
        operation.put("queryPlanId", "0".repeat(64));
        assertThat(authorizer.mayExecute(OWNER, changed(inputs, "operation", operation))).isFalse();
        Map<String, Object> descriptor = new LinkedHashMap<>(descriptor(inputs, 1));
        descriptor.put("queryKind", "LINK_METRICS");
        assertThat(authorizer.mayExecute(OWNER, changed(inputs, "query-1", descriptor))).isFalse();
        verifyNoInteractions(authority);
    }

    @Test
    void comparisonRejectsChangedBaselineOrderAndMissingCartesianMember() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        var inputs = comparison(List.of(Map.of("gid", "g1")), List.of(
                Map.of("startDate", START, "endDate", END),
                Map.of("startDate", "2026-09-03", "endDate", "2026-09-04")));
        var swapped = changed(changed(changed(changed(changed(changed(inputs,
                "scope-1", inputs.inputValues().get("scope-2")),
                "periods-1", inputs.inputValues().get("periods-2")),
                "query-1", inputs.inputValues().get("query-2")),
                "scope-2", inputs.inputValues().get("scope-1")),
                "periods-2", inputs.inputValues().get("periods-1")),
                "query-2", inputs.inputValues().get("query-1"));
        assertThat(new CampaignStatisticsRunAuthorizer(authority).mayExecute(OWNER, swapped)).isFalse();
        assertThat(new CampaignStatisticsRunAuthorizer(authority).mayExecute(OWNER,
                changed(inputs, "query-2", inputs.inputValues().get("query-1")))).isFalse();
        verifyNoInteractions(authority);
    }

    @Test
    void rankingAcceptsOneCanonicalLinkMetricsQueryAndRejectsForeignAccount() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        allowGroup(authority);
        var inputs = ranking();
        var authorizer = new CampaignStatisticsRunAuthorizer(authority);
        assertThat(authorizer.mayExecute(OWNER, inputs)).isTrue();
        when(authority.verifyCurrentPrincipal(PRINCIPAL))
                .thenReturn(new AgentPrincipal("1002", "otheruser", 7, false));
        assertThat(authorizer.mayExecute(OWNER, inputs)).isFalse();
        Map<String, Object> operation = new LinkedHashMap<>(operation(inputs));
        operation.put("metric", "clicks");
        assertThat(authorizer.mayExecute(OWNER, changed(inputs, "operation", operation))).isFalse();
    }

    @Test
    void fullShortUrlRequiresExactCurrentMembershipAtPinnedGroupVersion() {
        AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        allowGroup(authority);
        var inputs = comparison(List.of(Map.of("gid", "g1", "fullShortUrl", "sho.rt/a")), List.of(
                Map.of("startDate", START, "endDate", END),
                Map.of("startDate", "2026-09-03", "endDate", "2026-09-04")));
        when(authority.resolvePage(eq(PRINCIPAL), eq("g1"), eq("sho.rt/a"), isNull(), isNull(), eq(VERSION)))
                .thenReturn(new AgentAuthorityClient.AuthorizedScope("1001", VERSION,
                        List.of(Map.of("gid", "g1", "fullShortUrl", "https://sho.rt/a", "linkId", 11L))));
        var authorizer = new CampaignStatisticsRunAuthorizer(authority);
        assertThat(authorizer.mayExecute(OWNER, inputs)).isTrue();
        verify(authority, times(1)).resolvePage(PRINCIPAL, "g1", "sho.rt/a", null, null, VERSION);
        when(authority.resolvePage(eq(PRINCIPAL), eq("g1"), eq("sho.rt/a"), isNull(), isNull(), eq(VERSION)))
                .thenReturn(new AgentAuthorityClient.AuthorizedScope("1001", VERSION,
                        List.of(Map.of("gid", "g1", "fullShortUrl", "https://sho.rt/other", "linkId", 11L))));
        assertThat(authorizer.mayExecute(OWNER, inputs)).isFalse();
        when(authority.resolvePage(eq(PRINCIPAL), eq("g1"), eq("sho.rt/a"), isNull(), isNull(), eq(VERSION)))
                .thenReturn(new AgentAuthorityClient.AuthorizedScope("1001", VERSION,
                        List.of(Map.of("gid", "g1", "fullShortUrl", "https://sho.rt/a", "linkId", 11L),
                                Map.of("gid", "g1", "fullShortUrl", "https://sho.rt/other", "linkId", 12L))));
        assertThat(authorizer.mayExecute(OWNER, inputs)).isFalse();
        when(authority.resolvePage(eq(PRINCIPAL), eq("g1"), eq("sho.rt/a"), isNull(), isNull(), eq(VERSION)))
                .thenReturn(new AgentAuthorityClient.AuthorizedScope("1001", VERSION,
                        List.of(Map.of("gid", "g1", "fullShortUrl", "https://sho.rt/a", "linkId", 11L)), 11L));
        assertThat(authorizer.mayExecute(OWNER, inputs)).isFalse();
    }

    private static void allowGroup(AgentAuthorityClient authority) {
        when(authority.verifyCurrentPrincipal(PRINCIPAL)).thenReturn(PRINCIPAL);
        when(authority.resolveGroupMembersPage(PRINCIPAL, "g1", null, null))
                .thenReturn(new GroupMembersPage(GroupMembersPage.SCHEMA, "1001", "zhangsan", 7,
                        "g1", VERSION, null, List.of(11L), null));
    }

    private static FrozenInputSet comparison(List<Map<String, Object>> scopes, List<Map<String, Object>> periods) {
        var plan = CampaignStatisticsQueryPlan.create(scopes, periods);
        Map<String, Port> contracts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        contracts.put("operation", new Port(CampaignStatisticsPlanFactory.OPERATION_TYPE, true));
        values.put("operation", Map.of("schemaVersion", CampaignStatisticsPlanFactory.OPERATION_SCHEMA,
                "kind", "COMPARISON", "queryPlanId", plan.planId()));
        for (int index = 0; index < plan.queries().size(); index++) {
            var query = plan.queries().get(index);
            String suffix = "-" + (index + 1);
            String scopeRef = "current-group.v1:" + query.scope().gid();
            String periodsRef = "period.v1:" + query.period().startDate() + ":" + query.period().endDate();
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("schemaVersion", FrozenStatisticsJobQuery.SCHEMA);
            descriptor.put("scopeRef", scopeRef);
            descriptor.put("periodsRef", periodsRef);
            descriptor.put("scopeKind", "CURRENT_GROUP");
            descriptor.put("gid", query.scope().gid());
            if (!query.scope().fullShortUrl().isEmpty())
                descriptor.put("fullShortUrl", query.scope().fullShortUrl());
            descriptor.put("startDate", query.period().startDate().toString());
            descriptor.put("endDate", query.period().endDate().toString());
            descriptor.put("businessTimezone", "Asia/Shanghai");
            descriptor.put("queryKind", "METRICS");
            contracts.put("scope" + suffix, FrozenStatisticsJobQuery.INPUTS.get("scope"));
            contracts.put("periods" + suffix, FrozenStatisticsJobQuery.INPUTS.get("periods"));
            contracts.put("query" + suffix, FrozenStatisticsJobQuery.INPUTS.get("query"));
            values.put("scope" + suffix, scopeRef);
            values.put("periods" + suffix, periodsRef);
            values.put("query" + suffix, descriptor);
        }
        return new FrozenInputSet("inputs-1", "run-1", contracts, values);
    }

    private static FrozenInputSet ranking() {
        Map<String, Port> contracts = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        contracts.put("operation", new Port(CampaignStatisticsPlanFactory.OPERATION_TYPE, true));
        String planId = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(
                "ranking-query-plan/v1", "g1", START, END, "pv", 10)));
        values.put("operation", Map.of("schemaVersion", CampaignStatisticsPlanFactory.OPERATION_SCHEMA,
                "kind", "RANKING", "queryPlanId", planId, "metric", "pv", "limit", 10));
        String scopeRef = "current-group.v1:g1", periodsRef = "period.v1:" + START + ":" + END;
        contracts.put("scope-1", FrozenStatisticsJobQuery.INPUTS.get("scope"));
        contracts.put("periods-1", FrozenStatisticsJobQuery.INPUTS.get("periods"));
        contracts.put("query-1", FrozenStatisticsJobQuery.INPUTS.get("query"));
        values.put("scope-1", scopeRef);
        values.put("periods-1", periodsRef);
        values.put("query-1", Map.of("schemaVersion", FrozenStatisticsJobQuery.SCHEMA,
                "scopeRef", scopeRef, "periodsRef", periodsRef, "scopeKind", "CURRENT_GROUP",
                "gid", "g1", "startDate", START, "endDate", END,
                "businessTimezone", "Asia/Shanghai", "queryKind", "LINK_METRICS"));
        return new FrozenInputSet("inputs-1", "run-1", contracts, values);
    }

    private static FrozenInputSet changed(FrozenInputSet original, String key, Object value) {
        Map<String, Object> values = new LinkedHashMap<>(original.inputValues());
        values.put(key, value);
        return new FrozenInputSet(original.inputSetRef(), original.runId(), original.inputContracts(), values);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation(FrozenInputSet inputs) {
        return (Map<String, Object>) inputs.inputValues().get("operation");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> descriptor(FrozenInputSet inputs, int index) {
        return (Map<String, Object>) inputs.inputValues().get("query-" + index);
    }
}
