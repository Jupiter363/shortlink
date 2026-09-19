package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.NativePlanGraph;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.PersistentPlanDriver;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One admitted backend recovery pass: proven takeover, existing-only identity reconciliation,
 * then a native plan scan. No scheduler, model loop, sleep, submission fallback or Spring route.
 * The caller must enter through the shared process capacity executor before loading a Run.
 */
public final class CampaignRecoveryCoordinator {
    public enum Outcome { SCANNED, BLOCKED, STOPPED }

    @FunctionalInterface public interface RunAuthorizer {
        boolean mayRecover(RunDefinition definition, AgentPrincipal current);
    }

    /** Trusted factory validates the complete frozen plan and current capabilities before any I/O. */
    @FunctionalInterface public interface RuntimeFactory {
        Runtime create(RunToken token, AgentPrincipal current) throws Exception;
    }

    public record Runtime(PersistentPlanDriver driver, NativePlanGraph graph) {
        public Runtime { Objects.requireNonNull(driver); Objects.requireNonNull(graph); }
    }

    /** Internal handoff only: the token/receipts are not a client report or Graph checkpoint. */
    public record ResumeResult(Outcome outcome, RunToken token, String reason, int recoveredCallbacks,
                               List<StatisticsSubmissionReconciler.Result> reconciliations,
                               NativePlanGraph.ScanResult scan) {
        public ResumeResult { reconciliations = List.copyOf(reconciliations); }
    }

    private final CampaignRecoveryStore recovery;
    private final CampaignRunStore runs;
    private final StatisticsSubmissionReconciler submissions;
    private final RuntimeFactory runtimeFactory;
    private final RunAuthorizer authorizer;

    public CampaignRecoveryCoordinator(CampaignRecoveryStore recovery, CampaignRunStore runs,
            StatisticsSubmissionReconciler submissions, RuntimeFactory runtimeFactory, RunAuthorizer authorizer) {
        this.recovery = Objects.requireNonNull(recovery);
        this.runs = Objects.requireNonNull(runs);
        this.submissions = Objects.requireNonNull(submissions);
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory);
        this.authorizer = Objects.requireNonNull(authorizer);
    }

    public ResumeResult resume(RunToken expected, AgentPrincipal current) throws Exception {
        requirePrincipal(expected, current);
        if (!authorizer.mayRecover(expected.definition(), current))
            return result(Outcome.STOPPED, null, "RUN_ACCESS_DENIED", 0, List.of(), null);
        var takeover = recovery.recover(expected);
        if (takeover.outcome() != CampaignRecoveryStore.Outcome.ACQUIRED)
            return result(takeover.outcome() == CampaignRecoveryStore.Outcome.STOPPED ? Outcome.STOPPED : Outcome.BLOCKED,
                    null, takeover.reason(), takeover.recoveredCallbacks(), List.of(), null);
        RunToken token = takeover.token();
        if (!mayAdvance(token, current))
            return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), List.of(), null);
        Runtime runtime;
        try {
            runtime = Objects.requireNonNull(runtimeFactory.create(token, current));
        } catch (IllegalArgumentException | SecurityException unavailable) {
            return result(Outcome.BLOCKED, token, "RECOVERY_RUNTIME_UNAVAILABLE", takeover.recoveredCallbacks(), List.of(), null);
        }
        List<StatisticsSubmissionReconciler.Result> reconciled = new ArrayList<>();
        try {
            for (var child : runs.children(token)) {
                if (!mayAdvance(token, current))
                    return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null);
                StatisticsSubmissionReconciler.Result receipt;
                try {
                    receipt = submissions.recover(token, child.spec().childId(), current, () -> mayAdvance(token, current));
                } catch (IllegalArgumentException unsupported) {
                    // An unknown operation does not become a statistics submission or a new request.
                    receipt = new StatisticsSubmissionReconciler.Result(child.spec().childId(),
                            StatisticsSubmissionReconciler.Outcome.UNRESOLVED, child.jobId(), "RECOVERY_REQUEST_UNSUPPORTED");
                }
                reconciled.add(receipt);
                if (receipt.outcome() == StatisticsSubmissionReconciler.Outcome.STOPPED)
                    return result(Outcome.STOPPED, token, receipt.code(), takeover.recoveredCallbacks(), reconciled, null);
            }
            if (!mayAdvance(token, current))
                return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null);
            // Finding a job ID alone is not READY. Only already-ingested durable receipts can wake a step.
            runtime.driver().refreshWaiting();
            NativePlanGraph.ScanResult scan = runtime.graph().advance();
            return result(scan.scanCompleted() ? Outcome.SCANNED : Outcome.STOPPED, token,
                    scan.scanCompleted() ? null : "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, scan);
        } catch (Exception interrupted) {
            if (!mayAdvance(token, current))
                return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null);
            throw interrupted;
        }
    }

    private boolean mayAdvance(RunToken token, AgentPrincipal current) {
        if (!authorizer.mayRecover(token.definition(), current)) return false;
        return runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && run.token().equals(token)).isPresent();
    }

    private static void requirePrincipal(RunToken expected, AgentPrincipal current) {
        Objects.requireNonNull(expected);
        var owner = expected.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("RECOVERY_PRINCIPAL_MISMATCH");
    }

    private static ResumeResult result(Outcome outcome, RunToken token, String reason, int recovered,
            List<StatisticsSubmissionReconciler.Result> reconciliations, NativePlanGraph.ScanResult scan) {
        return new ResumeResult(outcome, token, reason, recovered, reconciliations, scan);
    }
}
