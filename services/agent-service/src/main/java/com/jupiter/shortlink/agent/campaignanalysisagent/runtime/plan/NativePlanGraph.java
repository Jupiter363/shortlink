package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.infrastructure.persistence.AgentStateSerializerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Unregistered, serial native Graph scan of one frozen plan. Edges define scan order; dependency
 * readiness is always reread from the domain ledger, never inferred from a checkpoint or Artifact.
 * The caller coordinates one advancement per Run and reconciles WAITING work before this scan.
 * Driver owns current authorization, CAS, execution and publication; this class interprets neither
 * FIXED capabilities nor REACT policies and does not implement another scheduler or tool loop.
 */
public final class NativePlanGraph {
    private static final JsonMapper JSON = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final Set<String> STATE_KEYS = Set.of("threadId", "planId", "revision", "visitedSteps",
            "advancedSteps", "lastStepRef", "lastObservedStatus", "stopped", "scanCompleted");
    // Graph core 1.1.2.3 adds this framework-owned UUID to every native checkpoint.
    private static final String NATIVE_EXECUTION_ID = "_graph_execution_id_";

    public record RunIdentity(String tenantId, String subjectId, long authVersion, String sessionId,
                              String runId, String planId, int revision, String runnerVersion,
                              String topologyVersion) {
        public RunIdentity {
            for (String value : new String[] {tenantId, subjectId, sessionId, runId, planId, runnerVersion, topologyVersion}) {
                if (value == null || value.isBlank() || value.length() > 256) {
                    throw new IllegalArgumentException("Plan execution identity requires bounded nonblank references");
                }
            }
            if (authVersion < 0 || revision < 1) throw new IllegalArgumentException("Invalid plan execution version");
        }
    }

    public enum StepStatus { PENDING, RUNNING, WAITING, READY, SUCCEEDED, FAILED, CANCELLED, BLOCKED }

    /** Bound by trusted server code to this Run identity; no model/caller supplied implementation. */
    public interface Driver {
        boolean mayAdvance() throws Exception;
        StepStatus status(String stepId) throws Exception;
        /** Must recheck authorization/Run lease/step CAS at the actual dispatch boundary. */
        void advance(PlanSpec.Step step) throws Exception;
    }

    /** scanCompleted means the scan reached its end, never that the business Run succeeded. */
    public record ScanResult(String threadId, boolean scanCompleted, int visitedSteps, int advancedSteps) { }

    private final RunIdentity identity;
    private final String threadId;
    private final Driver driver;
    private final BaseCheckpointSaver saver;
    private final CompiledGraph graph;
    private final int recursionLimit;
    private final ProcessExecutionScope processScope;

    public static NativePlanGraph compile(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment,
            PlanValidator validator, RunIdentity identity, BaseCheckpointSaver saver, Driver driver) throws GraphStateException {
        return compile(plan, inputs, assessment, validator, identity, saver, driver, null);
    }

    public static NativePlanGraph compile(PlanSpec plan, FrozenInputSet inputs, PlanningAssessment assessment,
            PlanValidator validator, RunIdentity identity, BaseCheckpointSaver saver, Driver driver,
            ProcessExecutionScope processScope) throws GraphStateException {
        Objects.requireNonNull(validator).validate(plan, inputs, assessment);
        Objects.requireNonNull(identity);
        if (!identity.runId().equals(plan.runId()) || !identity.planId().equals(plan.planId())
                || identity.revision() != plan.revision()) {
            throw new IllegalArgumentException("Plan execution identity does not match the frozen plan");
        }
        return new NativePlanGraph(plan, identity, Objects.requireNonNull(saver), Objects.requireNonNull(driver), processScope);
    }

