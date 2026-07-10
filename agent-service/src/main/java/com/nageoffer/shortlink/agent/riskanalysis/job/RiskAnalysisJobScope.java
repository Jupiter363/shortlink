package com.nageoffer.shortlink.agent.riskanalysis.job;

public record RiskAnalysisJobScope(
        String gid,
        String graphName,
        String graphVersion
) implements Comparable<RiskAnalysisJobScope> {

    public RiskAnalysisJobScope {
        gid = requireText(gid, "gid");
        graphName = requireText(graphName, "graphName");
        graphVersion = requireText(graphVersion, "graphVersion");
    }

    @Override
    public int compareTo(RiskAnalysisJobScope other) {
        int gidComparison = gid.compareTo(other.gid);
        if (gidComparison != 0) {
            return gidComparison;
        }
        int graphNameComparison = graphName.compareTo(other.graphName);
        if (graphNameComparison != 0) {
            return graphNameComparison;
        }
        return graphVersion.compareTo(other.graphVersion);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
