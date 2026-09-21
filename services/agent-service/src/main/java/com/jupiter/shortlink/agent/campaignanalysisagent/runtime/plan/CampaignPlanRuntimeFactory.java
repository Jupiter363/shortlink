package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactAuthorizer;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain typed composition boundary for a persisted, frozen campaign plan.
 *
 * <p>{@link #open(Request)} only reads and validates the authoritative run.  It does not create a
 * driver, initialize step rows, compile a Graph, advance a run, or obtain a Spring/Mysql saver.
 * Those effects begin only at {@link PreparedRuntime#compile(CampaignPlanRuntimeRegistry.SaverBinding)}.
 * This keeps the factory transport-neutral and prevents a registry from becoming a production
 * Graph entry point by accident.</p>
 */
public final class CampaignPlanRuntimeFactory {
    /** All authorities are supplied by the trusted caller; no allow-all defaults exist. */
    public record Request(Caller owner, String sessionId, RunToken token,
                          CampaignRunStore runs, CampaignStepStore steps,
                          PersistentPlanDriver.RunAuthorizer runAuthorizer,
                          ArtifactAuthorizer artifactAuthorizer,
                          StepBindings.CurrentInputAuthorizer inputAuthorizer,
                          ProcessExecutionScope processScope) {
        public Request {
            Objects.requireNonNull(owner, "PLAN_RUNTIME_OWNER_REQUIRED");
            if (sessionId == null || sessionId.isBlank() || sessionId.length() > 256) {
                throw new IllegalArgumentException("PLAN_RUNTIME_SESSION_REQUIRED");
            }
            Objects.requireNonNull(token, "PLAN_RUNTIME_TOKEN_REQUIRED");
            Objects.requireNonNull(token.definition(), "PLAN_RUNTIME_DEFINITION_REQUIRED");
            Objects.requireNonNull(runs, "PLAN_RUNTIME_RUN_STORE_REQUIRED");
            Objects.requireNonNull(steps, "PLAN_RUNTIME_STEP_STORE_REQUIRED");
            Objects.requireNonNull(runAuthorizer, "PLAN_RUNTIME_RUN_AUTHORIZER_REQUIRED");
            Objects.requireNonNull(artifactAuthorizer, "PLAN_RUNTIME_ARTIFACT_AUTHORIZER_REQUIRED");
            Objects.requireNonNull(inputAuthorizer, "PLAN_RUNTIME_INPUT_AUTHORIZER_REQUIRED");
        }
    }

    /** Effects are intentionally deferred until the caller presents a matching saver binding. */
    public static final class PreparedRuntime {
        private final CampaignPlanRuntimeFactory factory;
        private final Request request;
        private final FrozenCampaignRun frozen;

        private PreparedRuntime(CampaignPlanRuntimeFactory factory, Request request, FrozenCampaignRun frozen) {
            this.factory = factory;
            this.request = request;
            this.frozen = frozen;
        }

        public RunToken token() { return request.token(); }
        public FrozenCampaignRun frozen() { return frozen; }
        public CampaignPlanRuntimeRegistry registry() { return factory.registry; }

        /** Rechecks the persisted token before constructing the driver or Graph. */
        public CompiledRuntime compile(CampaignPlanRuntimeRegistry.SaverBinding binding)
                throws GraphStateException {
            FrozenCampaignRun current = factory.verify(request);
            if (!current.equals(frozen)) throw new SecurityException("PLAN_RUNTIME_FROZEN_CHANGED");
            BaseCheckpointSaver saver = factory.registry.requireSaver(binding);
            PersistentPlanDriver driver = new PersistentPlanDriver(request.token(), request.runs(), request.steps(),
                    factory.registry.catalog(), factory.registry.contracts(), factory.registry.fixedExecutors(),
                    request.runAuthorizer(), request.artifactAuthorizer(), request.inputAuthorizer(),
                    factory.registry.reactExecutors(), factory.registry.candidates(), request.processScope());
            NativePlanGraph graph = driver.compile(saver);
            return new CompiledRuntime(request.token(), frozen, driver, graph);
        }
    }

    /** Typed result of the explicit compile boundary; no HTTP/JSON projection is implied. */
    public record CompiledRuntime(RunToken token, FrozenCampaignRun frozen,
                                  PersistentPlanDriver driver, NativePlanGraph graph) {
        public CompiledRuntime {
            Objects.requireNonNull(token, "PLAN_RUNTIME_TOKEN_REQUIRED");
            Objects.requireNonNull(frozen, "PLAN_RUNTIME_FROZEN_REQUIRED");
            Objects.requireNonNull(driver, "PLAN_RUNTIME_DRIVER_REQUIRED");
            Objects.requireNonNull(graph, "PLAN_RUNTIME_GRAPH_REQUIRED");
        }
    }

    private final CampaignPlanRuntimeRegistry registry;

    public CampaignPlanRuntimeFactory(CampaignPlanRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "PLAN_RUNTIME_REGISTRY_REQUIRED");
    }

    /**
     * Performs authoritative identity/status/version checks without constructing a driver or
     * writing the step ledger.
     */
    public PreparedRuntime open(Request request) {
        Objects.requireNonNull(request, "PLAN_RUNTIME_REQUEST_REQUIRED");
        if (request.processScope() != null && request.processScope().isClosed()) {
            throw new IllegalStateException("PROCESS_EXECUTION_SCOPE_CLOSED");
        }
        FrozenCampaignRun frozen = verify(request);
        return new PreparedRuntime(this, request, frozen);
    }

    private FrozenCampaignRun verify(Request request) {
        RunDefinition definition = request.token().definition();
        if (!request.owner().equals(definition.caller()) || !request.sessionId().equals(definition.sessionId())) {
            throw new SecurityException("PLAN_RUNTIME_OWNER_SESSION_MISMATCH");
        }
        RunRecord persisted = request.runs().loadRun(request.owner(), definition.runId())
                .orElseThrow(() -> new IllegalStateException("PLAN_RUNTIME_RUN_NOT_FOUND"));
        if (persisted.status() != RunStatus.ACTIVE) {
            throw new IllegalStateException("PLAN_RUNTIME_RUN_NOT_ACTIVE");
        }
        if (!persisted.token().equals(request.token())) {
            throw new SecurityException("PLAN_RUNTIME_TOKEN_STALE");
        }
        if (!definition.equals(persisted.definition())
                || !request.owner().equals(persisted.definition().caller())
                || !request.sessionId().equals(persisted.definition().sessionId())) {
            throw new SecurityException("PLAN_RUNTIME_DEFINITION_CHANGED");
        }
        FrozenCampaignRun frozen = FrozenCampaignRun.read(persisted.definition());
        registry.validate(frozen);
        PlanValidator validator = new PlanValidator(registry.catalog(), artifactId -> {
            var metadata = request.runs().inspectArtifact(request.owner(), artifactId,
                    request.artifactAuthorizer());
            return metadata == null ? Optional.empty() : Optional.of(registry.contracts().typeOf(metadata));
        });
        validator.validate(frozen.plan(), frozen.inputs(), frozen.assessment());
        if (!request.runAuthorizer().mayExecute(request.owner(), frozen.inputs())) {
            throw new SecurityException("PLAN_RUNTIME_ACCESS_DENIED");
        }
        return frozen;
    }
}
