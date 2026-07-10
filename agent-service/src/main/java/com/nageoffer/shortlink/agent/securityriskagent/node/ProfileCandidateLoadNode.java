package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskAnalysisInput;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskProfileTargetRef;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileCandidateLoadNode {

    private static final String INTAKE_NODE = "intake";
    private static final String PROFILE_CANDIDATE_LOAD_NODE = "profile_candidate_load";
    private static final Pattern GID_PATTERN = Pattern.compile("gid\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)");

    private final JdbcShortLinkRiskProfileRepository shortLinkRepository;
    private final JdbcGroupRiskProfileRepository groupRepository;
    private final int topCandidateSize;

    public ProfileCandidateLoadNode(
            JdbcShortLinkRiskProfileRepository shortLinkRepository,
            JdbcGroupRiskProfileRepository groupRepository,
            int topCandidateSize
    ) {
        this.shortLinkRepository = shortLinkRepository;
        this.groupRepository = groupRepository;
        this.topCandidateSize = Math.max(1, topCandidateSize);
    }

    public static ProfileCandidateLoadNode noop() {
        return new ProfileCandidateLoadNode(null, null, 10);
    }

    public Map<String, Object> apply(OverAllState state) {
        Object analysisInputState = state.value("analysisInput").orElse(null);
        RiskAnalysisInput analysisInput = analysisInputState == null
                ? null
                : RiskAnalysisInput.fromStateValue(analysisInputState)
                        .orElseThrow(() -> new IllegalStateException("Invalid structured risk analysis input"));
        ProfileRiskAnalysisContext context = load(
                state.value("message", ""),
                analysisInput
        );
        return Map.of(
                "profileRiskContext", context,
                "profileRiskDataSource", context.isEmpty() ? Map.of() : context.toDataSource(),
                "visitedNodes", List.of(INTAKE_NODE, PROFILE_CANDIDATE_LOAD_NODE)
        );
    }

    public ProfileRiskAnalysisContext load(String message) {
        return load(message, null);
    }

    public ProfileRiskAnalysisContext load(String message, RiskAnalysisInput analysisInput) {
        if (analysisInput != null) {
            return load(analysisInput);
        }
        String gid = extractGid(message);
        if (!StringUtils.hasText(gid) || shortLinkRepository == null || groupRepository == null) {
            return ProfileRiskAnalysisContext.empty();
        }
        Optional<GroupRiskProfile> groupProfile = groupRepository.findLatestByGid(gid);
        List<ShortLinkRiskProfile> shortLinkProfiles = shortLinkRepository.findTopRiskByGid(gid, topCandidateSize).stream()
                .filter(profile -> profile.riskLevel() != RiskLevel.LOW)
                .toList();
        return new ProfileRiskAnalysisContext(gid, groupProfile.orElse(null), shortLinkProfiles);
    }

    private ProfileRiskAnalysisContext load(RiskAnalysisInput analysisInput) {
        if (shortLinkRepository == null || groupRepository == null) {
            return ProfileRiskAnalysisContext.empty();
        }
        Optional<GroupRiskProfile> groupProfile = groupRepository.findByBatchIdAndGid(
                analysisInput.batchId(),
                analysisInput.gid()
        );
        List<ShortLinkRiskProfile> shortLinkProfiles = analysisInput.candidates().stream()
                .limit(topCandidateSize)
                .map(candidate -> loadStructuredCandidate(analysisInput, candidate))
                .toList();
        return new ProfileRiskAnalysisContext(
                analysisInput.gid(),
                groupProfile.orElse(null),
                shortLinkProfiles
        );
    }

    private ShortLinkRiskProfile loadStructuredCandidate(
            RiskAnalysisInput analysisInput,
            RiskProfileTargetRef candidate
    ) {
        ShortLinkRiskProfile profile = shortLinkRepository.findByBatchIdAndTarget(
                analysisInput.batchId(),
                analysisInput.gid(),
                candidate.domain(),
                candidate.shortUri()
        ).orElseThrow(() -> new IllegalStateException("Structured risk profile candidate was not found"));
        if (profile.riskLevel() == RiskLevel.LOW) {
            throw new IllegalStateException("Structured risk profile candidate is not eligible");
        }
        return profile;
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
        return value == '.'
                || value == ';'
                || value == '\u3002'
                || value == '\uFF1B';
    }
}
