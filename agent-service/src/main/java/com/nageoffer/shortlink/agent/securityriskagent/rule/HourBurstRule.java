package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;

import java.util.List;
import java.util.Map;

class HourBurstRule implements RiskRule {

    private static final long MIN_PV_FOR_RISK = 50L;
    private static final double PEAK_HOUR_SHARE_WARNING = 0.6D;

    @Override
    public List<RiskSignal> evaluate(Map<String, Object> execution) {
        Map<String, Object> stats = RiskRuleSupport.mapValue(execution.get("data"));
        List<Object> hourRows = RiskRuleSupport.listValue(stats.get("hourStats"));
        if (hourRows.isEmpty()) {
            return List.of();
        }
        List<Long> hourValues = hourRows.stream()
                .map(RiskRuleSupport::longValue)
                .toList();
        long total = hourValues.stream().mapToLong(Long::longValue).sum();
        if (total < MIN_PV_FOR_RISK) {
            return List.of();
        }
        long peakValue = hourValues.stream().mapToLong(Long::longValue).max().orElse(0L);
        double peakShare = RiskRuleSupport.ratio(peakValue, total);
        if (peakShare < PEAK_HOUR_SHARE_WARNING) {
            return List.of();
        }
        int peakHour = hourValues.indexOf(peakValue);
        return List.of(new RiskSignal(
                "medium",
                64,
                "time",
                "hour_burst",
                "peak_hour",
                RiskRuleSupport.textValue(execution.get("name")),
                RiskRuleSupport.mapValue(execution.get("arguments")),
                RiskRuleSupport.linkedMap(
                        "totalPv", total,
                        "peakHour", (long) peakHour,
                        "peakHourPv", peakValue,
                        "peakHourShare", RiskRuleSupport.round4(peakShare)
                ),
                RiskRuleSupport.linkedMap("peakHourShareWarning", PEAK_HOUR_SHARE_WARNING),
                RiskRuleSupport.linkedMap("peakHour", peakHour),
                List.of(
                        "Check whether the peak hour matches a planned campaign launch.",
                        "If no campaign event explains the burst, sample access records around the peak hour."
                )
        ));
    }
}
