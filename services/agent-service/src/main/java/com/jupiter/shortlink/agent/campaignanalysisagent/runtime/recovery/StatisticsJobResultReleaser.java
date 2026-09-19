package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsReleaseStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsReleaseStore.Intent;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsReleaseStore.State;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One admitted release pass. Durable local proof precedes intent; status precedes any release retry. */
public final class StatisticsJobResultReleaser {
    public enum Outcome { NOT_APPLICABLE, ALREADY_CONFIRMED, CONFIRMED, BLOCKED, STOPPED }
    public record Result(String childId, String jobId, Outcome outcome, String code) {}
    private final CampaignRunStore runs;
    private final CampaignStatisticsReleaseStore releases;
    private final ShortLinkBusinessGateway gateway;

    public StatisticsJobResultReleaser(CampaignRunStore runs, CampaignStatisticsReleaseStore releases,
                                      ShortLinkBusinessGateway gateway) {
        this.runs = Objects.requireNonNull(runs);
        this.releases = Objects.requireNonNull(releases);
        this.gateway = Objects.requireNonNull(gateway);
    }

    /**
     * Both authorization callbacks must reflect this admitted operation's current trusted grant.
     * They must not hide HTTP inside a database transaction. No unregistered remote consumer is supported.
     */
    public Result release(RunToken token, String childId, AgentPrincipal current,
                          ArtifactAuthorizer artifactAuthorizer, BooleanSupplier authorized) {
        requirePrincipal(token, current);
        Objects.requireNonNull(artifactAuthorizer);
        Objects.requireNonNull(authorized);
        if (!current(token, authorized)) return result(childId, null, Outcome.STOPPED, "RUN_ACCESS_DENIED");
        ChildRecord child = runs.child(token, childId).orElseThrow(() -> new IllegalArgumentException("CHILD_NOT_FOUND"));
        if (child.spec().mode() != ChildMode.ASYNC || child.state() != ChildState.READY || child.jobId() == null
                || !StatisticsJobResultProtocol.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()))
            return result(childId, child.jobId(), Outcome.NOT_APPLICABLE, "MANAGED_READY_RESULT_REQUIRED");
        if (child.callbackActive()) return result(childId, child.jobId(), Outcome.BLOCKED, "EXECUTION_UNRESOLVED");
        DispatchPermit permit = null;
        try {
            var existing = releases.intent(token, childId);
            if (existing.isPresent() && existing.get().state() == State.CONFIRMED)
                return result(childId, child.jobId(), Outcome.ALREADY_CONFIRMED, null);
            Map<String, Object> original = StatisticsJobResultProtocol.originalRequest(child.spec());
            Intent intent = releases.prepare(token, childId, artifactAuthorizer);
            if (!current(token, authorized)) return result(childId, child.jobId(), Outcome.STOPPED, "RELEASE_AUTHORITY_REVOKED");
            permit = runs.beginRelease(token, childId);
            var context = new ToolContext(token.definition().sessionId(), current.username(), original, current);
            if (!live(permit, intent, authorized)) return stopped(childId, child.jobId());
            ToolResult status = status(context, child.jobId());
            if (status == null || !status.success()) return failed(childId, child.jobId(), status);
            if (!(status.data() instanceof Map<?, ?> data)) return protocolFailure(childId, child.jobId());
            if (released(data, intent)) {
                // Exact late facts may be retained after cancellation, without reviving the Run.
                releases.confirm(permit, intent, intent.expiresAtMillis());
                return current(token, authorized) ? result(childId, child.jobId(), Outcome.CONFIRMED, null) : stopped(childId, child.jobId());
            }
            if (!available(data, intent)) return protocolFailure(childId, child.jobId());
            if (!live(permit, intent, authorized)) return stopped(childId, child.jobId());
            ToolResult response = release(context, child.jobId(), original);
            if (response == null || !response.success()) return failed(childId, child.jobId(), response);
            if (!(response.data() instanceof Map<?, ?> acknowledgement) || !released(acknowledgement, intent))
                return protocolFailure(childId, child.jobId());
            releases.confirm(permit, intent, intent.expiresAtMillis());
            return current(token, authorized) ? result(childId, child.jobId(), Outcome.CONFIRMED, null) : stopped(childId, child.jobId());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException denied) {
            return current(token, authorized)
                    ? result(childId, child.jobId(), Outcome.BLOCKED, safeCode(denied.getMessage(), "RESULT_RELEASE_UNAVAILABLE"))
                    : stopped(childId, child.jobId());
        } finally {
            // Releasing remote storage never changes READY into DISPATCHING or UNRESOLVED.
            // This callback flag is cleared only when this actual call has exited.
            if (permit != null) runs.callbackExited(permit);
        }
    }

    private boolean live(DispatchPermit permit, Intent intent, BooleanSupplier authorized) {
        return current(permit.token(), authorized) && runs.mayDispatch(permit) && releases.mayRelease(permit, intent);
    }

    private boolean current(RunToken token, BooleanSupplier authorized) {
        return authorized.getAsBoolean() && runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(record -> record.status() == RunStatus.ACTIVE && token.equals(record.token())).isPresent();
    }

    private ToolResult status(ToolContext context, String jobId) {
        try { return gateway.readStatisticsJob(context, jobId); }
        catch (RuntimeException unknown) { return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), null); }
    }

    private ToolResult release(ToolContext context, String jobId, Map<String, Object> original) {
        try { return gateway.releaseStatisticsJobResult(context, jobId, original); }
        catch (RuntimeException unknown) { return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), null); }
    }

    private static boolean identity(Map<?, ?> data, Intent intent) {
        Object expiry = data.get("expiresAt");
        return intent.jobId().equals(data.get("jobId")) && (expiry instanceof Integer || expiry instanceof Long)
                && ((Number) expiry).longValue() == intent.expiresAtMillis();
    }

    private static boolean released(Map<?, ?> data, Intent intent) {
        return identity(data, intent) && "SUCCEEDED".equals(data.get("state"))
                && "RELEASED".equals(data.get("resultState")) && Boolean.FALSE.equals(data.get("resultReady"))
                && "RESULT_RELEASED".equals(data.get("resultCode"));
    }

    private static boolean available(Map<?, ?> data, Intent intent) {
        return identity(data, intent) && "SUCCEEDED".equals(data.get("state"))
                && "AVAILABLE".equals(data.get("resultState")) && Boolean.TRUE.equals(data.get("resultReady"))
                && data.get("resultCode") == null;
    }

    private static void requirePrincipal(RunToken token, AgentPrincipal current) {
        var owner = token.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("RESULT_PRINCIPAL_MISMATCH");
    }

    private static Result failed(String childId, String jobId, ToolResult response) {
        String code = response != null && response.data() instanceof Map<?, ?> data && data.get("code") instanceof String value
                ? safeCode(value, "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE") : "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE";
        return result(childId, jobId, Outcome.BLOCKED, code);
    }
    private static Result protocolFailure(String childId, String jobId) {
        return result(childId, jobId, Outcome.BLOCKED, "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
    }
    private static Result stopped(String childId, String jobId) { return result(childId, jobId, Outcome.STOPPED, "RELEASE_AUTHORITY_REVOKED"); }
    private static Result result(String childId, String jobId, Outcome outcome, String code) { return new Result(childId, jobId, outcome, code); }
    private static String safeCode(String value, String fallback) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : fallback;
    }
}
