package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Cardinality;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Capability;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Criterion;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Parameters;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Port;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.Signature;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CampaignPlanRuntimeFactoryTest {
    private static final Caller OWNER = new Caller("tenant-1", "analyst-1", 7);
    private static final String SESSION = "session-1";
    private static final PlanSpec.ExecutorRef EXECUTOR =
            new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "query", "1");
    private static final TypeRef EVIDENCE = new TypeRef("CampaignEvidence", 1, Cardinality.ONE);
    private static final String OUTPUT_CONTRACT = "evidence/v1";

    @Test
    void openOnlyReadsAuthoritativeRunAndCompileIsTheFirstDriverAndGraphBoundary() throws Exception {
        Fixture fixture = fixture(RunStatus.ACTIVE);
        CampaignPlanRuntimeFactory.PreparedRuntime prepared = fixture.factory.open(fixture.request());

        assertThat(prepared.token()).isEqualTo(fixture.token);
        assertThat(prepared.frozen()).isEqualTo(fixture.frozen);
        verifyNoInteractions(fixture.steps);

        CampaignPlanRuntimeFactory.CompiledRuntime compiled = prepared.compile(
                new CampaignPlanRuntimeRegistry.SaverBinding(fixture.registry.saverKey(), new MemorySaver()));

        assertThat(compiled.token()).isEqualTo(fixture.token);
        assertThat(compiled.frozen()).isEqualTo(fixture.frozen);
        assertThat(compiled.driver()).isNotNull();
        assertThat(compiled.graph()).isNotNull();
        verify(fixture.steps).initialize(eq(fixture.token), any());
    }

    @Test
    void openRejectsOwnerSessionStaleAndTerminalRunsBeforeAnyStepWrite() {
        Fixture active = fixture(RunStatus.ACTIVE);
        CampaignPlanRuntimeFactory.Request wrongOwner = new CampaignPlanRuntimeFactory.Request(
                new Caller("tenant-2", OWNER.subject(), OWNER.authVersion()), SESSION, active.token,
                active.runs, active.steps, (caller, inputs) -> true, (caller, metadata) -> true,
                (caller, type, value) -> true, null);
        assertThatThrownBy(() -> active.factory.open(wrongOwner))
                .isInstanceOf(SecurityException.class)
                .hasMessage("PLAN_RUNTIME_OWNER_SESSION_MISMATCH");
        verifyNoInteractions(active.steps);

        Fixture mismatch = fixture(RunStatus.ACTIVE);
        CampaignRunStore.RunToken stale = new RunToken(mismatch.definition, mismatch.token.version() + 1, "new-lease");
        CampaignPlanRuntimeFactory.Request staleRequest = new CampaignPlanRuntimeFactory.Request(
                OWNER, SESSION, stale, mismatch.runs, mismatch.steps, (caller, inputs) -> true,
                (caller, metadata) -> true, (caller, type, value) -> true, null);
        assertThatThrownBy(() -> mismatch.factory.open(staleRequest))
                .isInstanceOf(SecurityException.class)
                .hasMessage("PLAN_RUNTIME_TOKEN_STALE");
        verifyNoInteractions(mismatch.steps);

        for (RunStatus status : List.of(RunStatus.CANCELLED, RunStatus.SUPERSEDED)) {
            Fixture terminal = fixture(status);
            assertThatThrownBy(() -> terminal.factory.open(terminal.request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PLAN_RUNTIME_RUN_NOT_ACTIVE");
            verifyNoInteractions(terminal.steps);
        }
    }

    @Test
    void registryAndSaverBindingsAreImmutableAndFailClosed() {
        Fixture fixture = fixture(RunStatus.ACTIVE);
        CampaignPlanRuntimeRegistry.SaverBinding wrongSaver = new CampaignPlanRuntimeRegistry.SaverBinding(
                new CampaignPlanRuntimeRegistry.SaverKey("mysql", "v9"), new MemorySaver());
        CampaignPlanRuntimeFactory.PreparedRuntime prepared = fixture.factory.open(fixture.request());
        assertThatThrownBy(() -> prepared.compile(wrongSaver))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_RUNTIME_SAVER_MISMATCH");
        verifyNoInteractions(fixture.steps);

        assertThatThrownBy(() -> new CampaignPlanRuntimeRegistry(
                fixture.registry.versions(), fixture.registry.catalog(), fixture.registry.contracts(),
                List.of(fixture.executor, fixture.executor), List.of(), null, fixture.registry.saverKey()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_RUNTIME_EXECUTOR_DUPLICATE");

        assertThat(fixture.factory.open(fixture.request()))
                .isNotSameAs(fixture.factory.open(fixture.request()));
    }

    @Test
    void registryVersionMismatchIsRejectedBeforeDriverConstruction() {
        Fixture fixture = fixture(RunStatus.ACTIVE, "runner/other");
        assertThatThrownBy(() -> fixture.factory.open(fixture.request()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_RUNTIME_FROZEN_VERSION_MISMATCH");
        verifyNoInteractions(fixture.steps);
    }

    private static Fixture fixture(RunStatus status) {
        return fixture(status, FrozenCampaignRun.RUNNER);
    }

    private static Fixture fixture(RunStatus status, String runnerVersion) {
        FrozenCampaignRun frozen = frozen();
        RunDefinition definition = new RunDefinition(OWNER, SESSION, frozen.plan().runId(), frozen.plan().planId(),
                frozen.plan().revision(), frozen.definition(OWNER, SESSION).definitionJson());
        RunToken token = new RunToken(definition, 4, "lease-4");
        CampaignRunStore runs = mock(CampaignRunStore.class);
        CampaignStepStore steps = mock(CampaignStepStore.class);
        when(runs.loadRun(eq(OWNER), eq(definition.runId())))
                .thenReturn(Optional.of(new RunRecord(definition, status, token.version(), token.advanceToken())));
        PersistentPlanDriver.FixedExecutor executor = new PersistentPlanDriver.FixedExecutor(EXECUTOR,
                new StepBindings.StepPolicy() {
                    @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) { }
                    @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs,
                            Map<String, ArtifactContractRegistry.BoundArtifact> outputs) { }
                }, context -> PersistentPlanDriver.Result.succeeded(Map.of()));
        CapabilityCatalog catalog = catalog();
        ArtifactContractRegistry contracts = new ArtifactContractRegistry(List.of());
        CampaignPlanRuntimeRegistry.Versions versions = new CampaignPlanRuntimeRegistry.Versions(
                catalog.version(), "contracts/v1", "executors/v1", runnerVersion, FrozenCampaignRun.TOPOLOGY);
        CampaignPlanRuntimeRegistry registry = new CampaignPlanRuntimeRegistry(versions, catalog, contracts,
                List.of(executor), List.of(), null,
                new CampaignPlanRuntimeRegistry.SaverKey("memory", "v1"));
        CampaignPlanRuntimeFactory factory = new CampaignPlanRuntimeFactory(registry);
        return new Fixture(frozen, definition, token, runs, steps, registry, factory, executor);
    }

    private static CapabilityCatalog catalog() {
        Signature signature = new Signature(Map.of(), OUTPUT_CONTRACT,
                Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none());
        Capability capability = new Capability(EXECUTOR, signature, false);
        Criterion criterion = new Criterion("delivery", "1", PlanningAssessment.RequirementKind.DELIVERY,
                Parameters.none(), Set.of(EVIDENCE));
        return new CapabilityCatalog() {
            @Override public String version() { return "catalog/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef ref) {
                return EXECUTOR.equals(ref) ? Optional.of(capability) : Optional.empty();
            }
            @Override public Optional<CapabilityCatalog.Policy> policy(String ref, String version) {
                return Optional.empty();
            }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return "delivery".equals(ref) && "1".equals(version) ? Optional.of(criterion) : Optional.empty();
            }
        };
    }

    private static FrozenCampaignRun frozen() {
        PlanSpec.Goal goal = new PlanSpec.Goal("goal", "Answer the campaign question", true, "Deliver evidence");
        PlanSpec.Step step = new PlanSpec.Step("step-1", List.of("goal"), PlanSpec.ExecutionMode.FIXED,
                EXECUTOR, null, List.of(), Map.of(), Map.of(), OUTPUT_CONTRACT);
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(goal), List.of(step));
        FrozenInputSet inputs = new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of());
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("requirement-1", "goal",
                        PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("requirement-1",
                        List.of(new PlanningAssessment.EvidenceOutput("step-1", "evidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, inputs, assessment);
    }

    private record Fixture(FrozenCampaignRun frozen, RunDefinition definition, RunToken token,
                           CampaignRunStore runs, CampaignStepStore steps,
                           CampaignPlanRuntimeRegistry registry, CampaignPlanRuntimeFactory factory,
                           PersistentPlanDriver.FixedExecutor executor) {
        CampaignPlanRuntimeFactory.Request request() {
            return new CampaignPlanRuntimeFactory.Request(OWNER, SESSION, token, runs, steps,
                    (caller, inputs) -> true, (caller, metadata) -> true,
                    (caller, type, value) -> true, null);
        }
    }
}
