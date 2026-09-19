package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Request;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry.Response;
import java.util.function.Supplier;

/**
 * Trusted persistence boundary around one native model call; it never owns the agent loop.
 * Implementations may return an already saved public response without invoking liveCall.
 * The native adapter must project and validate the request and response before crossing here.
 */
@FunctionalInterface
public interface ModelCallBoundary {
    Response call(Request actualRequest, Supplier<Response> liveCall);
}
