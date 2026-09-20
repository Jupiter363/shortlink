package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.ReplanReceiptStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the in-process replan boundary.
 *
 * <p>Assessment and graph precompilation happen before the applier is called.  The applier is
 * the persistence boundary: its implementation must adopt compatible consumers and CAS the new
 * Run revision, including the receipt, in one writable REQUIRED transaction.  This class never
 * constructs a {@code PersistentPlanDriver}; a precompiler is deliberately a side-effect-free
 * adapter around the native graph boundary.</p>
 */
public final class ReplanCoordinator {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /** A compiled, immutable graph boundary. Implementations must not write checkpoints. */
    public interface GraphPrecompiler {
        PreparedGraph precompile(PlanSpec candidate, PlanningAssessment assessment) throws Exception;
    }

    /** Opaque graph input passed to the atomic applier; native graph construction follows commit. */
    public record PreparedGraph(String planId, int revision, String candidatePlanHash) {
        public PreparedGraph {
            Objects.requireNonNull(planId);
            Objects.requireNonNull(candidatePlanHash);
            if (revision < 1 || !candidatePlanHash.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid prepared graph identity");
        }
    }

    /** Performs consumer adoption and revision CAS, and records the receipt in one REQUIRED tx. */
    @FunctionalInterface
    public interface RevisionApplier {
        AppliedRevision apply(ApplyRequest request) throws Exception;
    }

    public record ApplyRequest(CampaignRunStore.RunToken baseRun, PlanSpec candidate,
                               PlanningAssessment candidateAssessment, PreparedGraph graph,
                               String requestJson, ReplanReceiptStore receipts) {
        public ApplyRequest {
            Objects.requireNonNull(baseRun); Objects.requireNonNull(candidate);
            Objects.requireNonNull(candidateAssessment); Objects.requireNonNull(graph);
            Objects.requireNonNull(requestJson); Objects.requireNonNull(receipts);
        }
    }

    public record AppliedRevision(CampaignRunStore.RunToken run,
                                  ReplanReceiptStore.Receipt receipt) {
        public AppliedRevision {
            Objects.requireNonNull(run); Objects.requireNonNull(receipt);
        }
    }

    public enum Outcome { APPLIED, IDEMPOTENT, REJECTED }

    public record Result(Outcome outcome, ReplanRequest.ReplanAssessment assessment,
                         ReplanReceiptStore.Receipt receipt,
                         CampaignRunStore.RunToken run, String reasonCode) {}

    private final ReplanReceiptStore receipts;
    private final GraphPrecompiler precompiler;
    private final RevisionApplier applier;

    public ReplanCoordinator(ReplanReceiptStore receipts, GraphPrecompiler precompiler,
                             RevisionApplier applier) {
        this.receipts = Objects.requireNonNull(receipts);
        this.precompiler = Objects.requireNonNull(precompiler);
        this.applier = Objects.requireNonNull(applier);
    }

    public Result replan(CampaignRunStore.RunToken baseRun, ReplanRequest request,
                         PlanSpec candidate, PlanningAssessment candidateAssessment) throws Exception {
        Objects.requireNonNull(baseRun); Objects.requireNonNull(request);
        String requestJson = canonical(request);
        Optional<ReplanReceiptStore.Receipt> existing = receipts.findFinalized(baseRun.definition().caller(),
                baseRun.definition().runId(), baseRun.definition().revision());
        if (existing.isEmpty()) existing = receipts.find(baseRun);
        if (existing.isPresent()) {
            ReplanReceiptStore.Receipt receipt = existing.get();
            if (!receipt.requestJson().equals(requestJson)) throw new IllegalStateException("REPLAN_RECEIPT_CONFLICT");
            return new Result("ACCEPTED".equals(receipt.decision()) ? Outcome.IDEMPOTENT : Outcome.REJECTED,
                    null, receipt, baseRun, receipt.reasonCode());
        }

        ReplanRequest.ReplanAssessment assessment = request.assess(candidate, candidateAssessment);
        if (!assessment.accepted()) {
            ReplanReceiptStore.Receipt receipt = receipts.record(baseRun, assessment.candidateRevision(),
                    Optional.ofNullable(assessment.candidatePlanHash()).orElse("0".repeat(64)),
                    requestJson, "REJECTED", assessment.reasonCode().name());
            return new Result(Outcome.REJECTED, assessment, receipt, baseRun, assessment.reasonCode().name());
        }
        PreparedGraph graph = Objects.requireNonNull(precompiler.precompile(candidate, candidateAssessment),
                "REPLAN_GRAPH_PRECOMPILE_FAILED");
        if (!graph.planId().equals(candidate.planId()) || graph.revision() != candidate.revision()
                || !graph.candidatePlanHash().equals(assessment.candidatePlanHash()))
            throw new IllegalStateException("REPLAN_GRAPH_IDENTITY_CHANGED");
        AppliedRevision applied = applier.apply(new ApplyRequest(baseRun, candidate, candidateAssessment,
                graph, requestJson, receipts));
        return new Result(Outcome.APPLIED, assessment, applied.receipt(), applied.run(), "ACCEPTED");
    }

    private static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("REPLAN_REQUEST_SERIALIZATION_FAILED", e); }
    }

}
