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
import java.util.Map;
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

    public record Runtime(PersistentPlanDriver driver, NativePlanGraph graph,
                          Map<String, StatisticsJobResultReceiver.Target> resultTargets,
                          CampaignRunStore.ArtifactAuthorizer releaseAuthorizer) {
        public Runtime(PersistentPlanDriver driver, NativePlanGraph graph) { this(driver, graph, Map.of()); }
        public Runtime(PersistentPlanDriver driver, NativePlanGraph graph,
                       Map<String, StatisticsJobResultReceiver.Target> resultTargets) {
            this(driver, graph, resultTargets, null);
        }
        public Runtime {
            Objects.requireNonNull(driver); Objects.requireNonNull(graph);
            resultTargets = Map.copyOf(resultTargets);
        }
    }

    /** Internal handoff only: the token/receipts are not a client report or Graph checkpoint. */
    public record ResumeResult(Outcome outcome, RunToken token, String reason, int recoveredCallbacks,
                               List<StatisticsSubmissionReconciler.Result> reconciliations,
                               NativePlanGraph.ScanResult scan,
                               List<StatisticsJobResultReceiver.Result> receivedResults,
                               List<StatisticsJobResultReleaser.Result> releasedResults) {
        public ResumeResult(Outcome outcome, RunToken token, String reason, int recoveredCallbacks,
                            List<StatisticsSubmissionReconciler.Result> reconciliations,
                            NativePlanGraph.ScanResult scan,
                            List<StatisticsJobResultReceiver.Result> receivedResults) {
            this(outcome, token, reason, recoveredCallbacks, reconciliations, scan, receivedResults, List.of());
        }
        public ResumeResult {
            reconciliations = List.copyOf(reconciliations);
            receivedResults = List.copyOf(receivedResults);
            releasedResults = List.copyOf(releasedResults);
        }
    }

    private final CampaignRecoveryStore recovery;
    private final CampaignRunStore runs;
    private final StatisticsSubmissionReconciler submissions;
    private final RuntimeFactory runtimeFactory;
    private final RunAuthorizer authorizer;
    private final StatisticsJobResultReceiver receiver;
    private final StatisticsJobResultReleaser releaser;

    public CampaignRecoveryCoordinator(CampaignRecoveryStore recovery, CampaignRunStore runs,
            StatisticsSubmissionReconciler submissions, RuntimeFactory runtimeFactory, RunAuthorizer authorizer) {
        this(recovery, runs, submissions, runtimeFactory, authorizer, null);
    }

    public CampaignRecoveryCoordinator(CampaignRecoveryStore recovery, CampaignRunStore runs,
            StatisticsSubmissionReconciler submissions, RuntimeFactory runtimeFactory, RunAuthorizer authorizer,
            StatisticsJobResultReceiver receiver) {
        this(recovery, runs, submissions, runtimeFactory, authorizer, receiver, null);
    }

    public CampaignRecoveryCoordinator(CampaignRecoveryStore recovery, CampaignRunStore runs,
            StatisticsSubmissionReconciler submissions, RuntimeFactory runtimeFactory, RunAuthorizer authorizer,
            StatisticsJobResultReceiver receiver, StatisticsJobResultReleaser releaser) {
        this.recovery = Objects.requireNonNull(recovery);
        this.runs = Objects.requireNonNull(runs);
        this.submissions = Objects.requireNonNull(submissions);
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory);
        this.authorizer = Objects.requireNonNull(authorizer);
        this.receiver = receiver;
        this.releaser = releaser;
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
            if (!runtime.resultTargets().isEmpty() && receiver == null)
                throw new IllegalArgumentException("Statistics result reception is unavailable");
            if (runtime.releaseAuthorizer() != null && releaser == null)
                throw new IllegalArgumentException("Statistics result release is unavailable");
        } catch (IllegalArgumentException | SecurityException unavailable) {
            return result(Outcome.BLOCKED, token, "RECOVERY_RUNTIME_UNAVAILABLE", takeover.recoveredCallbacks(), List.of(), null);
        }
        List<StatisticsSubmissionReconciler.Result> reconciled = new ArrayList<>();
        List<StatisticsJobResultReceiver.Result> received = new ArrayList<>();
        List<StatisticsJobResultReleaser.Result> released = new ArrayList<>();
        try {
            for (var child : runs.children(token)) {
                if (!mayAdvance(token, current))
                    return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null, received, released);
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
                    return result(Outcome.STOPPED, token, receipt.code(), takeover.recoveredCallbacks(), reconciled, null, received, released);
                var target = runtime.resultTargets().get(child.spec().childId());
                if (target != null && (receipt.outcome() == StatisticsSubmissionReconciler.Outcome.KNOWN_JOB
                        || receipt.outcome() == StatisticsSubmissionReconciler.Outcome.RECOVERED)) {
                    var result = receiver.receive(token, child.spec().childId(), current, target, () -> mayAdvance(token, current));
                    received.add(result);
                    if (result.outcome() == StatisticsJobResultReceiver.Outcome.STOPPED)
                        return result(Outcome.STOPPED, token, result.code(), takeover.recoveredCallbacks(), reconciled, null, received, released);
                }
                if (runtime.releaseAuthorizer() != null) {
                    var release = releaser.release(token, child.spec().childId(), current,
                            runtime.releaseAuthorizer(), () -> mayAdvance(token, current));
                    released.add(release);
                    if (release.outcome() == StatisticsJobResultReleaser.Outcome.STOPPED)
                        return result(Outcome.STOPPED, token, release.code(), takeover.recoveredCallbacks(), reconciled, null, received, released);
                }
            }
            if (!mayAdvance(token, current))
                return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null, received, released);
            // Finding a job ID alone is not READY. Only already-ingested durable receipts can wake a step.
            runtime.driver().refreshWaiting();
            NativePlanGraph.ScanResult scan = runtime.graph().advance();
            return result(scan.scanCompleted() ? Outcome.SCANNED : Outcome.STOPPED, token,
                    scan.scanCompleted() ? null : "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, scan, received, released);
        } catch (Exception interrupted) {
            if (!mayAdvance(token, current))
                return result(Outcome.STOPPED, token, "RECOVERY_AUTHORITY_REVOKED", takeover.recoveredCallbacks(), reconciled, null, received, released);
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
        return result(outcome, token, reason, recovered, reconciliations, scan, List.of());
    }

    private static ResumeResult result(Outcome outcome, RunToken token, String reason, int recovered,
            List<StatisticsSubmissionReconciler.Result> reconciliations, NativePlanGraph.ScanResult scan,
            List<StatisticsJobResultReceiver.Result> received) {
        return new ResumeResult(outcome, token, reason, recovered, reconciliations, scan, received);
    }

    private static ResumeResult result(Outcome outcome, RunToken token, String reason, int recovered,
            List<StatisticsSubmissionReconciler.Result> reconciliations, NativePlanGraph.ScanResult scan,
            List<StatisticsJobResultReceiver.Result> received, List<StatisticsJobResultReleaser.Result> released) {
        return new ResumeResult(outcome, token, reason, recovered, reconciliations, scan, received, released);
    }
}
