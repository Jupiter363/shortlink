package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.service.AgentPendingActionService;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalContext;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskActionProposalFactory;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RiskActionProposalNode {

    private static final String RISK_ACTION_PROPOSAL_NODE = "risk_action_proposal";
    private static final String BATCH_PROPOSER = "risk-analysis-worker";
    private static final Pattern FULL_SHORT_URL_PATTERN = Pattern.compile(
            "fullShortUrl\\s*[:=\\uFF1A]\\s*([^\\s,;\\uFF0C\\uFF1B]+)"
    );

    private final RiskActionProposalFactory proposalFactory;
    private final AgentPendingActionService pendingActionService;
    private final Duration ttl;
    private final boolean enabled;

    public RiskActionProposalNode(
            RiskActionProposalFactory proposalFactory,
            AgentPendingActionService pendingActionService,
            Duration ttl
    ) {
        this.proposalFactory = Objects.requireNonNull(
                proposalFactory,
                "proposalFactory must not be null"
        );
        this.pendingActionService = Objects.requireNonNull(
                pendingActionService,
                "pendingActionService must not be null"
        );
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ttl = ttl;
        this.enabled = true;
    }

    private RiskActionProposalNode() {
        this.proposalFactory = null;
        this.pendingActionService = null;
        this.ttl = null;
        this.enabled = false;
    }

    public static RiskActionProposalNode noop() {
        return new RiskActionProposalNode();
    }

    public Map<String, Object> apply(OverAllState state) {
        if (!enabled) {
            return output(List.of());
        }
        Objects.requireNonNull(state, "state must not be null");

        RiskActionProposalContext context = context(state);
        List<AgentActionProposal> proposals = proposalFactory.create(context);
        if (proposals == null || proposals.isEmpty()) {
            return output(List.of());
        }
        List<AgentPendingActionView> views = proposals.stream()
                .map(proposal -> pendingActionService.propose(proposal, ttl))
                .toList();
        return output(views);
    }

    private RiskActionProposalContext context(OverAllState state) {
        ProfileRiskAnalysisContext profileContext = state.value(
                "profileRiskContext",
                ProfileRiskAnalysisContext.empty()
        );
        Map<String, String> eventIdsByTarget = state.value("eventIdsByTarget", Map.of());
        List<RiskSignal> riskSignals = state.value("riskSignals", List.of());
        List<Map<String, Object>> toolExecutions = state.value("toolExecutions", List.of());
        String sessionId = state.value("sessionId", "");
        String username = state.value("username", "");
        String traceId = state.value("traceId", "");
        boolean batch = state.value("analysisInput").isPresent();
        Optional<RiskManualActionDirective> directive = manualDirective(state);

        return new RiskActionProposalContext(
                batch
                        ? "risk-profile:" + profileContext.batchId()
                        : interactiveSourceId(eventIdsByTarget, sessionId),
                profileContext.gid(),
                sessionId,
                batch ? BATCH_PROPOSER : username,
                traceId,
                eventIdsByTarget,
                proposalProfiles(
                        profileContext.shortLinkProfiles(),
                        directive,
                        state.value("message", "")
                ),
                riskSignals,
                toolExecutions,
                directive
        );
    }

    private List<ShortLinkRiskProfile> proposalProfiles(
            List<ShortLinkRiskProfile> profiles,
            Optional<RiskManualActionDirective> directive,
            String message
    ) {
        if (directive.isEmpty() || profiles == null || profiles.isEmpty()) {
            return profiles == null ? List.of() : profiles;
        }
        String explicitTarget = fullShortUrl(message);
        if (!hasText(explicitTarget)) {
            return profiles.size() == 1 ? profiles : List.of();
        }
        List<ShortLinkRiskProfile> matched = profiles.stream()
                .filter(Objects::nonNull)
                .filter(profile -> explicitTarget.equals(profile.fullShortUrl()))
                .toList();
        return matched.size() == 1 ? matched : List.of();
    }

    private String fullShortUrl(String message) {
        if (!hasText(message)) {
            return "";
        }
        Matcher matcher = FULL_SHORT_URL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return "";
        }
        String value = matcher.group(1).trim();
        if (matcher.find()) {
            return "";
        }
        while (!value.isEmpty() && isTrailingPunctuation(value.charAt(value.length() - 1))) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private boolean isTrailingPunctuation(char value) {
        return value == '.'
                || value == ';'
                || value == '\u3002'
                || value == '\uFF1B';
    }

    private Optional<RiskManualActionDirective> manualDirective(OverAllState state) {
        Object value = state.value("manualActionDirective").orElse(null);
        if (value instanceof Optional<?> optional) {
            value = optional.orElse(null);
        }
        return value instanceof RiskManualActionDirective directive
                ? Optional.of(directive)
                : Optional.empty();
    }

    private String interactiveSourceId(Map<String, String> eventIdsByTarget, String sessionId) {
        String stableId = eventIdsByTarget == null
                ? ""
                : eventIdsByTarget.values().stream()
                        .filter(this::hasText)
                        .findFirst()
                        .orElse("");
        if (!hasText(stableId)) {
            stableId = sessionId == null ? "" : sessionId;
        }
        return "interactive:" + stableId;
    }

    private Map<String, Object> output(List<AgentPendingActionView> views) {
        return Map.of(
                "pendingActionViews", views,
                "visitedNodes", List.of(RISK_ACTION_PROPOSAL_NODE)
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
