package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanValidator;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRevisionApplier;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignStatisticsConsumerStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcReplanReceiptStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit, opt-in composition boundary for a trusted campaign replan.
 *
 * <p>This factory is intentionally a plain Java object.  It is not a Spring bean and it is not
 * reachable from the chat, tool, or HTTP registries.  A trusted adapter must first resolve a
 * current {@link CampaignRunStore.RunToken}; {@link #open(CampaignRunStore.Caller,
 * CampaignRunStore.RunToken)} then checks that token against the persisted owner and frozen run
 * definition before returning a bound runtime.</p>
 *
 * <p>The stores and applier share the supplied writable REQUIRED transaction template.  The
 * native graph precompiler is created by the returned runtime for every execute call, so a frozen
 * input set or graph identity cannot leak between invocations.</p>
 */
public final class CampaignReplanRuntimeFactory {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PlanValidator planValidator;
    private final ProcessExecutionScope processScope;
    private final CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer;
    private final CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer;
    private final JdbcCampaignRunStore runs;
    private final JdbcCampaignStatisticsConsumerStore consumers;
    private final JdbcReplanReceiptStore receipts;
    private final JdbcCampaignRevisionApplier applier;

    /**
     * Creates a composition factory.  {@code processScope} may be null when the trusted caller
     * does not use a process lifetime scope; every other dependency is required explicitly.
     */
    public CampaignReplanRuntimeFactory(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Clock clock,
            PlanValidator planValidator,
            ProcessExecutionScope processScope,
            CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer,
            CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer) {
        this(jdbc, transactions, clock, planValidator, processScope, capabilityAuthorizer, consumerAuthorizer, ignored -> {});
    }

    public CampaignReplanRuntimeFactory(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            PlanValidator planValidator, ProcessExecutionScope processScope,
            CampaignReplanApplicationService.CapabilityAuthorizer capabilityAuthorizer,
            CampaignStatisticsConsumerStore.Authorizer consumerAuthorizer,
            java.util.function.Consumer<CampaignRunStore.RunToken> additionalCallbackGate) {
        this.jdbc = Objects.requireNonNull(jdbc, "REPLAN_JDBC_REQUIRED");
        this.transactions = Objects.requireNonNull(transactions, "REPLAN_TRANSACTION_REQUIRED");
        this.clock = Objects.requireNonNull(clock, "REPLAN_CLOCK_REQUIRED");
        this.planValidator = Objects.requireNonNull(planValidator, "REPLAN_PLAN_VALIDATOR_REQUIRED");
        this.processScope = processScope;
        this.capabilityAuthorizer = Objects.requireNonNull(capabilityAuthorizer,
                "REPLAN_CAPABILITY_AUTHORIZER_REQUIRED");
        this.consumerAuthorizer = Objects.requireNonNull(consumerAuthorizer,
                "REPLAN_CONSUMER_AUTHORIZER_REQUIRED");
        requireWritableRequiredTransaction(jdbc, transactions);

        // Each constructor below repeats its local composition proof.  Keeping these concrete
        // stores here makes the transaction/data-source sharing explicit at the one trusted seam.
        this.runs = new JdbcCampaignRunStore(jdbc, transactions, clock);
        this.consumers = new JdbcCampaignStatisticsConsumerStore(jdbc, transactions, clock, runs);
        this.receipts = new JdbcReplanReceiptStore(jdbc, transactions, clock);
        this.applier = new JdbcCampaignRevisionApplier(jdbc, transactions, runs, consumers, receipts,
                consumerAuthorizer, additionalCallbackGate);
    }

    /**
     * Binds one current owner and run token to an immutable persisted definition.
     *
     * <p>The lookup is deliberately by the caller supplied owner and run id.  A token from a
     * different owner, an old row version, a superseded revision, or a missing run is rejected
     * before any graph or persistence component is constructed.</p>
     */
    public CampaignReplanRuntime open(CampaignRunStore.Caller owner,
                                      CampaignRunStore.RunToken baseRun) {
        Objects.requireNonNull(owner, "REPLAN_OWNER_REQUIRED");
        Objects.requireNonNull(baseRun, "REPLAN_RUN_REQUIRED");
        if (baseRun.definition() == null || !owner.equals(baseRun.definition().caller())) {
            throw new IllegalArgumentException("REPLAN_OWNER_TOKEN_MISMATCH");
        }

        CampaignRunStore.RunRecord persisted = runs.loadRun(owner, baseRun.definition().runId())
                .orElseThrow(() -> new IllegalStateException("REPLAN_BASE_RUN_NOT_FOUND"));
        if (persisted.status() != CampaignRunStore.RunStatus.ACTIVE) {
            throw new IllegalStateException("REPLAN_BASE_RUN_NOT_ACTIVE");
        }
        if (!persisted.token().equals(baseRun)) {
            throw new SecurityException("REPLAN_BASE_TOKEN_STALE");
        }

        // FrozenCampaignRun.read validates the run/plan/revision identity encoded in the row.
        FrozenCampaignRun frozen = FrozenCampaignRun.read(persisted.definition());
        if (!owner.equals(persisted.definition().caller())
                || !baseRun.definition().equals(persisted.definition())) {
            throw new SecurityException("REPLAN_BASE_DEFINITION_MISMATCH");
        }
        return new CampaignReplanRuntime(owner, baseRun, frozen, planValidator, processScope,
                capabilityAuthorizer, receipts, applier);
    }

    private static void requireWritableRequiredTransaction(JdbcTemplate jdbc,
                                                           TransactionTemplate transactions) {
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null
                || manager.getDataSource() != jdbc.getDataSource()
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly()) {
            throw new IllegalArgumentException(
                    "Replan runtime requires one writable REQUIRED DataSource transaction");
        }
    }

}
