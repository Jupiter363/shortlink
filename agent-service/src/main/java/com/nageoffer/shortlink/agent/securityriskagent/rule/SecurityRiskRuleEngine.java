package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SecurityRiskRuleEngine {

    private final List<RiskRule> rules;

    public SecurityRiskRuleEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static SecurityRiskRuleEngine defaultEngine() {
        return defaultEngine(new SecurityRiskSanitizer());
    }

    public static SecurityRiskRuleEngine defaultEngine(SecurityRiskSanitizer sanitizer) {
        return new SecurityRiskRuleEngine(List.of(
                new TopIpConcentrationRule(sanitizer),
                new HighRepeatVisitRule(),
                new HourBurstRule()
        ));
    }

    public List<RiskSignal> evaluate(List<Map<String, Object>> toolExecutions) {
        List<RiskSignal> signals = new ArrayList<>();
        for (Map<String, Object> execution : toolExecutions) {
            if (!Boolean.TRUE.equals(execution.get("success")) || !isStatsTool(execution)) {
                continue;
            }
            for (RiskRule rule : rules) {
                try {
                    signals.addAll(rule.evaluate(execution));
                } catch (RuntimeException ignored) {
                    // A single deterministic rule must not fail the whole graph.
                }
            }
        }
        return signals;
    }

    private boolean isStatsTool(Map<String, Object> execution) {
        String toolName = RiskRuleSupport.textValue(execution.get("name"));
        return "get_group_stats".equals(toolName) || "get_short_link_stats".equals(toolName);
    }
}
