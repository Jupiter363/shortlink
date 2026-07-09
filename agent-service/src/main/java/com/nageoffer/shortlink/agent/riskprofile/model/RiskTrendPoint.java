package com.nageoffer.shortlink.agent.riskprofile.model;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;

import java.time.LocalDate;

public record RiskTrendPoint(
        LocalDate date,
        int riskScore,
        RiskLevel riskLevel
) {

    public RiskTrendPoint {
        riskScore = clampScore(riskScore);
        riskLevel = riskLevel == null ? RiskLevel.fromScore(riskScore) : riskLevel;
    }

    private static int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
