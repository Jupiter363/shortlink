package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One backend reconciliation of an uncertain POST. No model tool, retry loop or fresh submission. */
public final class StatisticsSubmissionReconciler {
    private static final String SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignRunStore store;
    private final ShortLinkBusinessGateway gateway;

    public enum Outcome { RECOVERED, KNOWN_JOB, ALREADY_READY, NOT_DISPATCHED, UNRESOLVED, STOPPED }
    /** A recovered job identity is not a completed or published statistics Artifact. */
    public record Result(String childId, Outcome outcome, String jobId, String code) {}

    public StatisticsSubmissionReconciler(CampaignRunStore store, ShortLinkBusinessGateway gateway) {
        this.store = Objects.requireNonNull(store);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public Result recover(RunToken token, String childId, AgentPrincipal current) {
        requireCurrentPrincipal(token, current);
        ChildRecord child = store.child(token, childId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown campaign child reference"));
        if (child.state() == ChildState.READY) return result(childId, Outcome.ALREADY_READY, child.jobId(), null);
        if (child.jobId() != null) return result(childId, Outcome.KNOWN_JOB, child.jobId(), null);
        if (child.state() == ChildState.PREPARED) return result(childId, Outcome.NOT_DISPATCHED, null, null);
        if (child.spec().mode() == ChildMode.SYNC)
            return result(childId, Outcome.UNRESOLVED, null, "READ_RESULT_UNKNOWN");
        if (child.state() != ChildState.UNRESOLVED || child.callbackActive())
            return result(childId, Outcome.UNRESOLVED, null, "EXECUTION_UNRESOLVED");

        // Only the original persisted statistics POST is recoverable through this protocol.
        Map<String, Object> request = frozenSubmission(child);
        DispatchPermit permit = store.beginReconciliation(token, childId);
        try {
            if (!store.mayDispatch(permit)) return result(childId, Outcome.STOPPED, null, "RUN_FENCED");
            var context = new ToolContext(token.definition().sessionId(), current.username(), request, current);
            ToolResult response;
            try {
                response = gateway.recoverExistingStatisticsJob(context, request);
            } catch (RuntimeException unavailable) {
                store.markUnresolved(permit);
                return result(childId, Outcome.UNRESOLVED, null, "REMOTE_UNAVAILABLE");
            }
            if (response == null || !response.success()) {
                store.markUnresolved(permit);
                return result(childId, Outcome.UNRESOLVED, null, failureCode(response));
            }
            if (!(response.data() instanceof Map<?, ?> data)
                    || !(data.get("jobId") instanceof String jobId) || !jobId.matches("[A-Za-z0-9_-]{1,128}")
                    || !(data.get("state") instanceof String state)
                    || !Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED").contains(state)) {
                store.markUnresolved(permit);
                return result(childId, Outcome.UNRESOLVED, null, "RECOVERY_PROTOCOL_UNAVAILABLE");
            }
            // Even remote SUCCEEDED needs separate result ingestion; WAITING is a known identity.
            if (!store.mayDispatch(permit)) {
                store.recordLateJob(permit, jobId);
                return result(childId, Outcome.STOPPED, jobId, "RUN_FENCED");
            }
            try {
                store.recordWaiting(permit, jobId);
            } catch (IllegalStateException fenced) {
                if (store.mayDispatch(permit)) throw fenced;
                store.recordLateJob(permit, jobId);
                return result(childId, Outcome.STOPPED, jobId, "RUN_FENCED");
            }
            return result(childId, Outcome.RECOVERED, jobId, null);
        } finally {
            // A completed/failed HTTP callback has really exited here; native Future timing is irrelevant.
            store.callbackExited(permit);
        }
    }

    private static Map<String, Object> frozenSubmission(ChildRecord child) {
        WireRequest wire = child.spec().wire();
        if (!"POST".equals(wire.method()) || !SUBMIT_PATH.equals(wire.path()))
            throw new IllegalArgumentException("Child is not a frozen statistics submission");
        try {
            Map<String, Object> request = JSON.readValue(wire.bodyJson(), new TypeReference<>() {});
            if (request == null || !child.spec().requestId().equals(request.get("requestId")))
                throw new IllegalArgumentException("Frozen request identity does not match the child ledger");
            return request;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid frozen statistics submission", invalid);
        }
    }

    private static void requireCurrentPrincipal(RunToken token, AgentPrincipal current) {
        Caller owner = token.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("Current principal does not own the campaign run");
    }

    private static String failureCode(ToolResult response) {
        return response != null && response.data() instanceof Map<?, ?> data
                && data.get("code") instanceof String code && code.matches("[A-Z][A-Z0-9_]{0,63}")
                ? code : "RECOVERY_PROTOCOL_UNAVAILABLE";
    }

    private static Result result(String childId, Outcome outcome, String jobId, String code) {
        return new Result(childId, outcome, jobId, code);
    }
}
