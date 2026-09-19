package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.BoundInputs;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.StepBindings;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.LocalProcessLiveness;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** A real backend-only child JVM halts after commit; the parent reopens its H2 file and proves exit. */
@Timeout(45)
class CampaignProcessRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T08:00:00Z"), ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("1001", "analyst", 7);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "analyst", 7, false);
    private static final PlanSpec.ExecutorRef QUERY = new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "fixture", "1");
    private static final CapabilityCatalog.TypeRef EVIDENCE = new CapabilityCatalog.TypeRef("Evidence", 1, CapabilityCatalog.Cardinality.ONE);
    private static final ArtifactAuthorizer ALLOW = (caller, artifact) -> true;
    @TempDir Path temporary;

    @Test
    void deadJvmReadyReceiptIsReusedAndCompletedStepIsNotRerunAfterNativeGraphRecovery() throws Exception {
        String domain = "same-test-pid-domain-" + UUID.randomUUID();
        String url = "jdbc:h2:file:" + temporary.resolve("ledger").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;WRITE_DELAY=0";
        Path log = temporary.resolve("owner.log");
        Process child = new ProcessBuilder(javaExecutable(), "-cp", classpathJar().toString(),
                ExitingOwner.class.getName(), url, domain).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "The backend fixture must terminate within its deadline");
            assertEquals(0, child.exitValue(), () -> readLog(log));
            assertFalse(child.isAlive());

            Fixture fixture = fixture(url);
            var probe = new LocalProcessLiveness(domain);
            var recovery = new JdbcCampaignRecoveryStore(fixture.jdbc(), fixture.transactions(), CLOCK, probe.currentIdentity(), probe);
            RunToken original = fixture.runs().loadRun(OWNER, "run-1").orElseThrow().token();
            assertEquals(1, original.version());
            assertEquals(2, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_step_ledger WHERE callback_active=TRUE", Integer.class)
                    + fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_child_ledger WHERE callback_active=TRUE", Integer.class));
            Artifact originalEvidence = fixture.runs().readArtifact(OWNER, "artifact-a", ALLOW);
            AtomicInteger aAdapters = new AtomicInteger();
            AtomicInteger bAdapters = new AtomicInteger();
            AtomicInteger cAdapters = new AtomicInteger();
            AtomicInteger wireCalls = new AtomicInteger();
            var coordinator = new CampaignRecoveryCoordinator(recovery, fixture.runs(),
                    new StatisticsSubmissionReconciler(fixture.runs(), noNetwork()), (token, principal) -> {
                var executor = new PersistentPlanDriver.FixedExecutor(QUERY, policy(), context -> {
                    return switch (context.step().stepId()) {
                        case "a" -> {
                            aAdapters.incrementAndGet();
                            ChildRecord ready = context.child(child("a"), boundary -> {
                                wireCalls.incrementAndGet();
                                throw new AssertionError("The committed synchronous result must not be read again");
                            });
                            yield PersistentPlanDriver.Result.succeeded(Map.of("evidence", ready.artifactId()));
                        }
                        case "b" -> {
                            bAdapters.incrementAndGet();
                            var evidence = context.inputs().artifact("upstream");
                            assertEquals(23, evidence.payload().path("pv").intValue());
                            yield PersistentPlanDriver.Result.succeeded(Map.of("evidence", evidence.metadata().ref().artifactId()));
                        }
                        default -> { cAdapters.incrementAndGet(); throw new AssertionError("Completed C must not run again"); }
                    };
                });
                var driver = new PersistentPlanDriver(token, fixture.runs(), fixture.steps(), catalog(), contracts(),
                        List.of(executor), (caller, frozen) -> true, ALLOW, (caller, type, value) -> true);
                return new CampaignRecoveryCoordinator.Runtime(driver, driver.compile(new MemorySaver()));
            }, (definition, principal) -> true);

            var resumed = coordinator.resume(original, PRINCIPAL);

            assertEquals(CampaignRecoveryCoordinator.Outcome.SCANNED, resumed.outcome());
            assertEquals(2, resumed.recoveredCallbacks());
            assertEquals(2, resumed.scan().advancedSteps());
            assertEquals(original.version() + 1, resumed.token().version());
            assertNotEquals(original.advanceToken(), resumed.token().advanceToken());
            assertEquals(1, aAdapters.get());
            assertEquals(1, bAdapters.get());
            assertEquals(0, cAdapters.get());
            assertEquals(0, wireCalls.get());
            fixture.steps().steps(resumed.token()).forEach(step -> {
                assertEquals(CampaignStepStore.StepStatus.SUCCEEDED, step.status());
                assertFalse(step.callbackActive());
            });
            assertEquals(originalEvidence, fixture.runs().readArtifact(OWNER, "artifact-a", ALLOW));
            assertEquals(Map.of("evidence", "artifact-c"), fixture.steps().step(resumed.token(), "c").orElseThrow().outputs());
            assertEquals(2, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery", Integer.class));
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_callback_recovery "
                    + "WHERE proof_code NOT IN ('PROCESS_ABSENT','PROCESS_EXITED','PROCESS_ID_REUSED')", Integer.class));
            assertFalse(fixture.steps().mayAdvance(original));
            // SHUTDOWN closes the session; JdbcTemplate's post-execution getWarnings is invalid then.
            try (var connection = fixture.jdbc().getDataSource().getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** No Spring app, network server or model. halt deliberately skips the ledger callbacks' finally. */
    public static final class ExitingOwner {
        public static void main(String[] arguments) {
            Fixture fixture = fixture(arguments[0]);
            new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_2__campaign_step_ledger.sql"),
                    new ClassPathResource("sql/migration/V20260919_3__campaign_run_owner.sql")).execute(fixture.jdbc().getDataSource());
            FrozenCampaignRun frozen = frozen();
            var probe = new LocalProcessLiveness(arguments[1]);
            RunToken token = new JdbcCampaignRecoveryStore(fixture.jdbc(), fixture.transactions(), CLOCK, probe.currentIdentity(), probe)
                    .recover(fixture.runs().createRun(frozen.definition(OWNER, "session-1"))).token();
            fixture.steps().initialize(token, frozen.plan().steps().stream().map(step ->
                    new CampaignStepStore.StepSpec(step.stepId(), FrozenCampaignRun.encode(step), step.dependsOn(),
                            Set.of("evidence"), Set.of("evidence"))).toList());
            var completed = fixture.steps().beginStep(token, "c");
            var c = publish(fixture, token, "c");
            fixture.runs().callbackExited(c);
            fixture.steps().settle(completed, CampaignStepStore.StepStatus.SUCCEEDED,
                    Map.of("evidence", "artifact-c"), null, ALLOW);
            fixture.steps().callbackExited(completed);
            fixture.steps().beginStep(token, "a");
            publish(fixture, token, "a");
            Runtime.getRuntime().halt(0);
        }
    }

    private static DispatchPermit publish(Fixture fixture, RunToken token, String id) {
        PlanSpec.Step step = frozen().plan().steps().stream().filter(value -> value.stepId().equals(id)).findFirst().orElseThrow();
        fixture.runs().prepareAction(token, new ActionSpec("action-" + id, id, QUERY.kind().name(), QUERY.name(), QUERY.version(),
                FrozenCampaignRun.encode(step)));
        fixture.runs().prepareChild(token, child(id));
        DispatchPermit permit = fixture.runs().beginDispatch(token, "child-" + id);
        fixture.runs().publishReady(permit, new ArtifactDraft("artifact-" + id, "Evidence", "evidence/v1", "scope-1", "periods-1",
                "{\"completeness\":\"PARTIAL\"}", "{\"source\":\"original\"}", CLOCK.instant().plusSeconds(3600), "{\"pv\":23}"));
        return permit;
    }

    private static ChildSpec child(String id) {
        return new ChildSpec("child-" + id, "action-" + id, ChildMode.SYNC, "request-" + id, new WireRequest("GET", "/fixture", "{}"));
    }

    private static FrozenCampaignRun frozen() {
        List<PlanSpec.Step> steps = List.of(step("a", List.of(), Map.of()), step("b", List.of("a"),
                Map.of("upstream", new PlanBinding(PlanBinding.Source.STEP_OUTPUT, null, "a", "evidence", null))),
                step("c", List.of(), Map.of()));
        PlanSpec plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan-1", 1, "run-1", "inputs-1",
                List.of(new PlanSpec.Goal("goal", "Analyze frozen evidence", true, "Deliver verified evidence")), steps);
        PlanningAssessment assessment = new PlanningAssessment("plan-1", 1, "catalog/v1",
                List.of(new PlanningAssessment.Requirement("delivery", "goal", PlanningAssessment.RequirementKind.DELIVERY,
                        true, "delivery/v1", "1", Map.of())),
                List.of(new PlanningAssessment.CoverageBinding("delivery", List.of(new PlanningAssessment.EvidenceOutput("b", "evidence")))), List.of());
        return FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs-1", "run-1", Map.of(), Map.of()), assessment);
    }

    private static PlanSpec.Step step(String id, List<String> dependsOn, Map<String, PlanBinding> bindings) {
        return new PlanSpec.Step(id, List.of("goal"), PlanSpec.ExecutionMode.FIXED, QUERY, null, dependsOn, bindings, Map.of(), "evidence/v1");
    }

    private static CapabilityCatalog catalog() {
        return new CapabilityCatalog() {
            @Override public String version() { return "catalog/v1"; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
                return QUERY.equals(executor) ? Optional.of(new Capability(QUERY, new Signature(Map.of("upstream", new Port(EVIDENCE, false)),
                        "evidence/v1", Map.of("evidence", new Port(EVIDENCE, true)), Parameters.none()), false)) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return Optional.of(new Criterion(ref, version, PlanningAssessment.RequirementKind.DELIVERY, Parameters.none(), Set.of(EVIDENCE)));
            }
        };
    }

    private static ArtifactContractRegistry contracts() {
        return new ArtifactContractRegistry(List.of(new ArtifactContractRegistry.Contract(EVIDENCE, "Evidence", "evidence/v1",
                node -> node.isObject() && node.path("pv").isIntegralNumber(), (metadata, quality) -> quality.isObject())));
    }

    private static StepBindings.StepPolicy policy() {
        return new StepBindings.StepPolicy() {
            @Override public void validateInputs(PlanSpec.Step step, BoundInputs inputs) {}
            @Override public void validateOutputs(PlanSpec.Step step, BoundInputs inputs,
                    Map<String, ArtifactContractRegistry.BoundArtifact> outputs) {}
        };
    }

    private static ShortLinkBusinessGateway noNetwork() {
        return new ShortLinkBusinessGateway() {
            @Override public ToolResult get(String path, ToolContext context, Map<String, Object> query) { throw new AssertionError("Unexpected GET"); }
            @Override public ToolResult post(String path, ToolContext context, Map<String, Object> body) { throw new AssertionError("Unexpected POST"); }
            @Override public ToolResult recoverExistingStatisticsJob(ToolContext context, Map<String, Object> request) { throw new AssertionError("Unexpected recovery"); }
        };
    }

    private static Fixture fixture(String url) {
        var source = new DriverManagerDataSource(url, "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        return new Fixture(jdbc, transactions, new JdbcCampaignRunStore(jdbc, transactions, CLOCK),
                new JdbcCampaignStepStore(jdbc, transactions, CLOCK));
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, JdbcCampaignRunStore runs, JdbcCampaignStepStore steps) {}

    /** Manifest classpath keeps the Windows command line bounded and preserves non-ASCII repo paths. */
    private Path classpathJar() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, Stream.of(classpath.split(java.io.File.pathSeparator))
                .map(value -> Path.of(value).toAbsolutePath().toUri().toASCIIString()).collect(Collectors.joining(" ")));
        Path jar = temporary.resolve("fixture-classpath.jar");
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        return jar;
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }

    private static String readLog(Path log) {
        try { return Files.readString(log, java.nio.charset.Charset.defaultCharset()); }
        catch (Exception unavailable) { return "Child JVM failed; see " + log; }
    }
}
