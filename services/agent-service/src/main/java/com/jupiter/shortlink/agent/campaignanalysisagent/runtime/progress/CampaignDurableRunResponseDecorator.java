package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.ReportLifecycleStore;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Request-bound response seam for a durable campaign run.
 *
 * <p>The decorator makes the missing-binding decision explicit.  A caller may decide how to
 * render {@link Outcome.Status#NO_BINDING}, but this class never silently substitutes the graph
 * result or performs a latest-revision lookup.  Run, plan and revision are supplied by a trusted
 * server-side handle; none is inferred from session, trace or user text.</p>
 */
public final class CampaignDurableRunResponseDecorator {
    private final CampaignDurableRunResponseService responses;

    public CampaignDurableRunResponseDecorator(CampaignDurableRunResponseService responses) {
        this.responses = Objects.requireNonNull(responses, "RUN_RESULT_RESPONSE_SERVICE_REQUIRED");
    }

    /** Composes one exact durable response without introducing a fallback path. */
    public Outcome decorate(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_DECORATION_REQUEST_REQUIRED");
        Optional<AgentRunResult> response = responses.read(new CampaignDurableRunResponseService.Request(
                request.base(), request.readRequest(), request.expectedPlanId()));
        return response.map(value -> Outcome.bound(value)).orElseGet(Outcome::noBinding);
    }

    public record Request(AgentRunResult base,
                          CampaignDurableRunResultReadService.Request readRequest,
                          String expectedPlanId) {
        public Request {
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            Objects.requireNonNull(readRequest, "RUN_RESULT_READ_REQUEST_REQUIRED");
            ReportLifecycleStore.require(expectedPlanId, "RUN_RESULT_PLAN_ID_INVALID");
        }
    }

    public record Outcome(Status status, Optional<AgentRunResult> response) {
        public Outcome {
            Objects.requireNonNull(status, "RUN_RESULT_OUTCOME_STATUS_REQUIRED");
            response = response == null ? Optional.empty() : response;
            if (status == Status.BOUND_RESPONSE && response.isEmpty())
                throw new IllegalArgumentException("RUN_RESULT_BOUND_RESPONSE_REQUIRED");
            if (status == Status.NO_BINDING && response.isPresent())
                throw new IllegalArgumentException("RUN_RESULT_NO_BINDING_RESPONSE_FORBIDDEN");
        }

        static Outcome bound(AgentRunResult response) {
            return new Outcome(Status.BOUND_RESPONSE, Optional.of(response));
        }

        static Outcome noBinding() {
            return new Outcome(Status.NO_BINDING, Optional.empty());
        }

        public enum Status {
            BOUND_RESPONSE,
            NO_BINDING
        }
    }
}
