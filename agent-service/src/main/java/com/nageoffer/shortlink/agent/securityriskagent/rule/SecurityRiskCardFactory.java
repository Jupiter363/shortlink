package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SecurityRiskCardFactory {

    private final SecurityRiskSanitizer sanitizer;

    private final SecurityRiskRuleEngine ruleEngine;

    public SecurityRiskCardFactory() {
        this(new SecurityRiskSanitizer());
    }

    public SecurityRiskCardFactory(SecurityRiskSanitizer sanitizer) {
        this(sanitizer, SecurityRiskRuleEngine.defaultEngine(sanitizer));
    }

    public SecurityRiskCardFactory(SecurityRiskSanitizer sanitizer, SecurityRiskRuleEngine ruleEngine) {
        this.sanitizer = sanitizer;
        this.ruleEngine = ruleEngine;
    }

    public List<Object> build(List<Map<String, Object>> toolExecutions) {
        List<Object> cards = new ArrayList<>();
        for (RiskSignal signal : ruleEngine.evaluate(toolExecutions)) {
            cards.add(signal.toCard());
        }
        return cards;
    }

    Object sanitizeForPrompt(Object value) {
        return sanitizer.sanitizeObject(value);
    }

    String sanitizeText(String text) {
        return sanitizer.sanitizeText(text);
    }
}
