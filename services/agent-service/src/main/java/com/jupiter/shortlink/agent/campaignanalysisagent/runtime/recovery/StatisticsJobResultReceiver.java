package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.Receipt;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore.ReceiptSpec;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One admitted read pass over an existing job. No submission, polling, model or Spring registration. */
public final class StatisticsJobResultReceiver {
    public enum Outcome { ALREADY_READY, WAITING, RECEIVING, READY, BLOCKED, STOPPED, NOT_APPLICABLE }
    /** Trusted executor output binding, stable across restarts; never supplied by the model. */
    public record Target(String artifactId, String scopeRef, String periodsRef) {
        public Target {
            if (artifactId == null || artifactId.isBlank() || scopeRef == null || scopeRef.isBlank()
                    || periodsRef == null || periodsRef.isBlank()) throw new IllegalArgumentException("RESULT_TARGET_REQUIRED");
        }
    }
    public record Result(String childId, String jobId, Outcome outcome, String code,
                         int receivedPages, int nextPageIndex, String artifactId) {}

    private final CampaignRunStore runs;
    private final CampaignStatisticsResultStore results;
    private final ShortLinkBusinessGateway gateway;
    private final Clock clock;
    private final int pagesPerPass;

    public StatisticsJobResultReceiver(CampaignRunStore runs, CampaignStatisticsResultStore results,
            ShortLinkBusinessGateway gateway, Clock clock) {
        this(runs, results, gateway, clock, 8);
    }

    /** Work quantum only; every later pass continues the durable cursor, with no total-page cap. */
    public StatisticsJobResultReceiver(CampaignRunStore runs, CampaignStatisticsResultStore results,
            ShortLinkBusinessGateway gateway, Clock clock, int pagesPerPass) {
        this.runs = Objects.requireNonNull(runs);
        this.results = Objects.requireNonNull(results);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        if (pagesPerPass < 1) throw new IllegalArgumentException("Positive page work quantum required");
        this.pagesPerPass = pagesPerPass;
    }