    private NativePlanGraph(PlanSpec plan, RunIdentity identity, BaseCheckpointSaver saver, Driver driver,
            ProcessExecutionScope processScope)
            throws GraphStateException {
        this.processScope = processScope;
        this.identity = identity;
        this.saver = saver;
        this.driver = driver;
        this.threadId = threadId(identity, plan);
        // One begin node + N scan nodes + one finish node, with START/END iteration allowance.
        this.recursionLimit = Math.addExact(plan.steps().size(), 4);
        StateGraph topology = new StateGraph("frozen-plan-scan", NativePlanGraph::strategies,
                AgentStateSerializerFactory.create());
        topology.addNode("begin-scan", node_async(ignored -> initialState()));
        topology.addEdge(StateGraph.START, "begin-scan");
        String previous = "begin-scan";
        for (int index = 0; index < plan.steps().size(); index++) {
            PlanSpec.Step step = plan.steps().get(index);
            String node = "scan-" + index; // Short stable topology reference; raw step arguments stay outside state.
            topology.addNode(node, node_async(state -> scan(step, node, state)));
            topology.addEdge(previous, node);
            previous = node;
        }
        topology.addNode("finish-scan", node_async(state -> Map.of("scanCompleted", !stopped(state))));
        topology.addEdge(previous, "finish-scan");
        topology.addEdge("finish-scan", StateGraph.END);
        this.graph = topology.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build())
                .recursionLimit(recursionLimit).releaseThread(false).build());
    }

    public String threadId() { return threadId; }
    public int recursionLimit() { return recursionLimit; }

    boolean belongsTo(Driver expected, ProcessExecutionScope scope) {
        return driver == expected && processScope == scope;
    }

    /** Every call starts a NEW scan at START; completed steps are skipped using persisted facts. */
    public synchronized ScanResult advance() {
        try (var ignored = processScope == null ? null : processScope.enter()) {
            return advanceAdmitted();
        }
    }

    private ScanResult advanceAdmitted() {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).nextNode(StateGraph.START).build();
        saver.get(config).ifPresent(checkpoint -> {
            if (checkpoint.getState().keySet().stream()
                    .anyMatch(key -> !STATE_KEYS.contains(key) && !NATIVE_EXECUTION_ID.equals(key))
                    || !(checkpoint.getState().get(NATIVE_EXECUTION_ID) instanceof String nativeId)
                    || !nativeId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
                    || !threadId.equals(checkpoint.getState().get("threadId"))) {
                throw new IllegalStateException("Plan checkpoint identity or projection is invalid");
            }
        });
        OverAllState result = graph.invoke(initialState(), config)
                .orElseThrow(() -> new IllegalStateException("Native plan scan produced no state"));
        return new ScanResult(threadId, Boolean.TRUE.equals(result.data().get("scanCompleted")),
                number(result, "visitedSteps"), number(result, "advancedSteps"));
    }

    private Map<String, Object> scan(PlanSpec.Step step, String node, OverAllState state) throws Exception {
        try (var ignored = processScope == null ? null : processScope.enter()) {
            return scanAdmitted(step, node, state);
        }
    }

    private Map<String, Object> scanAdmitted(PlanSpec.Step step, String node, OverAllState state) throws Exception {
        if (stopped(state)) return Map.of();
        if (!driver.mayAdvance()) return Map.of("stopped", true);
        StepStatus status = Objects.requireNonNull(driver.status(step.stepId()), "Persistent step status is required");
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("visitedSteps", number(state, "visitedSteps") + 1);
        update.put("lastStepRef", node);
        update.put("lastObservedStatus", status.name());
        if (status != StepStatus.PENDING && status != StepStatus.READY) return update;
        for (String dependency : step.dependsOn()) {
            if (driver.status(dependency) != StepStatus.SUCCEEDED) return update;
        }
        // A cancellation/revision change while reading dependencies must stop before dispatch.
        if (!driver.mayAdvance()) {
            update.put("stopped", true);
            return update;
        }
        driver.advance(step);
        update.put("advancedSteps", number(state, "advancedSteps") + 1);
        return update;
    }

    private Map<String, Object> initialState() {
        return Map.of("threadId", threadId, "planId", identity.planId(), "revision", identity.revision(),
                "visitedSteps", 0, "advancedSteps", 0, "lastStepRef", "", "lastObservedStatus", "",
                "stopped", false, "scanCompleted", false);
    }

    private static Map<String, KeyStrategy> strategies() {
        Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
        for (String key : STATE_KEYS) strategies.put(key, new ReplaceStrategy());
        return strategies;
    }

    private static boolean stopped(OverAllState state) { return Boolean.TRUE.equals(state.data().get("stopped")); }
    private static int number(OverAllState state, String key) { return ((Number) state.data().get(key)).intValue(); }

    private static String threadId(RunIdentity identity, PlanSpec plan) {
        try {
            String planDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(plan)));
            byte[] key = JSON.writeValueAsString(Map.of("identity", identity, "frozenPlanDigest", planDigest))
                    .getBytes(StandardCharsets.UTF_8);
            return UUID.nameUUIDFromBytes(key).toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("Cannot identify frozen plan topology", exception);
        }
    }
}
