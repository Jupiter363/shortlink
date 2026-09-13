package com.jupiter.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.security.OwnedGroupNameResolver;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileCandidateLoadNode {

    private static final String INTAKE_NODE = "intake";
    private static final String PROFILE_CANDIDATE_LOAD_NODE = "profile_candidate_load";
    private static final Pattern GID_PATTERN =
            Pattern.compile("gid\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");

    private final JdbcShortLinkRiskProfileRepository shortLinkRepository;
    private final JdbcGroupRiskProfileRepository groupRepository;
    private final int topCandidateSize;
    private final AgentAuthorityClient authority;
    private final AgentToolRegistry toolRegistry;

    public ProfileCandidateLoadNode(
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            int topCandidateSize) {
        this(shortLinkRepository, groupRepository, topCandidateSize, null);
    }

    public ProfileCandidateLoadNode(
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            int topCandidateSize,
            AgentAuthorityClient authority) {
        this(shortLinkRepository, groupRepository, topCandidateSize, authority, null);
    }

    public ProfileCandidateLoadNode(
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            int topCandidateSize,
            AgentAuthorityClient authority,
            AgentToolRegistry toolRegistry) {
        this.shortLinkRepository = shortLinkRepository;
        this.groupRepository = groupRepository;
        this.topCandidateSize = Math.min(100, Math.max(1, topCandidateSize));
        this.authority = authority;
        this.toolRegistry = toolRegistry;
    }

    public static ProfileCandidateLoadNode noop() {
        return new ProfileCandidateLoadNode(null, null, 10);
    }

    public Map<String, Object> apply(OverAllState state) {
        Object analysisInputState = state.value("analysisInput").orElse(null);
        RiskAnalysisInput analysisInput =
                analysisInputState == null
                                || (analysisInputState instanceof Map<?, ?> map && map.isEmpty())
                        ? null
                        : RiskAnalysisInput.fromStateValue(analysisInputState)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Invalid structured risk analysis input"));
        String message = state.value("message", "");
        AgentPrincipal principal = AgentPrincipal.fromState(state.value("principal").orElse(null));
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> executions = new ArrayList<>();
        String status = "EXPLICIT";
        String notice = "";
        String authorizedStatisticsGid = "";
        ProfileRiskAnalysisContext context;
        if (analysisInput == null
                && !StringUtils.hasText(extractGid(message))
                && toolRegistry != null
                && shortLinkRepository != null
                && groupRepository != null) {
            String gid =
                    resolveImplicitGid(
                            message, state.value("sessionId", ""), principal, warnings, executions);
            if (gid.isBlank()) {
                context = ProfileRiskAnalysisContext.empty();
                status = "MISSING";
            } else {
                context = loadAuthorized(gid, null, principal);
                authorizedStatisticsGid = gid;
                status = context.isEmpty() ? "NO_PROFILE" : "RESOLVED";
                notice =
                        "本轮诊断范围仅为当前用户的已授权分组 gid="
                                + gid
                                + "。画像与统计分别以本轮实际读取的证据为准；未调用实时策略查询工具，不代表已检查当前全部策略。";
                warnings.add(notice);
                if (context.isEmpty()) warnings.add("该分组当前没有可用风险画像；这不表示该分组没有风险。");
            }
        } else {
            context = load(message, analysisInput, principal);
            if (analysisInput == null && principal != null && authority != null
                    && shortLinkRepository != null && groupRepository != null) {
                authorizedStatisticsGid = context.gid();
            }
        }
        return Map.of(
                "profileRiskContext",
                context,
                "profileRiskDataSource",
                context.isEmpty() ? Map.of() : context.toDataSource(),
                "profileScopeWarnings",
                warnings,
                "profileScopeToolExecutions",
                executions,
                "profileScopeStatus",
                status,
                "profileScopeNotice",
                notice,
                "authorizedStatisticsGid",
                authorizedStatisticsGid,
                "visitedNodes",
                List.of(INTAKE_NODE, PROFILE_CANDIDATE_LOAD_NODE));
    }

    private String resolveImplicitGid(
            String message,
            String sessionId,
            AgentPrincipal principal,
            List<String> warnings,
            List<Map<String, Object>> executions) {
        if (principal == null) {
            warnings.add("缺少可信用户身份，无法确定风险诊断范围；未查询任何风险画像。");
            return "";
        }
        var tool = toolRegistry.findByName("list_groups");
        if (tool.isEmpty()) {
            warnings.add("当前分组查询工具不可用，请提供准确 gid；未查询任何风险画像。");
            return "";
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("name", "list_groups");
        execution.put("arguments", Map.of());
        executions.add(execution);
        try {
            var result =
                    tool.get()
                            .execute(
                                    new ToolContext(
                                            sessionId, principal.username(), Map.of(), principal));
            execution.put("success", result.success());
            if (!result.success()) {
                execution.put("message", "当前用户分组列表查询失败");
                warnings.add("当前用户分组列表查询失败，无法确定风险诊断范围；未查询任何风险画像。");
                return "";
            }
            execution.put("data", result.data());
            List<?> groups = result.data() instanceof List<?> list ? list : List.of();
            var resolution = OwnedGroupNameResolver.resolve(message, groups);
            if (resolution.status() == OwnedGroupNameResolver.Status.MATCHED)
                return resolution.gid();
            if (resolution.status() == OwnedGroupNameResolver.Status.MISSING
                    && !OwnedGroupNameResolver.hasExplicitGroupReference(message)) {
                List<String> gids =
                        groups.stream()
                                .filter(Map.class::isInstance)
                                .map(value -> ((Map<?, ?>) value).get("gid"))
                                .filter(value -> value instanceof String gid && !gid.isBlank())
                                .map(Object::toString)
                                .distinct()
                                .toList();
                if (gids.size() == 1) return gids.get(0);
            }
            warnings.add("无法唯一确定风险诊断分组，请从本轮返回的当前用户分组列表选择准确 gid；未查询任何风险画像，不能据此判断有无风险。");
        } catch (RuntimeException failure) {
            execution.put("success", false);
            execution.remove("data");
            execution.put("message", "当前用户分组列表查询失败");
            warnings.add("当前用户分组列表查询失败，无法确定风险诊断范围；未查询任何风险画像。");
        }
        return "";
    }

    public ProfileRiskAnalysisContext load(String message) {
        return load(message, null);
    }

    public ProfileRiskAnalysisContext load(String message, RiskAnalysisInput analysisInput) {
        return load(message, analysisInput, null);
    }

    public ProfileRiskAnalysisContext load(
            String message, RiskAnalysisInput analysisInput, AgentPrincipal principal) {
        String gid = analysisInput == null ? extractGid(message) : analysisInput.gid();
        return loadAuthorized(gid, analysisInput, principal);
    }

    private ProfileRiskAnalysisContext loadAuthorized(
            String gid, RiskAnalysisInput analysisInput, AgentPrincipal principal) {
        if (!StringUtils.hasText(gid) || shortLinkRepository == null || groupRepository == null) {
            return ProfileRiskAnalysisContext.empty();
        }
        if (principal == null || authority == null)
            throw new SecurityException("Current profile read authorization is required");
        AgentAuthorityClient.AuthorizedScope scope = authority.resolve(principal, gid, null, null);
        String batchId = analysisInput == null ? null : analysisInput.batchId();
        Optional<GroupRiskProfile> groupProfile =
                groupRepository.findAuthorized(scope, gid, batchId);
        List<ShortLinkRiskProfile> shortLinkProfiles =
                shortLinkRepository.findAuthorized(scope, batchId, topCandidateSize).stream()
                        .filter(profile -> profile.riskLevel() != RiskLevel.LOW)
                        .toList();
        if (analysisInput != null) {
            shortLinkProfiles =
                    shortLinkProfiles.stream()
                            .filter(
                                    profile ->
                                            analysisInput.candidates().stream()
                                                    .anyMatch(
                                                            candidate ->
                                                                    candidate
                                                                                    .domain()
                                                                                    .equals(
                                                                                            profile
                                                                                                    .domain())
                                                                            && candidate
                                                                                    .shortUri()
                                                                                    .equals(
                                                                                            profile
                                                                                                    .shortUri())))
                            .toList();
            if (shortLinkProfiles.size()
                    != Math.min(topCandidateSize, analysisInput.candidates().size())) {
                throw new IllegalStateException(
                        "Structured profile candidates are unavailable within the current scope");
            }
        }
        return new ProfileRiskAnalysisContext(gid, groupProfile.orElse(null), shortLinkProfiles);
    }

    private String extractGid(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        Matcher matcher = GID_PATTERN.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        String gid = matcher.group(1).trim();
        while (!gid.isEmpty() && isTrailingArgumentPunctuation(gid.charAt(gid.length() - 1))) {
            gid = gid.substring(0, gid.length() - 1);
        }
        return gid;
    }

    private boolean isTrailingArgumentPunctuation(char value) {
        return value == '.' || value == ';' || value == '\u3002' || value == '\uFF1B';
    }
}
