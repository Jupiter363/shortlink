package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding;

/** Safe, closed failures; never include an artifact's payload or plan-supplied text. */
public final class BindingException extends IllegalArgumentException {
    public enum Code {
        INVALID_BINDING, TYPE_MISMATCH, ARTIFACT_CONTRACT_MISMATCH,
        UPSTREAM_OUTPUT_UNAVAILABLE, INPUT_ACCESS_DENIED, OUTPUT_CONTRACT_MISMATCH
    }

    private final Code code;

    public BindingException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
