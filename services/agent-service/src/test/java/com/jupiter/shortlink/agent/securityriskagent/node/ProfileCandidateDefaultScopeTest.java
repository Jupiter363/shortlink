package com.jupiter.shortlink.agent.securityriskagent.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.checkpoint.GraphCheckpointStore;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.*;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.securityriskagent.graph.DefaultSecurityRiskGraphExecutor;
import com.jupiter.shortlink.agent.securityriskagent.graph.SecurityRiskGraphRequest;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ProfileCandidateDefaultScopeTest {
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("101", "alice", 3, false);
    private final JdbcShortLinkRiskProfileRepository links =
            mock(JdbcShortLinkRiskProfileRepository.class);
    private final JdbcGroupRiskProfileRepository groups =
            mock(JdbcGroupRiskProfileRepository.class);
    private final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
    private final AgentTool listGroups = mock(AgentTool.class);

    @ParameterizedTest
    @CsvSource({
        "诊断 default 分组最近 24 小时的访问风险,默认分组",
        "诊断默认分组风险,default",
        "Diagnose DEFAULT group risk,默认组"
    })
    void resolvesDefaultFromCurrentUserListThenUsesOriginalAuthorityAndProfileRead(
            String message, String name) {
        registry(ToolResult.success(List.of(Map.of("gid", "real-owned-gid", "name", name))));
        var scope = authorized("real-owned-gid");
        Map<String, Object> result = node().apply(state(message));
        assertThat(((ProfileRiskAnalysisContext) result.get("profileRiskContext")).gid())
                .isEqualTo("real-owned-gid");
        verify(authority).resolve(PRINCIPAL, "real-owned-gid", null, null);
        verify(groups).findAuthorized(scope, "real-owned-gid", null);
        verify(links).findAuthorized(scope, null, 10);
        verify(listGroups)
                .execute(
                        argThat(
                                context ->
                                        context.principal().equals(PRINCIPAL)
                                                && context.username().equals("alice")
                                                && context.arguments().isEmpty()));
        assertThat(result.get("profileScopeToolExecutions").toString())
                .contains("list_groups", "success=true");
        assertThat(result.get("profileScopeStatus")).isEqualTo("NO_PROFILE");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("real-owned-gid");
        assertThat(((ProfileRiskAnalysisContext) result.get("profileRiskContext")).isEmpty())
                .isTrue();
        assertThat(result.get("profileRiskDataSource")).isEqualTo(Map.of());
        assertThat(result.get("profileScopeWarnings").toString())
                .contains("没有可用风险画像", "不表示该分组没有风险");
    }

    @Test
    void unscopedPolicyPresetUsesOnlyTheSoleAuthorizedGroupAndDisclosesToolLimit() {
        registry(ToolResult.success(List.of(Map.of("gid", "only-owned", "name", "Marketing"))));
        authorized("only-owned");
        var result = node().apply(state("检查当前风险策略，指出覆盖空白并给出受控调整建议"));
        verify(authority).resolve(PRINCIPAL, "only-owned", null, null);
        assertThat(result.get("profileScopeNotice").toString())
                .contains("仅为当前用户", "gid=only-owned", "未调用实时策略查询工具");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("only-owned");
    }

    @Test
    void multipleUnscopedGroupsRequireSelectionWithoutReadingAnyProfiles() {
        registry(
                ToolResult.success(
                        List.of(
                                Map.of("gid", "one", "name", "Marketing"),
                                Map.of("gid", "two", "name", "Product"))));
        var result = node().apply(state("列出当前活跃的高风险短链接并说明风险证据"));
        assertThat(result.get("profileScopeStatus")).isEqualTo("MISSING");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        assertThat(result.get("profileScopeWarnings").toString())
                .contains("选择准确 gid", "不能据此判断有无风险");
        verifyNoInteractions(authority, links, groups);
    }

    @Test
    void explicitDefaultNeverFallsBackToAnUnrelatedSoleGroup() {
        registry(ToolResult.success(List.of(Map.of("gid", "not-default", "name", "Marketing"))));
        var result = node().apply(state("诊断 default 分组最近24小时风险"));
        assertThat(result.get("profileScopeStatus")).isEqualTo("MISSING");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        verifyNoInteractions(authority, links, groups);
    }

    @Test
    void ambiguousDefaultAliasesDoNotPickFirstGroup() {
        registry(
                ToolResult.success(
                        List.of(
                                Map.of("gid", "one", "name", "default"),
                                Map.of("gid", "two", "name", "默认分组"))));
        var result = node().apply(state("诊断default分组风险"));
        assertThat(result.get("profileScopeStatus")).isEqualTo("MISSING");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        verifyNoInteractions(authority, links, groups);
    }

    @Test
    void failedDiscoveryPreservesFailedToolEvidenceWithoutProfileQueries() {
        registry(ToolResult.failure("unavailable"));
        var result = node().apply(state("诊断default分组风险"));
        assertThat(result.get("profileScopeToolExecutions").toString())
                .contains("list_groups", "success=false");
        assertThat(result.get("profileScopeWarnings").toString()).contains("查询失败");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        verifyNoInteractions(authority, links, groups);
    }

    @Test
    void missingPrincipalCannotListGroupsOrReadProfiles() {
        registry(ToolResult.success(List.of()));
        var result =
                node().apply(
                                new OverAllState(
                                        Map.of(
                                                "message", "诊断default分组风险",
                                                "username", "alice",
                                                "authorizedStatisticsGid", "stale-owned-gid")));
        assertThat(result.get("profileScopeWarnings").toString()).contains("缺少可信用户身份");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        verify(listGroups, never()).execute(any());
        verifyNoInteractions(authority, links, groups);
    }

    @Test
    void explicitGidKeepsOriginalAuthorizationPathAndDoesNotDiscoverOtherGroups() {
        registry(ToolResult.success(List.of()));
        authorized("explicit-gid");
        var result = node().apply(state("诊断 gid=explicit-gid default分组风险"));
        assertThat(result.get("profileScopeStatus")).isEqualTo("EXPLICIT");
        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("explicit-gid");
        verify(authority).resolve(PRINCIPAL, "explicit-gid", null, null);
        verify(listGroups, never()).execute(any());
    }

    @Test
    void discoveredGroupMustPassFreshAuthorizationBeforeAnyProfileOrStatisticsScopeIsRead() {
        registry(ToolResult.success(List.of(Map.of("gid", "revoked-gid", "name", "默认分组"))));
        when(authority.resolve(PRINCIPAL, "revoked-gid", null, null))
                .thenThrow(new SecurityException("Current group access was revoked"));

        assertThatThrownBy(() -> node().apply(state("诊断默认分组访问风险")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Current group access was revoked");

        verify(authority).resolve(PRINCIPAL, "revoked-gid", null, null);
        verifyNoInteractions(links, groups);
    }

    @Test
    void structuredBatchReadsOnlyItsAuthorizedBatchWithoutGrantingDynamicStatisticsScope() {
        registry(ToolResult.success(List.of(Map.of("gid", "other-gid", "name", "默认分组"))));
        String gid = "batch-owned-gid";
        String batchId = "batch-structured";
        var scope = new AgentAuthorityClient.AuthorizedScope("101", "ownership-batch", List.of());
        when(authority.resolve(PRINCIPAL, gid, null, null)).thenReturn(scope);
        when(groups.findAuthorized(scope, gid, batchId)).thenReturn(Optional.empty());
        when(links.findAuthorized(scope, batchId, 10)).thenReturn(List.of());
        var input =
                new RiskAnalysisInput(
                        batchId, gid, LocalDateTime.of(2026, 9, 13, 22, 0), List.of());

        var result =
                node().apply(
                                new OverAllState(
                                        Map.of(
                                                "message", "诊断默认分组 gid=other-gid 访问风险",
                                                "principal", PRINCIPAL.toState(),
                                                "analysisInput", input.toStateValue(),
                                                "authorizedStatisticsGid", "stale-owned-gid")));

        assertThat(result.get("authorizedStatisticsGid")).isEqualTo("");
        assertThat(result.get("profileScopeStatus")).isEqualTo("EXPLICIT");
        assertThat(result.get("profileScopeToolExecutions")).isEqualTo(List.of());
        assertThat(((ProfileRiskAnalysisContext) result.get("profileRiskContext")).gid())
                .isEqualTo(gid);
        verify(authority).resolve(PRINCIPAL, gid, null, null);
        verify(groups).findAuthorized(scope, gid, batchId);
        verify(links).findAuthorized(scope, batchId, 10);
        verify(listGroups, never()).execute(any());
        verifyNoMoreInteractions(authority, groups, links);
    }

    @Test
    void graphCarriesScopeWarningsAndActualLookupAcrossNodesAndDoesNotInventModelEvidence() {
        registry(
                ToolResult.success(
                        List.of(
                                Map.of("gid", "one", "name", "Marketing"),
                                Map.of("gid", "two", "name", "Product"))));
        LlmChatClient llm = mock(LlmChatClient.class);
        var graph =
                new DefaultSecurityRiskGraphExecutor(
                        llm,
                        mock(GraphCheckpointStore.class),
                        new AgentProperties(),
                        new AgentToolRegistry(List.of(listGroups)),
                        links,
                        groups,
                        null,
                        null,
                        authority);
        var result =
                graph.execute(
                        new SecurityRiskGraphRequest(
                                "scope-test", "alice", "检查当前风险策略", "scope-trace", null, PRINCIPAL));
        assertThat(result.answer()).contains("选择准确 gid", "不能据此判断有无风险");
        assertThat(result.warnings().toString()).contains("选择准确 gid");
        assertThat(result.toolCalls().toString()).contains("list_groups", "Marketing", "Product");
        verifyNoInteractions(llm, authority, links, groups);
        assertThat(result.traceEvents()).hasSize(9);
    }

    private void registry(ToolResult result) {
        when(listGroups.descriptor())
                .thenReturn(new ToolDescriptor("list_groups", "Current user's groups", Map.of()));
        when(listGroups.execute(any())).thenReturn(result);
    }

    private ProfileCandidateLoadNode node() {
        return new ProfileCandidateLoadNode(
                links, groups, 10, authority, new AgentToolRegistry(List.of(listGroups)));
    }

    private AgentAuthorityClient.AuthorizedScope authorized(String gid) {
        var scope = new AgentAuthorityClient.AuthorizedScope("101", "ownership-1", List.of());
        when(authority.resolve(PRINCIPAL, gid, null, null)).thenReturn(scope);
        when(groups.findAuthorized(scope, gid, null)).thenReturn(Optional.empty());
        when(links.findAuthorized(scope, null, 10)).thenReturn(List.of());
        return scope;
    }

    private OverAllState state(String message) {
        return new OverAllState(
                Map.of(
                        "message",
                        message,
                        "sessionId",
                        "scope-test",
                        "username",
                        "untrusted-name",
                        "principal",
                        PRINCIPAL.toState(),
                        "authorizedStatisticsGid",
                        "stale-owned-gid"));
    }
}
