package com.nageoffer.shortlink.agent.riskprofile.detector;

import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskSourceStats;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class ShortLinkRiskDetector {

    private static final double TRAFFIC_SPIKE_WARNING = 2.0D;
    private static final double TRAFFIC_SPIKE_STRONG = 6.0D;
    private static final double IP_CONCENTRATION_WARNING = 0.45D;
    private static final double IP_CONCENTRATION_STRONG = 0.75D;
    private static final double PEAK_HOUR_WARNING = 0.40D;
    private static final double PEAK_HOUR_STRONG = 0.70D;
    private static final double REPEAT_VISIT_WARNING = 0.30D;
    private static final double REPEAT_VISIT_STRONG = 0.75D;
    private static final double PROFILE_CONCENTRATION_WARNING = 0.50D;
    private static final double PROFILE_CONCENTRATION_STRONG = 0.75D;

    public ShortLinkRiskProfile detect(ShortLinkRiskSourceStats stats) {
        ShortLinkRiskMetrics metrics = toMetrics(stats);
        Set<RiskReasonCode> reasonCodes = EnumSet.noneOf(RiskReasonCode.class);

        int trafficScore = scoreRange(
                valueOrZero(metrics.pvGrowth2hVs24hAvg()),
                TRAFFIC_SPIKE_WARNING,
                TRAFFIC_SPIKE_STRONG,
                RiskScoreWeights.TRAFFIC_SPIKE_MAX
        );
        if (trafficScore >= 12) {
            reasonCodes.add(RiskReasonCode.TRAFFIC_SPIKE);
        }

        int concentrationScore = scoreRange(
                max(metrics.topIpShare(), metrics.topVisitorShare()),
                IP_CONCENTRATION_WARNING,
                IP_CONCENTRATION_STRONG,
                RiskScoreWeights.IP_VISITOR_CONCENTRATION_MAX
        );
        if (concentrationScore >= 6) {
            reasonCodes.add(RiskReasonCode.IP_CONCENTRATION);
        }

        int peakHourScore = scoreRange(
                valueOrZero(metrics.peakHourShare()),
                PEAK_HOUR_WARNING,
                PEAK_HOUR_STRONG,
                RiskScoreWeights.PEAK_HOUR_BURST_MAX
        );
        if (peakHourScore >= 5) {
            reasonCodes.add(RiskReasonCode.PEAK_HOUR_BURST);
        }

        int repeatVisitScore = scoreRange(
                valueOrZero(metrics.repeatVisitRatio()),
                REPEAT_VISIT_WARNING,
                REPEAT_VISIT_STRONG,
                RiskScoreWeights.REPEAT_VISIT_MAX
        );
        if (repeatVisitScore >= 5) {
            reasonCodes.add(RiskReasonCode.HIGH_REPEAT_VISIT);
        }

        int profileConcentrationScore = profileConcentrationScore(metrics, reasonCodes);
        int riskScore = clampScore(trafficScore + concentrationScore + peakHourScore + repeatVisitScore + profileConcentrationScore);

        return new ShortLinkRiskProfile(
                stats.gid(),
                stats.domain(),
                stats.shortUri(),
                stats.fullShortUrl(),
                stats.profileWindowStart(),
                stats.profileWindowEnd(),
                metrics,
                riskScore,
                riskScore,
                RiskLevel.fromScore(riskScore),
                reasonCodes,
                RiskWatchStatus.NONE,
                List.of(),
                ""
        );
    }

    private ShortLinkRiskMetrics toMetrics(ShortLinkRiskSourceStats stats) {
        return new ShortLinkRiskMetrics(
                stats.pv2h(),
                stats.uv2h(),
                stats.pv24h(),
                stats.uv24h(),
                stats.pv7d(),
                stats.uv7d(),
                round4(pvGrowth2hVs24hAverage(stats.pv2h(), stats.pv24h())),
                stats.topIpShare(),
                stats.topVisitorShare(),
                stats.topRegionShare(),
                stats.topDeviceShare(),
                stats.topBrowserShare(),
                round4(ratio(stats.pv2h(), stats.uv2h())),
                stats.peakHourShare(),
                stats.repeatVisitRatio()
        );
    }

    private int profileConcentrationScore(ShortLinkRiskMetrics metrics, Set<RiskReasonCode> reasonCodes) {
        double topDeviceShare = valueOrZero(metrics.topDeviceShare());
        double topRegionShare = valueOrZero(metrics.topRegionShare());
        double topBrowserShare = valueOrZero(metrics.topBrowserShare());
        double strongestShare = Math.max(topDeviceShare, Math.max(topRegionShare, topBrowserShare));
        int score = scoreRange(
                strongestShare,
                PROFILE_CONCENTRATION_WARNING,
                PROFILE_CONCENTRATION_STRONG,
                RiskScoreWeights.DEVICE_REGION_BROWSER_MAX
        );
        if (score >= 5) {
            if (topDeviceShare >= PROFILE_CONCENTRATION_WARNING) {
                reasonCodes.add(RiskReasonCode.DEVICE_CONCENTRATION);
            }
            if (topRegionShare >= PROFILE_CONCENTRATION_WARNING) {
                reasonCodes.add(RiskReasonCode.REGION_CONCENTRATION);
            }
            if (topBrowserShare >= PROFILE_CONCENTRATION_WARNING) {
                reasonCodes.add(RiskReasonCode.BROWSER_CONCENTRATION);
            }
        }
        return score;
    }

    private double pvGrowth2hVs24hAverage(int pv2h, int pv24h) {
        if (pv2h <= 0 || pv24h <= 0) {
            return 0D;
        }
        return ratio(pv2h, pv24h / 12.0D);
    }

    private int scoreRange(double value, double warning, double strong, int maxScore) {
        if (value < warning) {
            return 0;
        }
        if (value >= strong) {
            return maxScore;
        }
        double progress = (value - warning) / (strong - warning);
        return clampScore((int) Math.round(maxScore * progress));
    }

    private double ratio(double numerator, double denominator) {
        if (numerator <= 0D || denominator <= 0D) {
            return 0D;
        }
        return numerator / denominator;
    }

    private double max(Double first, Double second) {
        return Math.max(valueOrZero(first), valueOrZero(second));
    }

    private double valueOrZero(Double value) {
        return value == null ? 0D : value;
    }

    private double round4(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private int clampScore(int score) {
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }
}
