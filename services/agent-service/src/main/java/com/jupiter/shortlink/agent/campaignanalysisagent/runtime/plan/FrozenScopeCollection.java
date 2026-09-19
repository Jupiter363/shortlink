package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ActionSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed, server-frozen enumeration input. Resolving it neither enumerates members nor invents a scope reference. */
public final class FrozenScopeCollection {
    public static final PlanSpec.ExecutorRef REF =
            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "scope_collection", "1");
    public static final String SCHEMA = "scope-collection-definition/v1";
    public static final String OUTPUT_CONTRACT = "campaign-scope/v1";
    public static final TypeRef DEFINITION_TYPE = new TypeRef("ScopeCollectionDefinition", 1, Cardinality.ONE);
    public static final Map<String, Port> INPUTS = Map.of("definition", new Port(DEFINITION_TYPE, true));
    private static final Set<String> FIELDS = Set.of("schemaVersion", "gid", "expiresAt");

    private FrozenScopeCollection() {}

    public record Bound(PlanSpec.Step step, Map<String, Object> descriptor, CampaignScopeStore.Definition definition) {
        public Bound { descriptor = Map.copyOf(descriptor); }
    }

    /** Validate every matching step before any collection may begin. IDs identify slots, not mutable input values. */
    public static Map<String, Bound> resolve(RunDefinition definition) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(definition);
        require(frozen.inputs().runId().equals(definition.runId())
                && frozen.inputs().inputSetRef().equals(frozen.plan().inputSetRef()));
        Map<String, Bound> bindings = new LinkedHashMap<>();
        for (PlanSpec.Step step : frozen.plan().steps()) {
            if (!REF.equals(step.executor())) continue;
            require(step.executionMode() == PlanSpec.ExecutionMode.FIXED && step.explorationPolicy() == null
                    && step.parameters().isEmpty() && step.inputBindings().keySet().equals(INPUTS.keySet())
                    && OUTPUT_CONTRACT.equals(step.outputContractRef()));
            PlanBinding binding = step.inputBindings().get("definition");
            require(binding != null && binding.source() == PlanBinding.Source.INPUT && binding.input() != null
                    && binding.stepId() == null && binding.output() == null && binding.artifactId() == null);
            Port port = frozen.inputs().inputContracts().get(binding.input());
            require(port != null && DEFINITION_TYPE.equals(port.type()));
            Map<String, Object> descriptor = object(frozen.inputs().inputValues().get(binding.input()));
            require(FIELDS.equals(descriptor.keySet()) && SCHEMA.equals(descriptor.get("schemaVersion")));
            String gid = text(descriptor.get("gid"), 64);
            require(gid.matches("[A-Za-z0-9_-]{1,64}"));
            String expiresAt = text(descriptor.get("expiresAt"), 40);
            Instant expiry;
            try { expiry = Instant.parse(expiresAt); }
            catch (DateTimeException invalid) { throw invalid(); }
            require(expiresAt.endsWith("Z") && expiry.getNano() % 1_000_000 == 0);
            try { require(expiry.equals(Instant.ofEpochMilli(expiry.toEpochMilli()))); }
            catch (ArithmeticException invalid) { throw invalid(); }
            String identity = CampaignRunStore.sha256(FrozenCampaignRun.encode(List.of("scope-collection-slot/v1",
                    definition.caller().tenantId(), definition.caller().subject(), definition.caller().authVersion(),
                    definition.sessionId(), definition.runId(), definition.planId(), definition.revision(), step.stepId())));
            ActionSpec action = new ActionSpec("scope-action-" + identity, step.stepId(), REF.kind().name(),
                    REF.name(), REF.version(), FrozenCampaignRun.encode(step));
            var collection = new CampaignScopeStore.Definition("scope-collection-" + identity, action, gid, expiry);
            require(bindings.putIfAbsent(step.stepId(), new Bound(step, descriptor, collection)) == null);
        }
        return Collections.unmodifiableMap(bindings);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?> && ((Map<?, ?>) value).keySet().stream().allMatch(String.class::isInstance));
        return (Map<String, Object>) value;
    }

    private static String text(Object value, int maximum) {
        require(value instanceof String);
        String text = (String) value;
        require(!text.isBlank() && text.length() <= maximum && text.equals(text.trim())
                && text.chars().noneMatch(Character::isISOControl));
        return text;
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FROZEN_SCOPE_COLLECTION_INVALID"); }
}
