package com.jupiter.shortlink.membership;

public record AppliedCut(String generation, long revision, long memberCount) {
    public AppliedCut {
        if (generation == null || generation.isBlank() || revision < 0 || memberCount < 0)
            throw new IllegalArgumentException("Invalid membership cut");
    }
}
