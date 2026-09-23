package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.ReplanRequest;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcReplanReceiptStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Resolves only the committed revision chain rooted in the immutable original intake definition. */
public final class CampaignReplanDefinitionResolver {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcReplanReceiptStore receipts;

    public CampaignReplanDefinitionResolver(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.receipts = new JdbcReplanReceiptStore(jdbc, transactions, clock);
    }

    public RunDefinition resolve(RunDefinition initial, RunRecord current) {
        Objects.requireNonNull(initial); Objects.requireNonNull(current);
        if (initial.revision() != 1 || current.status() != RunStatus.ACTIVE
                || !initial.caller().equals(current.definition().caller())
                || !initial.sessionId().equals(current.definition().sessionId())
                || !initial.runId().equals(current.definition().runId())
                || !initial.planId().equals(current.definition().planId())
                || current.definition().revision() < initial.revision()) throw denied();
        return transactions.execute(status -> {
            var root = FrozenCampaignRun.read(initial);
            var next = FrozenCampaignRun.read(current.definition());
            verifyStored(current.definition(), RunStatus.ACTIVE);
            if (!root.inputs().equals(next.inputs())) throw denied();
            for (int revision = current.definition().revision(); revision > 1; revision--) {
                var receipt = receipts.findFinalized(initial.caller(), initial.runId(), revision - 1).orElseThrow(CampaignReplanDefinitionResolver::denied);
                if (!"ACCEPTED".equals(receipt.decision()) || receipt.baseRevision() != revision - 1
                        || receipt.candidateRevision() != revision || !initial.runId().equals(receipt.runId())
                        || !ReplanRequest.planHash(next.plan()).equals(receipt.candidatePlanHash())) throw denied();
                final ReplanRequest request;
                try { request = JSON.readValue(receipt.requestJson(), ReplanRequest.class); }
                catch (Exception invalid) { throw denied(); }
                var baseline = request.baseline();
                if (baseline.plan().revision() != revision - 1 || !request.assess(next.plan(), next.assessment()).accepted()
                        || !root.plan().goals().equals(next.plan().goals())
                        || !root.assessment().requirements().equals(next.assessment().requirements())) throw denied();
                next = FrozenCampaignRun.freeze(baseline.plan(), root.inputs(), baseline.assessment());
                verifyStored(next.definition(initial.caller(), initial.sessionId()), RunStatus.SUPERSEDED);
            }
            if (!initial.equals(next.definition(initial.caller(), initial.sessionId()))) throw denied();
            return current.definition();
        });
    }

    private void verifyStored(RunDefinition expected, RunStatus status) {
        var matches = jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_ledger WHERE run_id=? AND revision=? "
                + "AND tenant_id=? AND subject_name=? AND auth_version=? AND session_id=? AND plan_id=? AND definition_hash=? AND run_status=?",
                Integer.class, expected.runId(), expected.revision(), expected.caller().tenantId(), expected.caller().subject(),
                expected.caller().authVersion(), expected.sessionId(), expected.planId(), expected.definitionHash(), status.name());
        if (matches == null || matches != 1) throw denied();
    }
    private static SecurityException denied() { return new SecurityException("CAMPAIGN_COMMITTED_REVISION_REQUIRED"); }
}
