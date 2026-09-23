package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.CapacityRejectedException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.diagnostics.CampaignFailureDiagnostics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded timer tick re-enters the shared intake. No timer, executor, model loop or takeover logic is owned here. */
public final class CampaignDueWorkDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(CampaignDueWorkDispatcher.class);
    @FunctionalInterface public interface Submit { Future<Void> submit(WorkRef reference); }
    @FunctionalInterface public interface WorkState { Decision inspect(WorkRef reference); }
    public enum Decision { CONTINUE, DONE, BLOCKED }
    public record Settings(int batchSize, int maxInFlight, long initialDelayMillis, long maximumDelayMillis) {
        public Settings {
            if (batchSize < 1 || maxInFlight < 1 || (long) batchSize + maxInFlight > 4096
                    || initialDelayMillis < 1 || maximumDelayMillis < initialDelayMillis)
                throw new IllegalArgumentException("DUE_WORK_SETTINGS_INVALID");
        }
        public static Settings defaults() { return new Settings(16, 32, 1000, 10000); }
    }
    public record Tick(int completed, int submitted, int inFlight) {}
    private record Pending(CampaignDueWorkStore.Claim claim, Future<Void> future) {}
    private final CampaignDueWorkStore store;
    private final Submit submit;
    private final WorkState state;
    private final Settings settings;
    private final Map<String, Pending> inFlight = new LinkedHashMap<>();

    public CampaignDueWorkDispatcher(CampaignDueWorkStore store, Submit submit, WorkState state, Settings settings) {
        this.store = Objects.requireNonNull(store); this.submit = Objects.requireNonNull(submit);
        this.state = Objects.requireNonNull(state); this.settings = Objects.requireNonNull(settings);
    }

    /** No Future timeout is used. In-flight callbacks are retained until the actual admitted Future completes. */
    public synchronized Tick tick() {
        int completed = 0;
        var iterator = inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next().getValue();
            if (!pending.future().isDone()) continue;
            try {
                pending.future().get();
                finish(pending.claim(), state.inspect(pending.claim().reference()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Tick(completed, 0, inFlight.size());
            } catch (ExecutionException failed) {
                fail(pending.claim(), failed.getCause());
            } catch (CancellationException cancelled) {
                store.finish(pending.claim(), CampaignDueWorkStore.State.BLOCKED, "ADVANCE_CANCELLED");
            } catch (RuntimeException denied) {
                fail(pending.claim(), denied);
            }
            iterator.remove(); completed++;
        }
        int submitted = 0;
        for (var candidate : store.due(settings.batchSize() + inFlight.size())) {
            if (submitted >= settings.batchSize() || inFlight.size() >= settings.maxInFlight()) break;
            if (inFlight.containsKey(candidate.reference().runId())) continue;
            var claimed = store.claim(candidate, settings.initialDelayMillis(), settings.maximumDelayMillis());
            if (claimed.isEmpty()) continue;
            var claim = claimed.get();
            try {
                var decision = state.inspect(claim.reference());
                if (decision != Decision.CONTINUE) { finish(claim, decision); continue; }
                inFlight.put(claim.reference().runId(), new Pending(claim, Objects.requireNonNull(submit.submit(claim.reference()))));
                submitted++;
            } catch (RuntimeException rejected) { fail(claim, rejected); }
        }
        return new Tick(completed, submitted, inFlight.size());
    }

    private void finish(CampaignDueWorkStore.Claim claim, Decision decision) {
        store.finish(claim, switch (Objects.requireNonNull(decision)) {
            case CONTINUE -> CampaignDueWorkStore.State.READY;
            case DONE -> CampaignDueWorkStore.State.DONE;
            case BLOCKED -> CampaignDueWorkStore.State.BLOCKED;
        }, decision == Decision.BLOCKED ? "DURABLE_WORK_BLOCKED" : null);
    }

    private void fail(CampaignDueWorkStore.Claim claim, Throwable failure) {
        // Proven admission rejection may retry. Unknown execution/model errors require explicit recovery.
        if (failure instanceof CapacityRejectedException)
            store.finish(claim, CampaignDueWorkStore.State.READY, "LOCAL_ADMISSION_REJECTED");
        else {
            String reason = failure instanceof SecurityException ? "ACCESS_DENIED" : "ADVANCE_REQUIRES_ATTENTION";
            LOG.warn("Campaign advance blocked reason={} diagnostic={}", reason, CampaignFailureDiagnostics.describe(failure));
            store.finish(claim, CampaignDueWorkStore.State.BLOCKED, reason);
        }
    }
}
