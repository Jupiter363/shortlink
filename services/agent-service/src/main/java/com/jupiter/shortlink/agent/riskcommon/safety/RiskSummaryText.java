package com.jupiter.shortlink.agent.riskcommon.safety;

/** Bounds database summaries without changing the full analysis in the graph or conversation. */
public final class RiskSummaryText {

    public static final int MAX_CODE_POINTS = 2048;
    public static final String OMISSION_MARKER = "\n…（摘要已省略部分内容，完整分析见会话记录）";

    private RiskSummaryText() {
    }

    public static String forPersistence(String text) {
        if (text == null) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) {
            return text;
        }
        int prefixCodePoints = MAX_CODE_POINTS
                - OMISSION_MARKER.codePointCount(0, OMISSION_MARKER.length());
        int prefixEnd = text.offsetByCodePoints(0, prefixCodePoints);
        return text.substring(0, prefixEnd) + OMISSION_MARKER;
    }
}
