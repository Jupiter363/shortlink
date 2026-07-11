package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

class TopIpConcentrationRule implements RiskRule {

    private static final long MIN_PV_FOR_RISK = 50L;
    private static final double TOP_IP_SHARE_WARNING = 0.3D;

    private final SecurityRiskSanitizer sanitizer;

    TopIpConcentrationRule(SecurityRiskSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    public List<RiskSignal> evaluate(Map<String, Object> execution) {
        Map<String, Object> stats = RiskRuleSupport.mapValue(execution.get("data"));
        long pv = RiskRuleSupport.longValue(stats.get("pv"));
        if (pv < MIN_PV_FOR_RISK) {
            return List.of();
        }
        List<Object> topIpRows = RiskRuleSupport.listValue(stats.get("topIpStats"));
        if (topIpRows.isEmpty()) {
            return List.of();
        }
        Map<String, Object> topIp = topIpRows.stream()
                .map(RiskRuleSupport::mapValue)
                .filter(each -> !RiskRuleSupport.textValue(each.get("maskedIp")).isBlank())
                .filter(each -> RiskRuleSupport.longValue(each.get("cnt")) > 0L)
                .max(Comparator.comparingLong(each -> RiskRuleSupport.longValue(each.get("cnt"))))
                .orElse(Map.of());
        if (topIp.isEmpty()) {
            return List.of();
        }
        long topIpCount = RiskRuleSupport.longValue(topIp.get("cnt"));
        double topIpShare = RiskRuleSupport.ratio(topIpCount, pv);
        if (topIpShare < TOP_IP_SHARE_WARNING) {
            return List.of();
        }
        return List.of(new RiskSignal(
                "high",
                78,
                "traffic",
                "top_ip_concentration",
                "ip_concentration",
                RiskRuleSupport.textValue(execution.get("name")),
                RiskRuleSupport.mapValue(execution.get("arguments")),
                RiskRuleSupport.linkedMap(
                        "pv", pv,
                        "topIpCount", topIpCount,
                        "topIpShare", RiskRuleSupport.round4(topIpShare)
                ),
                RiskRuleSupport.linkedMap(
                        "topIpShareWarning", TOP_IP_SHARE_WARNING,
                        "minPv", MIN_PV_FOR_RISK
                ),
                RiskRuleSupport.linkedMap(
                        "maskedTopIp", sanitizer.sanitizeText(RiskRuleSupport.textValue(topIp.get("maskedIp"))),
                        "topIpCount", topIpCount
                ),
                List.of(
                        "Review whether the top IP segment matches expected delivery traffic.",
                        "Check channel source and campaign placement for abnormal repeated access."
                )
        ));
    }
}
