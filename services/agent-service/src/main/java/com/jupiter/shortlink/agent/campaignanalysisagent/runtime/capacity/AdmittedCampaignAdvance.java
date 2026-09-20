package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.Demand;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.Snapshot;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Opt-in process admission around a complete campaign advance. Queue entries remain only WorkRef
 * identifiers; the shared factory loads inputs and constructs runtime objects after the atomic
 * advance/model/payload reservation. Use one instance for callers sharing those configured limits.
 */
public final class AdmittedCampaignAdvance implements AutoCloseable {
    @FunctionalInterface public interface Operation {
        void run() throws Exception;
    }

    @FunctionalInterface public interface WorkFactory {
        /** Build here, not in submit's caller. Every detached worker must use this execution scope. */
        Operation load(WorkRef reference, ProcessExecutionScope scope) throws Exception;
    }

    private static final Demand ADVANCE_DEMAND = new Demand(1, 1, 1);
    private final ProcessCapacityExecutor admitted;

    public AdmittedCampaignAdvance(Limits limits, Executor executor, WorkFactory factory) {
        Objects.requireNonNull(factory, "factory");
        this.admitted = new ProcessCapacityExecutor(limits, executor, reference -> execute(reference, factory));
    }

    /** Preserve the original capacity rejection type; rejection does not load data or create MODEL attempts. */
    public Future<Void> submit(WorkRef reference) { return admitted.submit(reference, ADVANCE_DEMAND); }

    public Snapshot snapshot() { return admitted.snapshot(); }

    /** Cancellation is requested; the underlying permits remain held until actual worker exit. */
    @Override public void close() { admitted.close(); }

    private static void execute(WorkRef reference, WorkFactory factory) throws Exception {
        ProcessExecutionScope scope = new ProcessExecutionScope();
        try {
            try (ProcessExecutionScope.Lease ignored = scope.enter()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("CAMPAIGN_ADVANCE_CANCELLED");
                Operation operation = Objects.requireNonNull(factory.load(reference, scope), "Campaign operation is required");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("CAMPAIGN_ADVANCE_CANCELLED");
                operation.run();
            }
        } finally {
            // ProcessCapacityExecutor releases only after this worker returns. Running native
            // callbacks that ignore cancellation keep the same reservation until their finally.
            scope.closeAndAwaitActualExit();
        }
    }
}
