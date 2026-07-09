package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;

import java.util.List;
import java.util.Map;

class HighRepeatVisitRule implements RiskRule {

    private static final long MIN_PV_FOR_RISK = 50L;
    private static final double PV_PER_UV_WARNING = 5.0D;

    @Override
    public List<RiskSignal> evaluate(Map<String, Object> execution) {
        Map<String, Object> stats = RiskRuleSupport.mapValue(execution.get("data"));
        long pv = RiskRuleSupport.longValue(stats.get("pv"));
        long uv = RiskRuleSupport.longValue(stats.get("uv"));
        if (pv < MIN_PV_FOR_RISK || uv <= 0) {
            return List.of();
        }
        double pvPerUv = RiskRuleSupport.ratio(pv, uv);
        if (pvPerUv < PV_PER_UV_WARNING) {
            return List.of();
        }
        return List.of(new RiskSignal(
                "medium",
                68,
                "traffic",
                "high_repeat_visits",
                "repeat_visit",
                RiskRuleSupport.textValue(execution.get("name")),
                RiskRuleSupport.mapValue(execution.get("arguments")),
                RiskRuleSupport.linkedMap(
                        "pv", pv,
                        "uv", uv,
                        "pvPerUv", RiskRuleSupport.round4(pvPerUv)
                ),
                RiskRuleSupport.linkedMap(
                        "pvPerUvWarning", PV_PER_UV_WARNING,
                        "minPv", MIN_PV_FOR_RISK
                ),
                RiskRuleSupport.linkedMap("message", "PV is high relative to UV"),
                List.of(
                        "Compare repeated visits with expected campaign mechanics.",
                        "Sample access records to confirm whether repeat visits share IP or device fingerprints."
                )
        ));
    }
}
