package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Parameters;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Signature;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.BoundArtifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry.Contract;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BindingException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class StepBindingsTest {
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Caller CALLER = new Caller("tenant-a", "analyst-a", 7);
    private static final TypeRef ROW = new TypeRef("MetricRow", 1, Cardinality.ONE);
    private static final TypeRef ROWS = new TypeRef("MetricRows", 1, Cardinality.MANY);
    private static final TypeRef SCOPE = new TypeRef("ScopeRef", 1, Cardinality.ONE);
    private static final TypeRef PERIODS = new TypeRef("PeriodsRef", 1, Cardinality.ONE);
    private static final String OUTPUT_CONTRACT = "test-output/v1";
    private static final StepBindings.CompletedOutputLookup NO_UPSTREAM = (step, output) -> Optional.empty();
    private static final StepBindings.StepPolicy NO_EXTRA_RELATION = new StepBindings.StepPolicy() {
        @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { }
        @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) { }
    };

    @Test
    void metadataPairCannotAdvertiseTwoCardinalitiesOrCompetingValidators() {
        Contract row = contract(ROW, "METRIC_ROW", "metric-row/1");
        Contract ambiguous = contract(new TypeRef("MetricRow", 1, Cardinality.MANY), "METRIC_ROW", "metric-row/1");
        assertThrows(IllegalArgumentException.class, () -> new ArtifactContractRegistry(List.of(row, ambiguous)));
        assertThrows(IllegalArgumentException.class, () -> new ArtifactContractRegistry(List.of(row, row)));
        assertDoesNotThrow(() -> registry());
    }

    @Test
    void trustedMetadataClaimsStillRequireExactSchemaTypeShapeAndValidActualRows() {
        Fixture fixture = fixture();
        ArtifactContractRegistry registry = registry();
        CampaignRunStore store = fixture.store();
        List<ArtifactDraft> invalid = List.of(
                draft("wrong-type", "OTHER", "metric-row/1", "{\"pv\":2}"),
                draft("wrong-schema", "METRIC_ROW", "metric-row/2", "{\"pv\":2}"),
                draft("forged-field", "METRIC_ROW", "metric-row/1", "{\"pv\":\"secret-invalid-number\"}"),
                draft("array-as-one", "METRIC_ROW", "metric-row/1", "[{\"pv\":2}]"),
                draft("null-as-one", "METRIC_ROW", "metric-row/1", "null"),
                draft("duplicate-field", "METRIC_ROW", "metric-row/1", "{\"pv\":2,\"pv\":3}"));
        for (ArtifactDraft artifact : invalid) {
            fixture.publish(artifact);
            BindingException error = assertThrows(BindingException.class,
                    () -> registry.validateArtifact(ROW, artifact.artifactId(), store, CALLER, (c, m) -> true));
            assertFalse(error.getMessage().contains("secret-invalid-number"));
        }
        fixture.publish(draft("object-as-many", "METRIC_ROWS", "metric-rows/1", "{\"pv\":2}"));
        fixture.publish(draft("invalid-many-member", "METRIC_ROWS", "metric-rows/1", "[{\"pv\":2},{}]"));
        fixture.publish(draft("valid-one", "METRIC_ROW", "metric-row/1", "{\"pv\":2}"));
        assertThrows(BindingException.class, () -> registry.validateArtifact(ROWS, "object-as-many", store, CALLER, (c, m) -> true));
        assertThrows(BindingException.class, () -> registry.validateArtifact(ROWS, "invalid-many-member", store, CALLER, (c, m) -> true));
        assertEquals(TYPE_MISMATCH, assertThrows(BindingException.class,
                () -> registry.validateArtifact(ROWS, "valid-one", store, CALLER, (c, m) -> true)).code());
        BoundArtifact valid = registry.validateArtifact(ROW, "valid-one", store, CALLER, (c, m) -> true);
        assertEquals(ROW, registry.typeOf(valid.metadata()));
        assertEquals(2, valid.payload().path("pv").intValue());
    }

    @Test
    void upstreamMustBeSucceededAndAnExplicitDirectDependencyWithTheExactPort() {
        Fixture fixture = fixture();
        fixture.publish(draft("upstream-ready-only", "METRIC_ROW", "metric-row/1", "{\"pv\":8}"));
        StepBindings bindings = bindings(fixture.store(), NO_EXTRA_RELATION);
        Signature signature = signature(Map.of("evidence", required(ROW)), Map.of());
        PlanSpec.Step consumer = step(Map.of("evidence", PlanBinding.output("producer", "selected")), List.of("producer"));
        assertEquals(UPSTREAM_OUTPUT_UNAVAILABLE, assertThrows(BindingException.class,
                () -> bindings.resolve(consumer, emptyInputs(), signature, NO_UPSTREAM)).code(),
                "A published child artifact does not prove that the producing step succeeded");
        AtomicInteger lookups = new AtomicInteger();
        StepBindings.CompletedOutputLookup completed = (id, port) -> {
            lookups.incrementAndGet();
            assertEquals("producer", id);
            assertEquals("selected", port);
            return Optional.of("upstream-ready-only");
        };
        PlanSpec.Step undeclared = step(consumer.inputBindings(), List.of());
        assertEquals(INVALID_BINDING, assertThrows(BindingException.class,
                () -> bindings.resolve(undeclared, emptyInputs(), signature, completed)).code());
        assertEquals(0, lookups.get(), "Do not query an undeclared producer");
        BoundInputs resolved = bindings.resolve(consumer, emptyInputs(), signature, completed);
        assertEquals(8, ((JsonNode) resolved.value("evidence")).path("pv").intValue());
        assertEquals("upstream-ready-only", resolved.artifact("evidence").metadata().ref().artifactId());
        resolved.close();
        assertThrows(IllegalStateException.class, resolved::values);
        assertThrows(IllegalStateException.class, resolved::artifacts);
        assertThrows(IllegalStateException.class, () -> bindings.reauthorize(signature, resolved));
    }

    @Test
    void frozenInputsNeedMatchingTypesCardinalitiesAndCurrentScopeAndPeriodAuthorization() {
        Fixture fixture = fixture();
        List<String> checked = new ArrayList<>();
        AtomicBoolean authorized = new AtomicBoolean(true);
        StepBindings bindings = new StepBindings(registry(), fixture.store(), CALLER, (c, m) -> true,
                (caller, type, value) -> {
                    assertEquals(CALLER, caller);
                    checked.add(type.name() + ":" + value);
                    return authorized.get();
                }, NO_EXTRA_RELATION);
        Map<String, Port> ports = Map.of("scope", required(SCOPE), "periods", required(PERIODS));
        FrozenInputSet frozen = inputs(ports, Map.of("scope", "scope-a", "periods", "periods-a"));
        PlanSpec.Step step = step(Map.of("scope", PlanBinding.input("scope"), "periods", PlanBinding.input("periods")), List.of());
        Signature signature = signature(ports, Map.of());
        try (BoundInputs resolved = bindings.resolve(step, frozen, signature, NO_UPSTREAM)) {
            assertEquals(frozen.inputValues(), resolved.values());
            assertEquals(Set.of("ScopeRef:scope-a", "PeriodsRef:periods-a"), Set.copyOf(checked));
            authorized.set(false);
            assertEquals(INPUT_ACCESS_DENIED, assertThrows(BindingException.class,
                    () -> bindings.reauthorize(signature, resolved)).code());
            authorized.set(true);
        }
        authorized.set(false);
        assertEquals(INPUT_ACCESS_DENIED, assertThrows(BindingException.class,
                () -> bindings.resolve(step, frozen, signature, NO_UPSTREAM)).code());
        authorized.set(true);
        FrozenInputSet switchedType = inputs(Map.of("scope", required(PERIODS), "periods", required(PERIODS)), frozen.inputValues());
        assertEquals(TYPE_MISMATCH, assertThrows(BindingException.class,
                () -> bindings.resolve(step, switchedType, signature, NO_UPSTREAM)).code());
        FrozenInputSet malformedOne = inputs(ports, Map.of("scope", List.of("scope-a"), "periods", "periods-a"));
        assertEquals(TYPE_MISMATCH, assertThrows(BindingException.class,
                () -> bindings.resolve(step, malformedOne, signature, NO_UPSTREAM)).code());
        FrozenInputSet missing = inputs(ports, Map.of("scope", "scope-a"));
        assertEquals(INVALID_BINDING, assertThrows(BindingException.class,
                () -> bindings.resolve(step, missing, signature, NO_UPSTREAM)).code());
    }

    @Test
    void artifactReuseAlwaysRechecksCurrentAuthorizationAndExpiryThroughRealStore() {
        Fixture fixture = fixture();
        ArtifactDraft artifact = draft("reuse", "METRIC_ROW", "metric-row/1", "{\"pv\":9}");
        fixture.publish(artifact);
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicInteger checks = new AtomicInteger();
        ArtifactAuthorizer authorizer = (caller, metadata) -> {
            checks.incrementAndGet();
            return authorized.get();
        };
        StepBindings bindings = new StepBindings(registry(), fixture.store(), CALLER, authorizer,
                (c, t, v) -> true, NO_EXTRA_RELATION);
        PlanSpec.Step step = step(Map.of("data", PlanBinding.artifact("reuse")), List.of());
        Signature signature = signature(Map.of("data", required(ROW)), Map.of());
        try (BoundInputs resolved = bindings.resolve(step, emptyInputs(), signature, NO_UPSTREAM)) {
            assertEquals(1, checks.get());
            authorized.set(false);
            assertThrows(SecurityException.class, () -> bindings.reauthorize(signature, resolved));
            assertThrows(SecurityException.class, () -> bindings.resolve(step, emptyInputs(), signature, NO_UPSTREAM));
            assertEquals(3, checks.get());
            authorized.set(true);
            StepBindings expired = new StepBindings(registry(), fixture.store(artifact.expiresAt()), CALLER, authorizer,
                    (c, t, v) -> true, NO_EXTRA_RELATION);
            assertThrows(SecurityException.class, () -> expired.reauthorize(signature, resolved));
            assertThrows(SecurityException.class, () -> expired.resolve(step, emptyInputs(), signature, NO_UPSTREAM));
            assertEquals(3, checks.get(), "Expiry fails before external authorization and payload loading");
            Caller newerIdentity = new Caller(CALLER.tenantId(), CALLER.subject(), CALLER.authVersion() + 1);
            StepBindings wrongVersion = new StepBindings(registry(), fixture.store(), newerIdentity, authorizer,
                    (c, t, v) -> true, NO_EXTRA_RELATION);
            assertThrows(SecurityException.class, () -> wrongVersion.resolve(step, emptyInputs(), signature, NO_UPSTREAM));
            assertEquals(3, checks.get());
        }
    }

    @Test
    void emptyCollectionIsPresentButMissingUnknownAndMalformedOutputsCannotSucceed() {
        Fixture fixture = fixture();
        fixture.publish(draft("empty", "METRIC_ROWS", "metric-rows/1", "[]"));
        fixture.publish(draft("bad-output", "METRIC_ROWS", "metric-rows/1", "[{\"pv\":\"bad\"}]"));
        StepBindings bindings = bindings(fixture.store(), NO_EXTRA_RELATION);
        PlanSpec.Step step = step(Map.of("data", PlanBinding.artifact("empty")), List.of());
        Signature signature = signature(Map.of("data", required(ROWS)), Map.of("rows", required(ROWS)));
        try (BoundInputs resolved = bindings.resolve(step, emptyInputs(), signature, NO_UPSTREAM)) {
            assertTrue(resolved.values().containsKey("data"));
            assertTrue(((JsonNode) resolved.value("data")).isEmpty());
            assertEquals(OUTPUT_CONTRACT_MISMATCH, assertThrows(BindingException.class,
                    () -> bindings.validateOutputs(step, signature, resolved, Map.of())).code());
            assertEquals(OUTPUT_CONTRACT_MISMATCH, assertThrows(BindingException.class,
                    () -> bindings.validateOutputs(step, signature, resolved, Map.of("rows", "empty", "surprise", "empty"))).code());
            assertEquals(ARTIFACT_CONTRACT_MISMATCH, assertThrows(BindingException.class,
                    () -> bindings.validateOutputs(step, signature, resolved, Map.of("rows", "bad-output"))).code());
            Map<String, ArtifactMetadata> accepted = bindings.validateOutputs(step, signature, resolved, Map.of("rows", "empty"));
            assertEquals(Set.of("rows"), accepted.keySet());
            assertEquals("empty", accepted.get("rows").ref().artifactId());
        }
    }

    @Test
    void registeredPoliciesControlScopePeriodsAndQualityWithoutAssumingAllScopesAreEqual() {
        Fixture fixture = fixture();
        fixture.publish(draft("a", "METRIC_ROW", "metric-row/1", "{\"pv\":3}"));
        fixture.publish(withPolicy(draft("b", "METRIC_ROW", "metric-row/1", "{\"pv\":4}"), "scope-b", "periods-a", "{\"status\":\"COMPLETE\"}"));
        fixture.publish(withPolicy(draft("other-period", "METRIC_ROW", "metric-row/1", "{\"pv\":5}"), "scope-b", "periods-other", "{\"status\":\"COMPLETE\"}"));
        fixture.publish(withPolicy(draft("partial", "METRIC_ROW", "metric-row/1", "{\"pv\":6}"), "scope-a", "periods-a", "{\"status\":\"PARTIAL\"}"));
        PlanSpec.Step step = step(Map.of("left", PlanBinding.artifact("a"), "right", PlanBinding.artifact("b")), List.of());
        Signature signature = signature(Map.of("left", required(ROW), "right", required(ROW)), Map.of("result", required(ROW)));
        StepBindings.StepPolicy singleScope = policy(Set.of("scope-a"), "periods-a");
        assertThrows(SecurityException.class,
                () -> bindings(fixture.store(), singleScope).resolve(step, emptyInputs(), signature, NO_UPSTREAM));
        StepBindings explicitlyMultipleScopes = bindings(fixture.store(), policy(Set.of("scope-a", "scope-b"), "periods-a"));
        try (BoundInputs resolved = explicitlyMultipleScopes.resolve(step, emptyInputs(), signature, NO_UPSTREAM)) {
            assertEquals(2, resolved.artifacts().size());
            assertThrows(SecurityException.class, () -> explicitlyMultipleScopes.validateOutputs(step, signature, resolved,
                    Map.of("result", "other-period")));
            assertThrows(BindingException.class, () -> explicitlyMultipleScopes.validateOutputs(step, signature, resolved,
                    Map.of("result", "partial")));
            assertEquals("b", explicitlyMultipleScopes.validateOutputs(step, signature, resolved,
                    Map.of("result", "b")).get("result").ref().artifactId());
        }
    }

    private static StepBindings.StepPolicy policy(Set<String> scopes, String periods) {
        return new StepBindings.StepPolicy() {
            private void validate(BoundArtifact artifact) {
                if (!scopes.contains(artifact.metadata().ref().scopeRef())
                        || !periods.equals(artifact.metadata().ref().periodsRef())) throw new SecurityException("BOUNDARY_MISMATCH");
            }
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {
                inputs.artifacts().values().forEach(this::validate);
            }
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs, Map<String, BoundArtifact> outputs) {
                outputs.values().forEach(this::validate);
            }
        };
    }

    private static ArtifactContractRegistry registry() {
        return new ArtifactContractRegistry(List.of(contract(ROW, "METRIC_ROW", "metric-row/1"),
                contract(ROWS, "METRIC_ROWS", "metric-rows/1")));
    }

    private static Contract contract(TypeRef type, String artifactType, String schema) {
        return new Contract(type, artifactType, schema,
                row -> row.isObject() && row.path("pv").isIntegralNumber() && row.path("pv").longValue() >= 0,
                (metadata, quality) -> "COMPLETE".equals(quality.path("status").asText()));
    }

    private static StepBindings bindings(CampaignRunStore store, StepBindings.StepPolicy policy) {
        return new StepBindings(registry(), store, CALLER, (c, m) -> true, (c, t, v) -> true, policy);
    }

    private static Port required(TypeRef type) { return new Port(type, true); }
    private static Signature signature(Map<String, Port> inputs, Map<String, Port> outputs) {
        return new Signature(inputs, OUTPUT_CONTRACT, outputs, Parameters.none());
    }
    private static FrozenInputSet inputs(Map<String, Port> ports, Map<String, Object> values) {
        return new FrozenInputSet("input-set", "run-1", ports, values);
    }
    private static FrozenInputSet emptyInputs() { return inputs(Map.of(), Map.of()); }
    private static PlanSpec.Step step(Map<String, PlanBinding> bindings, List<String> dependencies) {
        return new PlanSpec.Step("consumer", List.of(), PlanSpec.ExecutionMode.FIXED,
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "test", "1"), null,
                dependencies, bindings, Map.of(), OUTPUT_CONTRACT);
    }

    private static ArtifactDraft draft(String id, String type, String schema, String payload) {
        return new ArtifactDraft(id, type, schema, "scope-a", "periods-a", "{\"status\":\"COMPLETE\"}", "{}",
                NOW.plusSeconds(3600), payload);
    }
    private static ArtifactDraft withPolicy(ArtifactDraft base, String scope, String periods, String quality) {
        return new ArtifactDraft(base.artifactId(), base.type(), base.schemaVersion(), scope, periods, quality,
                base.provenanceJson(), base.expiresAt(), base.payloadJson());
    }

    private static Fixture fixture() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:step_binding_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql")).execute(source);
        return new Fixture(new JdbcTemplate(source), new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions) {
        CampaignRunStore store() { return store(NOW); }
        CampaignRunStore store(Instant now) {
            return new JdbcCampaignRunStore(jdbc, transactions, Clock.fixed(now, ZoneOffset.UTC));
        }
        void publish(ArtifactDraft artifact) {
            CampaignRunStore store = store();
            RunToken run = store.createRun(new RunDefinition(CALLER, "session-1", "run-1", "plan-1", 1, "{}"));
            String action = "action-" + artifact.artifactId();
            String child = "child-" + artifact.artifactId();
            store.prepareAction(run, new ActionSpec(action, "producer", "TOOL", "test", "1", "{}"));
            store.prepareChild(run, new ChildSpec(child, action, ChildMode.SYNC, "request-" + artifact.artifactId(),
                    new WireRequest("GET", "/test", "{}")));
            DispatchPermit permit = store.beginDispatch(run, child);
            try { store.publishReady(permit, artifact); }
            finally { store.callbackExited(permit); }
        }
    }
}
