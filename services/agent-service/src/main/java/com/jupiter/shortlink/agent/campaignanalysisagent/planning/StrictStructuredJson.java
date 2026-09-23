package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import java.util.regex.Pattern;

/** Presentation-only normalization of an already bounded, durably recorded model response. */
public final class StrictStructuredJson {
    private static final Pattern FENCE = Pattern.compile(
            "\\A```(?:(?i:json))?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```[ \\t]*\\z");
    private StrictStructuredJson() {}

    /**
     * Callers bound the original text before calling this method, then apply their unchanged strict
     * JSON/schema reader. This neither searches for a plausible object nor rewrites the saved receipt.
     * Prose and multiple blocks remain invalid input to that reader.
     */
    public static String unwrapSingleFence(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("STRUCTURED_JSON_ENVELOPE_INVALID");
        String value = encoded.strip();
        if (!value.startsWith("```")) return encoded;
        var fence = FENCE.matcher(value);
        if (!fence.matches()) throw new IllegalArgumentException("STRUCTURED_JSON_ENVELOPE_INVALID");
        return fence.group(1);
    }
}
