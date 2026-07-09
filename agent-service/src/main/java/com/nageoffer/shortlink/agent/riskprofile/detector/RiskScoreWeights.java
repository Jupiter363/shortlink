package com.nageoffer.shortlink.agent.riskprofile.detector;

public final class RiskScoreWeights {

    public static final int TRAFFIC_SPIKE_MAX = 25;
    public static final int IP_VISITOR_CONCENTRATION_MAX = 25;
    public static final int PEAK_HOUR_BURST_MAX = 20;
    public static final int REPEAT_VISIT_MAX = 15;
    public static final int DEVICE_REGION_BROWSER_MAX = 15;

    private RiskScoreWeights() {
    }
}
