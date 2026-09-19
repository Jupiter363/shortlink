package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.tool.shortlink.DimensionQuery;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Resolves only server-frozen input values; never looks up latest chat state or expands group members. */
public final class FrozenStatisticsJobQuery {
    public static final String SCHEMA = "statistics-job-query/v1";
    public static final String SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/jobs";
    public static final String FROZEN_SUBMIT_PATH = "/internal/short-link-admin/v1/agent-tools/statistics/frozen-jobs";
    public static final TypeRef SCOPE_TYPE = new TypeRef("ScopeRef", 1, Cardinality.ONE);
    public static final TypeRef PERIODS_TYPE = new TypeRef("PeriodsRef", 1, Cardinality.ONE);
    public static final TypeRef QUERY_TYPE = new TypeRef("StatisticsJobQuery", 1, Cardinality.ONE);
    public static final Map<String, Port> INPUTS = Map.of("scope", new Port(SCOPE_TYPE, true),
            "periods", new Port(PERIODS_TYPE, true), "query", new Port(QUERY_TYPE, true));
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeRef", "periodsRef", "scopeKind",
            "gid", "fullShortUrl", "startDate", "endDate", "businessTimezone", "queryKind", "dimensions", "filters", "scope");
    private static final Set<String> KINDS = Set.of("METRICS", "ACCESS_RECORDS", "LINK_METRICS", "DIMENSION_BREAKDOWN");

    private FrozenStatisticsJobQuery() {}

    public record Bound(PlanSpec.Step step, String scopeRef, String periodsRef, Map<String, Object> descriptor,
                        ChildSpec child, StatisticsJobResultReceiver.Target target, Map<String, Object> request) {
        public Bound {
            descriptor = Map.copyOf(descriptor);
            request = Map.copyOf(request);
        }
    }

    /** Data-only CALL preparation; admission still requires the actual stored CALL and its live permit. */
    public record Prepared(String scopeRef, String periodsRef, Map<String, Object> descriptor,
                           ChildSpec child, StatisticsJobResultReceiver.Target target, Map<String, Object> request) {
        public Prepared {
            descriptor = immutableMap(descriptor);
            request = immutableMap(request);
        }
    }

    /**
     * Reconstructs a query/receiver target from a real CALL's frozen identity, including after its
     * callback exits. This creates no action, permit, synthetic fixed step, or remote request.
     * The adapter must verify the stored CallSpec, typed bindings and current rights separately.
     */
    public static Prepared prepareCall(RunDefinition definition, String stepId, String callId, String actionId,
                                      PlanSpec.ExecutorRef executor, String scopeRef, String periodsRef,
                                      Map<String, Object> supplied) {
        require(definition != null && executor != null);
        text(stepId, 96); text(callId, 96); text(actionId, 96);
        require(executor.kind() == PlanSpec.ExecutorKind.TOOL && "statistics_query_job".equals(executor.name()));
        text(executor.version(), 128);
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        PlanSpec.Step step = frozen.plan().steps().stream().filter(value -> stepId.equals(value.stepId()))
                .findFirst().orElseThrow(FrozenStatisticsJobQuery::invalid);
        require(step.executionMode() == PlanSpec.ExecutionMode.REACT && step.executor() == null
                && step.explorationPolicy() != null && step.explorationPolicy().allowedExecutors().contains(executor)
                && step.explorationPolicy().scopeRef().equals(scopeRef)
                && step.explorationPolicy().periodsRef().equals(periodsRef));
        Map<String, Object> descriptor = immutableMap(object(supplied));
        Map<String, Object> request = new TreeMap<>(prepareRequest(scopeRef, periodsRef, descriptor));
        // The CALL-owned slot never changes with query content, version pins or callback attempts.
        String slot = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of("statistics-call-slot/v1",
                definition.caller().tenantId(), definition.caller().subject(), definition.caller().authVersion(),
                definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), stepId, callId, actionId)));
        String requestId = "stat_" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(slot, executor, descriptor, request)));
        request.put("requestId", requestId);
        ChildSpec child = new ChildSpec("stats-child-" + slot, actionId, ChildMode.ASYNC, requestId,
                new WireRequest("POST", request.containsKey("scope") ? FROZEN_SUBMIT_PATH : SUBMIT_PATH,
                        FrozenCampaignRun.encode(request)));
        return new Prepared(scopeRef, periodsRef, descriptor, child,
                new StatisticsJobResultReceiver.Target("stats-result-" + slot, scopeRef, periodsRef), request);
    }

    /** All matching steps are validated before a caller may dispatch any of them. */
    public static Map<String, Bound> resolve(RunDefinition definition, PlanSpec.ExecutorRef executor) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Bound> bindings = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!executor.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && step.parameters().isEmpty() && step.inputBindings().keySet().equals(INPUTS.keySet()));
            Map<String, Object> values = new LinkedHashMap<>();
            for (var input : INPUTS.entrySet()) {
                PlanBinding binding = step.inputBindings().get(input.getKey());
                require(binding != null && binding.source() == PlanBinding.Source.INPUT && binding.input() != null
                        && binding.stepId() == null && binding.output() == null && binding.artifactId() == null);
                Port port = frozen.inputs().inputContracts().get(binding.input());
                require(port != null && input.getValue().type().equals(port.type()));
                Object value = frozen.inputs().inputValues().get(binding.input());
                require(value != null);
                values.put(input.getKey(), value);
            }
            String scope = text(values.get("scope"), 256);
            String periods = text(values.get("periods"), 256);
            Map<String, Object> descriptor = object(values.get("query"));
            Map<String, Object> request = new TreeMap<>(prepareRequest(scope, periods, descriptor));
            boolean fixedMembers = request.containsKey("scope");

            // Slot identity excludes query values and attempts. Changed parameters must collide with
            // the original child and fail its immutable request check, never create a second child.
            String slot = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of("statistics-slot/v1",
                    definition.caller().tenantId(), definition.caller().subject(), definition.caller().authVersion(),
                    definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), step.stepId())));
            String requestId = "stat_" + CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of(slot,
                    executor, descriptor, request)));
            request.put("requestId", requestId);
            ChildSpec child = new ChildSpec("stats-child-" + slot, "stats-action-" + slot, ChildMode.ASYNC,
                    requestId, new WireRequest("POST", fixedMembers ? FROZEN_SUBMIT_PATH : SUBMIT_PATH, FrozenCampaignRun.encode(request)));
            Bound bound = new Bound(step, scope, periods, descriptor, child,
                    new StatisticsJobResultReceiver.Target("stats-result-" + slot, scope, periods), request);
            require(bindings.putIfAbsent(step.stepId(), bound) == null);
        }
        return Collections.unmodifiableMap(bindings);
    }

    /** Closed descriptor validation only; this neither authorizes a scope nor registers execution. */
    public static Map<String, Object> prepareRequest(String scopeRef, String periodsRef, Map<String, Object> supplied) {
        String scope = text(scopeRef, 256);
        String periods = text(periodsRef, 256);
        Map<String, Object> descriptor = immutableMap(object(supplied));
        require(FIELDS.containsAll(descriptor.keySet()) && SCHEMA.equals(descriptor.get("schemaVersion"))
                && scope.equals(descriptor.get("scopeRef")) && periods.equals(descriptor.get("periodsRef"))
                && "Asia/Shanghai".equals(descriptor.get("businessTimezone")));
        Map<String, Object> request = new TreeMap<>();
        boolean fixedMembers = "FROZEN_SET".equals(descriptor.get("scopeKind"));
        if (fixedMembers) {
            require(!descriptor.containsKey("fullShortUrl"));
            FrozenQueryScope shard = FrozenQueryScope.fromMap(object(descriptor.get("scope")));
            require(scope.equals(shard.parentScopeRef()));
            request.put("scope", shard.asMap());
        } else require("CURRENT_GROUP".equals(descriptor.get("scopeKind")) && !descriptor.containsKey("scope"));
        request.put("gid", text(descriptor.get("gid"), 128));
        String kind = text(descriptor.get("queryKind"), 64);
        require(KINDS.contains(kind));
        request.put("queryKind", kind);
        LocalDate start = date(descriptor.get("startDate"));
        LocalDate end = date(descriptor.get("endDate"));
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        require(!start.isBefore(LocalDate.of(1970, 1, 1)) && days >= 1 && days <= 180);
        request.put("startDate", start.toString());
        request.put("endDate", end.toString());
        if (descriptor.containsKey("fullShortUrl")) request.put("fullShortUrl", text(descriptor.get("fullShortUrl"), 2048));
        if ("DIMENSION_BREAKDOWN".equals(kind)) {
            try {
                DimensionQuery.dimensions(descriptor.get("dimensions"));
                DimensionQuery.filters(descriptor.get("filters"));
            } catch (IllegalArgumentException invalid) { throw invalid(); }
            request.put("dimensions", descriptor.get("dimensions"));
            if (descriptor.containsKey("filters")) {
                require(descriptor.get("filters") instanceof List<?>);
                for (Object filter : (List<?>) descriptor.get("filters"))
                    require(Set.of("dimension", "operator", "values").containsAll(object(filter).keySet()));
                request.put("filters", descriptor.get("filters"));
            }
        } else require(!descriptor.containsKey("dimensions") && !descriptor.containsKey("filters"));
        return immutableMap(request);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) immutable(object(source), 0);
        return copy;
    }

    private static Object immutable(Object value, int depth) {
        require(depth <= 128);
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            object(value).forEach((key, item) -> copy.put(key, immutable(item, depth + 1)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(item -> immutable(item, depth + 1)).toList();
        return value; // Descriptor validation admits only immutable JSON scalars at its leaves.
    }

    private static LocalDate date(Object value) {
        String text = text(value, 10);
        require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        try { return LocalDate.parse(text); }
        catch (DateTimeException invalid) { throw invalid(); }
    }

    private static String text(Object value, int maximum) {
        require(value instanceof String);
        String text = (String) value;
        require(!text.isBlank() && text.length() <= maximum && text.equals(text.trim())
                && text.chars().noneMatch(Character::isISOControl));
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?> && ((Map<?, ?>) value).keySet().stream().allMatch(String.class::isInstance));
        return (Map<String, Object>) value;
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FROZEN_STATISTICS_QUERY_INVALID"); }
}
