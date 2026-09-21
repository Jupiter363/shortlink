package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.WireRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A narrow orchestration seam for a statistics CALL containing independent child queries.
 * The supplied {@link CampaignCallExecution} remains responsible for durable child/artifact
 * receipts, fencing and retry identity; this class only preserves ordering and outcome facts.
 * It deliberately does not call a gateway, create a RunToken, or aggregate business payloads.
 */
public final class CampaignStatisticsCompositeCall {
    public record Request(String childId, String actionId, ChildMode mode, String requestId,
                          WireRequest wire, CampaignStepExecution.ChildCall call) {
        public Request {
            text(childId, "COMPOSITE_CHILD_ID_REQUIRED");
            text(actionId, "COMPOSITE_ACTION_ID_REQUIRED");
            text(requestId, "COMPOSITE_REQUEST_ID_REQUIRED");
            if (mode != ChildMode.SYNC && mode != ChildMode.ASYNC)
                throw new IllegalArgumentException("COMPOSITE_CHILD_MODE_INVALID");
            Objects.requireNonNull(wire, "COMPOSITE_WIRE_REQUIRED");
            Objects.requireNonNull(call, "COMPOSITE_CALL_REQUIRED");
        }

        public ChildSpec spec() {
            return new ChildSpec(childId, actionId, mode, requestId, wire);
        }
    }

    public record Failure(String childId, String code) {
        public Failure {
            text(childId, "COMPOSITE_FAILURE_CHILD_REQUIRED");
            text(code, "COMPOSITE_FAILURE_CODE_REQUIRED");
        }
    }

    public record Outcome(List<ChildRecord> children, List<String> readyChildIds,
                          List<String> waitingChildIds, List<String> waitingJobs,
                          List<Failure> failures) {
        public Outcome {
            children = List.copyOf(children);
            readyChildIds = List.copyOf(readyChildIds);
            waitingChildIds = List.copyOf(waitingChildIds);
            waitingJobs = List.copyOf(waitingJobs);
            failures = List.copyOf(failures);
        }

        public boolean waiting() { return !waitingChildIds.isEmpty(); }
        public boolean complete() { return failures.isEmpty() && !waiting(); }
    }

    @FunctionalInterface
    interface Runner {
        ChildRecord run(Request request) throws Exception;
    }

    /** Runs each child through the durable CALL boundary; existing READY/WAITING receipts are reused. */
    public Outcome execute(CampaignCallExecution execution, List<Request> requests) throws Exception {
        Objects.requireNonNull(execution);
        return execute(requests, request -> execution.child(request.spec(), request.call()));
    }

    /** Package-private seam keeps orchestration tests independent from a database fixture. */
    Outcome execute(List<Request> requests, Runner runner) throws Exception {
        Objects.requireNonNull(runner);
        List<Request> planned = validate(requests);
        List<ChildRecord> children = new ArrayList<>();
        List<String> ready = new ArrayList<>(), waiting = new ArrayList<>(), jobs = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Request request : planned) {
            try {
                ChildRecord child = Objects.requireNonNull(runner.run(request), "COMPOSITE_CHILD_RESULT_MISSING");
                if (!request.spec().equals(child.spec())) throw new IllegalStateException("COMPOSITE_CHILD_IDENTITY_MISMATCH");
                children.add(child);
                if (child.state() == CampaignRunStore.ChildState.READY) ready.add(request.childId());
                else if (child.state() == CampaignRunStore.ChildState.WAITING) {
                    waiting.add(request.childId());
                    if (child.jobId() != null && !child.jobId().isBlank()) jobs.add(child.jobId());
                } else failures.add(new Failure(request.childId(), child.reason() == null
                        ? "COMPOSITE_CHILD_NOT_READY" : child.reason().name()));
            } catch (SecurityException fenced) {
                throw fenced;
            } catch (Exception failed) {
                failures.add(new Failure(request.childId(), code(failed)));
            }
        }
        return new Outcome(children, ready, waiting, jobs, failures);
    }

    private static List<Request> validate(List<Request> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 16)
            throw new IllegalArgumentException("COMPOSITE_CHILD_COUNT_INVALID");
        List<Request> result = List.copyOf(requests);
        Set<String> childIds = new HashSet<>(), requestIds = new HashSet<>();
        for (Request request : result) {
            Objects.requireNonNull(request, "COMPOSITE_CHILD_REQUIRED");
            if (!childIds.add(request.childId())) throw new IllegalArgumentException("COMPOSITE_CHILD_DUPLICATE");
            if (!requestIds.add(request.requestId())) throw new IllegalArgumentException("COMPOSITE_REQUEST_DUPLICATE");
        }
        return result;
    }

    private static String code(Exception failed) {
        String message = failed.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{0,95}") ? message : "COMPOSITE_CHILD_FAILED";
    }

    private static void text(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(code);
    }
}
