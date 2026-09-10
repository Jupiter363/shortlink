package com.jupiter.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.jupiter.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.jupiter.shortlink.agent.securityriskagent.model.RiskAnalysisInput;

import org.springframework.util.StringUtils;

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
        this.shortLinkRepository = shortLinkRepository;
        this.groupRepository = groupRepository;
        this.topCandidateSize = Math.min(100, Math.max(1, topCandidateSize));
        this.authority = authority;
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
        ProfileRiskAnalysisContext context =
                load(
                        state.value("message", ""),
                        analysisInput,
                        AgentPrincipal.fromState(state.value("principal").orElse(null)));
        return Map.of(
                "profileRiskContext",
                context,
                "profileRiskDataSource",
                context.isEmpty() ? Map.of() : context.toDataSource(),
                "visitedNodes",
                List.of(INTAKE_NODE, PROFILE_CANDIDATE_LOAD_NODE));
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
