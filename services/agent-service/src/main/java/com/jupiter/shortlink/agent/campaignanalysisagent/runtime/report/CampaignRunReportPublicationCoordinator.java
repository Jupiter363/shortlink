package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportApplicationService;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.GoalAssessor;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.Binding;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunResultStore.BindingDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignTrustedRunResultAdapter;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.ExecutionStatus;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes one verified report and binds its durable run-result reference atomically.
 *
 * <p>The report application service still owns publication authorization and evidence validation;
 * the result adapter still owns request-bound report credentials and token fencing. This class only
 * supplies the shared transaction boundary so a failed bind cannot leave an unreferenced report
 * revision that looks successfully delivered.</p>
 */
public final class CampaignRunReportPublicationCoordinator {
    private final CampaignReportApplicationService reports;
    private final CampaignTrustedRunResultAdapter results;
    private final TransactionTemplate transactions;

    public CampaignRunReportPublicationCoordinator(CampaignReportApplicationService reports,
                                                   CampaignTrustedRunResultAdapter results,
                                                   JdbcReportLifecycleStore lifecycle,
                                                   JdbcTemplate jdbc,
                                                   TransactionTemplate transactions) {
        this.reports = Objects.requireNonNull(reports, "REPORT_APPLICATION_REQUIRED");
        this.results = Objects.requireNonNull(results, "RUN_RESULT_ADAPTER_REQUIRED");
        Objects.requireNonNull(lifecycle, "REPORT_LIFECYCLE_STORE_REQUIRED");
        Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!reports.usesLifecycleStore(lifecycle)
                || !results.usesLifecycleStore(lifecycle)
                || !results.usesTransactionTemplate(transactions)
                || !lifecycle.usesTransactionTemplate(transactions)
                || !(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || manager.getDataSource() != jdbc.getDataSource()
                || !lifecycle.sharesDataSource(jdbc)
                || transactions.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || transactions.isReadOnly())
            throw new IllegalArgumentException(
                    "Report publication requires one shared writable REQUIRED DataSource transaction");
    }

    /** Publishes and binds one exact report revision; all durable mutations join one transaction. */
    public PublicationResult publishAndBind(Caller caller, RunToken token,
                                            CampaignReportApplicationService.PublishRequest publication,
                                            BindingDraft binding) {
        Objects.requireNonNull(caller, "RUN_RESULT_CALLER_REQUIRED");
        Objects.requireNonNull(token, "RUN_RESULT_TOKEN_REQUIRED");
        Objects.requireNonNull(publication, "REPORT_PUBLISH_REQUEST_REQUIRED");
        Objects.requireNonNull(binding, "RUN_RESULT_DRAFT_REQUIRED");
        validateIdentity(caller, token, publication, binding);
        validateDeliveryStatus(publication, binding);
        return results.withCurrentRunAndBinding(caller, token, publication.owner(), publication.capability(), () -> {
            ReportLifecycleStore.Published published = reports.publish(publication);
            Binding persisted = results.bind(caller, token, binding, publication.owner(), publication.capability());
            return new PublicationResult(new ReportRef(published.key().reportId(), published.key().revision()), persisted);
        });
    }

    private static void validateDeliveryStatus(CampaignReportApplicationService.PublishRequest publication,
                                               BindingDraft binding) {
        GoalAssessor.Result assessment = new GoalAssessor().assess(new GoalAssessor.Input(
                publication.publisherRequest().plan(), publication.publisherRequest().planningAssessment(),
                publication.publisherRequest().observations(), publication.publisherRequest().draft()));
        boolean complete = !assessment.goals().isEmpty()
                && assessment.goals().stream().allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED);
        if (binding.reportRef() != null && (binding.executionStatus() == ExecutionStatus.SUCCEEDED) != complete)
            throw new IllegalStateException("RUN_RESULT_REPORT_STATUS_MISMATCH");
    }

    private static void validateIdentity(Caller caller, RunToken token,
                                         CampaignReportApplicationService.PublishRequest publication,
                                         BindingDraft binding) {
        RunDefinition definition = token.definition();
        if (definition == null || !caller.equals(definition.caller()))
            throw new SecurityException("RUN_RESULT_ACCESS_DENIED");
        var plan = publication.publisherRequest().plan();
        var draft = publication.publisherRequest().draft();
        if (!definition.runId().equals(plan.runId()) || !definition.planId().equals(plan.planId())
                || definition.revision() != plan.revision()
                || !definition.runId().equals(draft.runId()) || !definition.planId().equals(draft.planId())
                || definition.revision() != draft.planRevision())
            throw new IllegalStateException("RUN_RESULT_DEFINITION_MISMATCH");
        ReportRef expected = new ReportRef(draft.reportId(), draft.revision());
        if (!expected.equals(binding.reportRef()))
            throw new IllegalStateException("RUN_RESULT_REPORT_REF_MISMATCH");
    }

    public record PublicationResult(ReportRef reportRef, Binding binding) {
        public PublicationResult {
            Objects.requireNonNull(reportRef, "REPORT_PUBLICATION_REQUIRED");
            Objects.requireNonNull(binding, "RUN_RESULT_BINDING_REQUIRED");
        }
    }
}
