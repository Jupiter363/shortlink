package com.jupiter.shortlink.membership;

public record ControlSnapshot(String namespace, String generation, long revision, long memberCount,
                              Mode mode, boolean baselineReady) {
    public AppliedCut cut() { return new AppliedCut(generation, revision, memberCount); }
}
