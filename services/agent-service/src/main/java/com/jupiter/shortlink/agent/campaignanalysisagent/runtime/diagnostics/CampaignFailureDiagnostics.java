package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.diagnostics;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Operational diagnostics without prompt text, request identities, SQL, or provider messages. */
public final class CampaignFailureDiagnostics {
    private CampaignFailureDiagnostics() {}
    private static final Set<String> CODES = Set.of(
            "PLANNER_SCHEMA_INVALID", "PLANNER_SCHEMA_NOT_IN_PROMPT", "PLANNER_REQUEST_TOO_LARGE",
            "PLANNER_GENERATION_REJECTED", "PLANNER_NATIVE_RESULT_INVALID", "PLANNER_EXTRA_MODEL_CALL_REJECTED",
            "PLANNER_TOOLS_FORBIDDEN", "PLANNER_MODEL_RESPONSE_UNAVAILABLE", "PLANNER_MODEL_CONTENT_REJECTED",
            "PLANNER_MODEL_PROPERTIES_REJECTED", "PLANNER_MODEL_BOUNDARY_REJECTED", "PLANNER_RESPONSE_TOO_LARGE",
            "PROCESS_EXECUTION_SCOPE_CLOSED", "MODEL_REQUEST_REQUIRED", "MODEL_TOOLS_REQUIRED",
            "MODEL_DYNAMIC_TOOLS_UNSUPPORTED", "MODEL_TOOLS_INVALID", "MODEL_RUNTIME_OPTIONS_UNSUPPORTED",
            "MODEL_GENERATION_OPTIONS_CHANGED", "MODEL_GENERATION_OPTIONS_INVALID", "MODEL_GENERATION_OPTIONS_EMPTY",
            "MODEL_TOOL_CALLBACK_UNAPPROVED", "MODEL_TOOL_SELECTION_INVALID", "MODEL_TOOL_DESCRIPTION_CHANGED",
            "MODEL_TOOL_SCHEMA_INVALID", "MODEL_MESSAGES_REQUIRED", "MODEL_MESSAGE_REQUIRED",
            "MODEL_MEDIA_UNSUPPORTED", "MODEL_TOOL_RESPONSE_EMPTY", "MODEL_TOOL_RESPONSE_INVALID",
            "MODEL_MESSAGE_TYPE_UNSUPPORTED", "MODEL_MESSAGE_PROPERTIES_UNSUPPORTED",
            "CAMPAIGN_REQUEST_ACCESS_DENIED", "CAMPAIGN_PRINCIPAL_CHANGED", "CAMPAIGN_REQUEST_EXPIRED",
            "CAMPAIGN_INTERPRETATION_UNRESOLVED", "CAMPAIGN_INTERPRETATION_PROMPT_CHANGED",
            "CAMPAIGN_INTERPRETATION_CHANGED", "CAMPAIGN_INTERPRETATION_RESPONSE_INVALID");

    /** Never call Throwable.toString/printStackTrace or expose an unregistered exception message. */
    public static String describe(Throwable failure) {
        var diagnostic = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; failure != null && depth < 5 && seen.add(failure); depth++, failure = failure.getCause()) {
            if (depth > 0) diagnostic.append(" <- ");
            diagnostic.append(failure.getClass().getName());
            String message = failure.getMessage();
            if (message != null && CODES.contains(message)) diagnostic.append('[').append(message).append(']');
            if (failure instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[A-Z0-9]{5}")) diagnostic.append(" sqlState=").append(state);
                diagnostic.append(" vendorCode=").append(sql.getErrorCode());
            }
            StackTraceElement[] frames = failure.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 5); index++) {
                StackTraceElement frame = frames[index];
                diagnostic.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                        .append(':').append(frame.getLineNumber());
            }
        }
        return diagnostic.toString();
    }
}
