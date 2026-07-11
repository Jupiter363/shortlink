package com.nageoffer.shortlink.agent.securityriskagent.model;

import java.util.List;

public record SecurityRiskAssessment(
        List<RiskSignal> signals,
        List<Object> cards
) {

    public SecurityRiskAssessment {
        signals = signals == null ? List.of() : List.copyOf(signals);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