    public Result receive(RunToken token, String childId, AgentPrincipal current, Target target,
                          BooleanSupplier authorized) {
        requirePrincipal(token, current);
        Objects.requireNonNull(target);
        Objects.requireNonNull(authorized);
        if (!authorized.getAsBoolean()) return result(childId, null, Outcome.STOPPED, "RUN_ACCESS_DENIED", null);
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> new IllegalArgumentException("CHILD_NOT_FOUND"));
        String jobId = child.jobId();
        if (child.state() == ChildState.READY)
            return new Result(childId, jobId, target.artifactId().equals(child.artifactId()) ? Outcome.ALREADY_READY : Outcome.BLOCKED,
                    target.artifactId().equals(child.artifactId()) ? null : "RESULT_TARGET_CHANGED", 0, 0, child.artifactId());
        if (child.spec().mode() != ChildMode.ASYNC || jobId == null)
            return result(childId, jobId, Outcome.NOT_APPLICABLE, "KNOWN_ASYNC_JOB_REQUIRED", null);
        if (child.callbackActive() || child.state() == ChildState.DISPATCHING)
            return result(childId, jobId, Outcome.BLOCKED, "EXECUTION_UNRESOLVED", null);
        StatisticsJobResultProtocol protocol;
        try { protocol = new StatisticsJobResultProtocol(child); }
        catch (IllegalArgumentException unsupported) {
            return result(childId, jobId, Outcome.BLOCKED, safeCode(unsupported.getMessage(), "RESULT_REQUEST_UNSUPPORTED"), null);
        }
        Receipt receipt = results.receipt(token, childId).orElse(null);
        var context = new ToolContext(token.definition().sessionId(), current.username(), protocol.request(), current);
        DispatchPermit permit = runs.beginReconciliation(token, childId);
        try {
            if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
            ToolResult response = status(context, jobId);
            if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
            if (response == null || !response.success()) return result(childId, jobId, Outcome.BLOCKED, failureCode(response), receipt);
            var status = protocol.status(response.data());
            if ("FAILED".equals(status.state()) || "CANCELLED".equals(status.state()))
                return result(childId, jobId, Outcome.BLOCKED, "REMOTE_JOB_" + status.state(), receipt);
            if (!"SUCCEEDED".equals(status.state())) {
                if (receipt != null) return result(childId, jobId, Outcome.BLOCKED, "STATISTICS_RESULT_CHANGED", receipt);
                runs.recordWaiting(permit, jobId);
                return result(childId, jobId, Outcome.WAITING, null, null);
            }
            if (status.expiresAtMillis() <= clock.millis())
                return result(childId, jobId, Outcome.BLOCKED, "SNAPSHOT_EXPIRED", receipt);
            receipt = results.initialize(permit, new ReceiptSpec(jobId, child.spec().wire().hash(), target.artifactId(),
                    target.scopeRef(), target.periodsRef(), status.totalRows(), status.pageCount(), status.expiresAtMillis()));
            for (int received = 0; received < pagesPerPass && !receipt.complete(); received++) {
                if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
                ToolResult page = page(context, jobId, receipt.nextPageIndex());
                if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
                if (page == null || !page.success()) return result(childId, jobId, Outcome.BLOCKED, failureCode(page), receipt);
                var validated = protocol.page(status, page.data(), receipt.nextPageIndex());
                if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
                receipt = results.append(permit, validated);
            }
            if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
            if (receipt.complete()) {
                ArtifactRef artifact = results.publish(permit);
                return new Result(childId, jobId, Outcome.READY, null, receipt.storedPages(), receipt.nextPageIndex(), artifact.artifactId());
            }
            runs.recordWaiting(permit, jobId);
            return result(childId, jobId, Outcome.RECEIVING, null, receipt);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException rejected) {
            if (!live(permit, authorized)) return stopped(childId, jobId, receipt);
            return result(childId, jobId, Outcome.BLOCKED, safeCode(rejected.getMessage(), "RESULT_RECEPTION_UNAVAILABLE"), receipt);
        } finally {
            try {
                try { if (runs.mayDispatch(permit)) runs.markUnresolved(permit); }
                catch (IllegalStateException fenced) { if (runs.mayDispatch(permit)) throw fenced; }
            } finally {
                // This finally denotes the real callback exit; cancellation/timeout never simulates it.
                runs.callbackExited(permit);
            }
        }
    }

    private boolean live(DispatchPermit permit, BooleanSupplier authorized) {
        return authorized.getAsBoolean() && runs.mayDispatch(permit);
    }
    private ToolResult status(ToolContext context, String jobId) {
        try { return gateway.readStatisticsJob(context, jobId); }
        catch (RuntimeException unavailable) { return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), null); }
    }
    private ToolResult page(ToolContext context, String jobId, int index) {
        try { return gateway.readStatisticsJobPage(context, jobId, index, 500); }
        catch (RuntimeException unavailable) { return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), null); }
    }
    private static void requirePrincipal(RunToken token, AgentPrincipal current) {
        var owner = token.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("RESULT_PRINCIPAL_MISMATCH");
    }
    private static String failureCode(ToolResult response) {
        return response != null && response.data() instanceof Map<?, ?> data && data.get("code") instanceof String code
                ? safeCode(code, "STATISTICS_READ_PROTOCOL_UNAVAILABLE") : "STATISTICS_READ_PROTOCOL_UNAVAILABLE";
    }
    private static String safeCode(String code, String fallback) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : fallback;
    }
    private static Result stopped(String child, String job, Receipt receipt) {
        return result(child, job, Outcome.STOPPED, "RESULT_AUTHORITY_REVOKED", receipt);
    }
    private static Result result(String child, String job, Outcome outcome, String code, Receipt receipt) {
        return new Result(child, job, outcome, code, receipt == null ? 0 : receipt.storedPages(),
                receipt == null ? 0 : receipt.nextPageIndex(), receipt != null && receipt.published() ? receipt.spec().artifactId() : null);
    }
}
