package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress;

import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed read-to-response seam for one durable campaign run.
 *
 * <p>The service deliberately owns no store, graph, HTTP, or Spring state.  It performs exactly
 * one E71 read, rejects a projection that does not belong to the requested run revision, and
 * delegates all report/status sanitization to the E76 response adapter.  A missing binding stays
 * an empty response; this boundary never falls back to an older revision or to the graph result
 * text.</p>
 */
public final class CampaignDurableRunResponseService {
    private final CampaignDurableRunResultReadService reads;
    private final CampaignDurableRunResponseAdapter responses;

    public CampaignDurableRunResponseService(CampaignDurableRunResultReadService reads,
                                             CampaignDurableRunResponseAdapter responses) {
        this.reads = Objects.requireNonNull(reads, "RUN_RESULT_READ_SERVICE_REQUIRED");
        this.responses = Objects.requireNonNull(responses, "RUN_RESULT_RESPONSE_ADAPTER_REQUIRED");
    }

    /**
     * Reads exactly the requested run revision and, when present, composes its sanitized response.
     */
    public Optional<AgentRunResult> read(Request request) {
        Objects.requireNonNull(request, "RUN_RESULT_RESPONSE_REQUEST_REQUIRED");
        Optional<CampaignRunResultProjection.Projection> projection = Objects.requireNonNull(
                reads.read(request.readRequest()), "RUN_RESULT_READ_REQUIRED");
        if (projection.isEmpty()) return Optional.empty();

        CampaignRunResultProjection.Projection value = projection.get();
        if (!request.readRequest().runId().equals(value.runId())
                || request.readRequest().revision() != value.revision()) {
            throw new IllegalStateException("RUN_RESULT_IDENTITY_MISMATCH");
        }
        if (request.expectedPlanId().isPresent()
                && !request.expectedPlanId().get().equals(value.planId())) {
            throw new IllegalStateException("RUN_RESULT_PLAN_IDENTITY_MISMATCH");
        }

        CampaignDurableRunResponseAdapter.Identity identity =
                new CampaignDurableRunResponseAdapter.Identity(
                        request.readRequest().runId(), request.expectedPlanId().orElse(value.planId()),
                        request.readRequest().revision());
        return responses.adapt(new CampaignDurableRunResponseAdapter.Request(
                request.base(), Optional.of(identity), Optional.of(value)));
    }

    /** Explicit base response plus one exact E71 read request. */
    public record Request(AgentRunResult base,
                          CampaignDurableRunResultReadService.Request readRequest,
                          Optional<String> expectedPlanId) {
        public Request {
            Objects.requireNonNull(base, "AGENT_BASE_RESULT_REQUIRED");
            Objects.requireNonNull(readRequest, "RUN_RESULT_READ_REQUEST_REQUIRED");
            expectedPlanId = expectedPlanId == null ? Optional.empty() : expectedPlanId;
            expectedPlanId.ifPresent(planId -> {
                if (planId.isBlank() || planId.length() > 96) {
                    throw new IllegalArgumentException("RUN_RESULT_PLAN_ID_INVALID");
                }
            });
        }

        public Request(AgentRunResult base,
                       CampaignDurableRunResultReadService.Request readRequest) {
            this(base, readRequest, Optional.empty());
        }

        public Request(AgentRunResult base,
                       CampaignDurableRunResultReadService.Request readRequest,
                       String expectedPlanId) {
            this(base, readRequest, Optional.ofNullable(expectedPlanId));
        }
    }
}
