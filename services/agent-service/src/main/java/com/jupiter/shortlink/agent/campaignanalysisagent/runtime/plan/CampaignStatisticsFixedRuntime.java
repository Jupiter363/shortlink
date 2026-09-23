package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.binding.ArtifactContractRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignAdvanceOutcomeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignConversationSessionOwner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRecoveryStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.CampaignRecoveryCoordinator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsSubmissionReconciler;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process.LocalProcessLiveness;
import com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsDurableProjector;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit, transport-neutral composition of one FIXED statistics profile. The durable intake is
 * shared across requests; each admitted Run creates its own executor registration and Graph.
 * Neither construction nor this class exposes a public HTTP or model-tool entry point.
 */
public final class CampaignStatisticsFixedRuntime implements AutoCloseable {
    public static final String PROFILE_REF = "campaign-statistics-fixed";
    public static final String PROFILE_VERSION = "1";

    private static final String CATALOG_VERSION = "campaign-statistics-fixed-catalog/v1";
    private static final String DELIVERY_REF = "statistics-pages-delivery";
    private static final String DELIVERY_VERSION = "1";
    private static final CampaignPlanRuntimeRegistry.SaverKey SAVER_KEY =
            new CampaignPlanRuntimeRegistry.SaverKey("campaign-mysql-graph", "1");

    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignStepStore steps;
    private final JdbcCampaignStatisticsResultStore results;
    private final JdbcCampaignAdvanceOutcomeStore outcomes;
    private final CampaignCurrentPrincipalResolver principals;
    private final CampaignStatisticsArtifactAuthorizer artifactAuthorizer;
    private final CampaignStatisticsPlanFactory plans;
    private final CampaignStatisticsDurableProjector projector;
    private final CampaignRunIntake intake;
    private final ThreadPoolExecutor executor;

    /** All JDBC stores must use this exact writable REQUIRED transaction template and data source. */
    public CampaignStatisticsFixedRuntime(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            AgentAuthorityClient authority, ShortLinkBusinessGateway gateway, BaseCheckpointSaver saver,
            String trustedProcessDomain, ProcessCapacityExecutor.Limits limits) {
        Objects.requireNonNull(jdbc, "STATISTICS_JDBC_REQUIRED");
        Objects.requireNonNull(transactions, "STATISTICS_TRANSACTION_REQUIRED");
        Objects.requireNonNull(clock, "STATISTICS_CLOCK_REQUIRED");
        Objects.requireNonNull(authority, "STATISTICS_AUTHORITY_REQUIRED");
        Objects.requireNonNull(gateway, "STATISTICS_GATEWAY_REQUIRED");
        Objects.requireNonNull(saver, "STATISTICS_SAVER_REQUIRED");
        Objects.requireNonNull(limits, "STATISTICS_CAPACITY_REQUIRED");

        LocalProcessLiveness liveness = new LocalProcessLiveness(trustedProcessDomain);
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock);
        this.steps = new JdbcCampaignStepStore(jdbc, transactions, clock);
        this.results = new JdbcCampaignStatisticsResultStore(jdbc, transactions, clock);
        this.outcomes = new JdbcCampaignAdvanceOutcomeStore(jdbc, transactions, clock);
        JdbcCampaignRunIntakeStore requests = new JdbcCampaignRunIntakeStore(jdbc, transactions, clock,
                runs, CampaignRunStore.Limits.defaults().definitionBytes());
        JdbcCampaignRecoveryStore recovery = new JdbcCampaignRecoveryStore(jdbc, transactions, clock,
                liveness.currentIdentity(), liveness);
        var sessions = new JdbcCampaignConversationSessionOwner(jdbc, transactions, clock);
        this.principals = new CampaignCurrentPrincipalResolver(authority, sessions);
        var runAuthorizer = new CampaignStatisticsRunAuthorizer(authority);
        var inputAuthorizer = new CampaignStatisticsCurrentInputAuthorizer(authority);
        var queryAuthorizer = new CampaignStatisticsQueryAuthorizer(authority);
        this.artifactAuthorizer = new CampaignStatisticsArtifactAuthorizer(runs, runAuthorizer, queryAuthorizer);
        CapabilityCatalog catalog = catalog();
        var contracts = new ArtifactContractRegistry(List.of(StatisticsJobFixedExecutor.artifactContract()));
        this.plans = new CampaignStatisticsPlanFactory(catalog, DELIVERY_REF, DELIVERY_VERSION);
        this.projector = new CampaignStatisticsDurableProjector(runs, results);

        var profile = new CampaignRunIntake.Profile(PROFILE_REF, PROFILE_VERSION, catalog, contracts,
                runAuthorizer, artifactAuthorizer, inputAuthorizer, context -> {
            var statistics = new StatisticsJobFixedExecutor(context.token().definition(), context.principal(),
                    gateway, queryAuthorizer);
            if (!statistics.authorized()) throw new SecurityException("STATISTICS_QUERY_ACCESS_DENIED");
            var versions = new CampaignPlanRuntimeRegistry.Versions(catalog.version(),
                    "statistics-job-pages/v1", "statistics-query-job/v1",
                    FrozenCampaignRun.RUNNER, FrozenCampaignRun.TOPOLOGY);
            var registry = new CampaignPlanRuntimeRegistry(versions, catalog, contracts,
                    List.of(statistics.registration()), List.of(), null, SAVER_KEY);
            var runtime = new CampaignPlanRuntimeFactory(registry);
            var definition = context.token().definition();
            var prepared = runtime.open(new CampaignPlanRuntimeFactory.Request(definition.caller(),
                    definition.sessionId(), context.token(), runs, steps, context.runAuthorizer(),
                    context.artifactAuthorizer(), context.inputAuthorizer(), context.scope()));
            var compiled = prepared.compile(new CampaignPlanRuntimeRegistry.SaverBinding(SAVER_KEY, saver));
            return new CampaignRecoveryCoordinator.Runtime(compiled.driver(), compiled.graph(),
                    statistics.resultTargets());
        });

        // The admission queue contains only WorkRefs. A second, short bounded queue covers the
        // handoff race between a completed worker releasing its permit and its pool thread idling.
        AtomicInteger threadNumber = new AtomicInteger();
        int workers = limits.activeAdvances();
        this.executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), runnable -> {
                    Thread thread = new Thread(runnable,
                            "campaign-statistics-fixed-" + threadNumber.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        try {
            this.intake = new CampaignRunIntake(requests, runs, recovery,
                    new StatisticsSubmissionReconciler(runs, gateway),
                    new StatisticsJobResultReceiver(runs, results, gateway, clock), null,
                    List.of(profile), principals, limits, executor, null, List.of(), outcomes);
        } catch (RuntimeException | Error failure) {
            executor.shutdown();
            throw failure;
        }
    }

    public CampaignRunIntake intake() { return intake; }
    public CampaignStatisticsPlanFactory plans() { return plans; }
    public JdbcCampaignRunStore runs() { return runs; }
    public JdbcCampaignStepStore steps() { return steps; }
    public JdbcCampaignStatisticsResultStore results() { return results; }
    public JdbcCampaignAdvanceOutcomeStore outcomes() { return outcomes; }
    public CampaignCurrentPrincipalResolver principals() { return principals; }
    public CampaignStatisticsArtifactAuthorizer artifactAuthorizer() { return artifactAuthorizer; }
    public CampaignStatisticsDurableProjector projector() { return projector; }

    /** Cancel admission before gracefully stopping its externally owned worker pool. */
    @Override
    public void close() {
        intake.close();
        executor.shutdown();
    }

    private static CapabilityCatalog catalog() {
        var capability = StatisticsJobFixedExecutor.capability();
        var delivery = new CapabilityCatalog.Criterion(DELIVERY_REF, DELIVERY_VERSION,
                PlanningAssessment.RequirementKind.DELIVERY, CapabilityCatalog.Parameters.none(),
                Set.of(StatisticsJobFixedExecutor.OUTPUT_TYPE));
        return new CapabilityCatalog() {
            @Override public String version() { return CATALOG_VERSION; }
            @Override public Optional<Capability> capability(PlanSpec.ExecutorRef executor) {
                return capability.executor().equals(executor) ? Optional.of(capability) : Optional.empty();
            }
            @Override public Optional<Policy> policy(String ref, String version) { return Optional.empty(); }
            @Override public Optional<Criterion> criterion(String ref, String version) {
                return delivery.criterionRef().equals(ref) && delivery.criterionVersion().equals(version)
                        ? Optional.of(delivery) : Optional.empty();
            }
        };
    }
}
