package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverageTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.DeclineSelectionCallTest.*;
import static com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.NativeDeclineSelectionSkillTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanBinding;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignExplorationCandidateStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignSkillInvocationStore.InvocationRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.StatisticsJobResultReceiver;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;

/** Typed native terminal responses assessed against real Skill evidence; assessments never settle the outer Step. */
@Timeout(60)
class ExplorationCandidateAssessmentTest {
    @Test
    void realNamedSkillEvidenceCompletesOnlyRegisteredCriteriaWhileUnknownQualityRemainsRejectedAcrossReopen() throws Exception {
        for (boolean requireCompleteCollection : List.of(false, true)) {
            List<PlanSpec.CriterionUse> uses = new ArrayList<>();
            uses.add(new PlanSpec.CriterionUse("supported-evidence", Map.of()));
            if (requireCompleteCollection) uses.add(new PlanSpec.CriterionUse("complete-collection", Map.of()));
            var f = new CallFixture(false, uses);
            migrate(f);
            var criteria = criteria(f, requireCompleteCollection);
            var candidates = candidates(f, criteria);
            var model = new SkillModel(f, ExplorationCandidateAssessmentTest::completeCandidate);
            StepPermit current = null;
            boolean firstExited = false;
            try {
                assertEquals("WAITING", nativeAdapter(f, f.step, model, candidates).invoke(PROMPT).get("status"));
                assertTrue(candidates.assessment(f.token, STEP).isEmpty());
                assertEquals(1, model.calls.get()); assertEquals(2, f.gateway.submits);
                var call = onlyCall(f, f.token);
                var waiting = f.invocations.invocation(f.token, call.spec().callId()).orElseThrow();
                f.steps.settle(f.step, StepStatus.WAITING, Map.of(), "awaiting-skill", f.auth);
                f.steps.callbackExited(f.step); firstExited = true;
                RunToken writer = f.steps.acquireRun(f.token);
                var delegate = f.adapter();
                var targets = delegate.resultTargets(writer);
                var receiver = new StatisticsJobResultReceiver(f.runs, f.base.results, f.gateway, CLOCK, 1);
                for (var entry : targets.entrySet()) {
                    var received = receiver.receive(writer, entry.getKey(), PRINCIPAL, entry.getValue(),
                            () -> delegate.reauthorize(writer, entry.getKey()));
                    assertEquals(StatisticsJobResultReceiver.Outcome.READY, received.outcome(), received.code());
                }
                assertEquals(StepStatus.READY, f.steps.refreshWaiting(writer, STEP).status());
                current = f.steps.beginStep(writer, STEP);
                var callback = delegate.beginContinuation(current, call.spec().callId(), waiting.rowVersion());
                InvocationRecord completion;
                try { completion = delegate.execute(callback); }
                finally { f.calls.callbackExited(callback); }
                model.call = call; model.completion = completion;
                var result = nativeAdapter(f, current, model, candidates).invoke(PROMPT);
                assertEquals(requireCompleteCollection ? "BLOCKED" : "CANDIDATE", result.get("status"));
                if (requireCompleteCollection) assertEquals("EXPLORATION_CANDIDATE_REJECTED", result.get("reason"));
                var assessment = candidates.assessment(writer, STEP).orElseThrow();
                assertEquals(requireCompleteCollection ? Verdict.REJECTED : Verdict.COMPLETE, assessment.verdict());
                assertEquals(requireCompleteCollection ? 2 : 1, assessment.criteria().size());
                var coverage = assessment.criteria().stream().filter(c -> c.criterionRef().equals("supported-evidence")).findFirst().orElseThrow();
                assertEquals(CompletionCriterionRegistry.State.MET, coverage.state());
                assertTrue(coverage.reasonCodes().contains("DECLINE_SELECTION_COVERED"));
                assertEquals(OUTPUTS.size(), new HashSet<>(coverage.evidenceArtifactIds()).size());
                if (requireCompleteCollection) {
                    var quality = assessment.criteria().stream().filter(c -> c.criterionRef().equals("complete-collection")).findFirst().orElseThrow();
                    assertEquals(CompletionCriterionRegistry.State.UNKNOWN, quality.state());
                    assertTrue(quality.reasonCodes().contains("COLLECTION_COMPLETENESS_UNKNOWN"));
                } else assertEquals(completion.outputs(), assessment.outputs());
                assertEquals(StepStatus.RUNNING, f.steps.step(writer, STEP).orElseThrow().status());
                assertTrue(f.steps.step(writer, STEP).orElseThrow().outputs().isEmpty(), "Candidate acceptance is not outer Step publication");
                ChildRecord source = f.children(writer).stream().filter(c -> c.spec().mode() == ChildMode.MODEL
                        && c.spec().modelInvocation().turnIndex() == 2).findFirst().orElseThrow();
                assertEquals(source.spec().childId(), assessment.modelChildId());
                assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
                assertEquals(1, f.count("campaign_exploration_candidate", RUN));
                assertEquals(1, writer.definition().revision());

                // Reopen the exact source/registry, including a rejected candidate: no hidden retry or quality upgrade.
                var reopened = candidates(f, criteria);
                assertEquals(assessment, reopened.assessment(writer, STEP).orElseThrow());
                assertEquals(result.get("status"), nativeAdapter(f, current, model, reopened).invoke(PROMPT).get("status"));
                assertEquals(assessment, reopened.assessment(writer, STEP).orElseThrow());
                assertEquals(source, f.runs.child(writer, source.spec().childId()).orElseThrow());
                assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
                assertEquals("CANDIDATE_RUN_FENCED", assertThrows(SecurityException.class,
                        () -> reopened.assess(f.step, source.spec().childId()), "Old Step/writer cannot assess a new terminal turn").getMessage());

                f.allowed.set(false);
                assertThrows(SecurityException.class, () -> reopened.assessment(writer, STEP));
                assertEquals(2, model.calls.get()); assertEquals(2, f.gateway.submits); assertEquals(2, f.gateway.pageReads);
                f.allowed.set(true);
                assertEquals(assessment, candidates(f, criteria).assessment(writer, STEP).orElseThrow());
                assertTrue(f.steps.step(writer, STEP).orElseThrow().outputs().isEmpty());
            } finally {
                f.allowed.set(true);
                if (current != null) f.steps.callbackExited(current);
                if (!firstExited) f.steps.callbackExited(f.step);
            }
            exited(f);
        }
    }

    @Test
    void terminalRequestsAndMalformedCandidateAreDurableWithoutExecutingCapabilitiesOrRevisingThePlan() throws Exception {
        var cases = new LinkedHashMap<String, Verdict>();
        cases.put(new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.NEEDS_INPUT,
                "A period definition is missing.", List.of(), null, List.of("comparison-period"), null, null).encode(), Verdict.NEEDS_INPUT);
        cases.put(new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.REQUEST_REPLAN,
                "The requested explanation needs another authorized evidence source.", List.of(), null, null,
                "Add an explicitly authorized source for the explanation.", null).encode(), Verdict.REPLAN_REQUESTED);
        String noProgress = new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.NO_PROGRESS,
                "No supported next action is available.", List.of(), null, null, null, "NO_SUPPORTED_ACTION").encode();
        cases.put(noProgress, Verdict.NO_PROGRESS_REPORTED);
        cases.put(noProgress.substring(0, noProgress.length() - 1) + ",\"unregisteredChecker\":true}", Verdict.REJECTED);
        for (var entry : cases.entrySet()) {
            var f = new CallFixture(false);
            migrate(f);
            var registry = criteria(f, false);
            var candidates = candidates(f, registry);
            var model = new TerminalModel(entry.getKey());
            var definition = f.token.definition();
            try {
                var result = nativeAdapter(f, f.step, model, candidates).invoke(PROMPT);
                assertEquals("BLOCKED", result.get("status"));
                assertEquals("EXPLORATION_" + switch (entry.getValue()) {
                    case NEEDS_INPUT -> "NEEDS_INPUT";
                    case REPLAN_REQUESTED -> "REPLAN_REQUESTED";
                    case NO_PROGRESS_REPORTED -> "NO_PROGRESS_REPORTED";
                    default -> "CANDIDATE_REJECTED";
                }, result.get("reason"));
                var saved = candidates.assessment(f.token, STEP).orElseThrow();
                assertEquals(entry.getValue(), saved.verdict());
                assertTrue(saved.outputs().isEmpty()); assertTrue(saved.criteria().isEmpty());
                assertFalse(saved.assessmentId().isBlank());
                assertEquals(1, model.calls.get()); assertEquals(1, f.count("campaign_model_response", RUN));
                assertEquals(0, f.count("campaign_exploration_call", RUN));
                assertEquals(0, f.gateway.submits + f.gateway.reads() + f.gateway.recoveries);
                assertEquals(0, f.finalCount());
                assertEquals(StepStatus.RUNNING, f.steps.step(f.token, STEP).orElseThrow().status());
                assertTrue(f.steps.step(f.token, STEP).orElseThrow().outputs().isEmpty());
                var reopened = candidates(f, registry);
                assertEquals("BLOCKED", nativeAdapter(f, f.step, model, reopened).invoke(PROMPT).get("status"));
                assertEquals(saved, reopened.assessment(f.token, STEP).orElseThrow());
                assertEquals(1, model.calls.get()); assertEquals(1, f.count("campaign_exploration_candidate", RUN));
                assertEquals(definition, f.runs.loadRun(OWNER, RUN).orElseThrow().token().definition());
                assertEquals(1, f.runs.loadRun(OWNER, RUN).orElseThrow().token().definition().revision());
            } finally { f.steps.callbackExited(f.step); }
            exited(f);
        }
    }

    private static CompletionCriterionRegistry criteria(CallFixture f, boolean completeCollection) {
        var registrations = new ArrayList<CompletionCriterionRegistry.Registration>();
        registrations.add(DeclineSelectionCompletionCriteria.coverage("decline-explore", "1", "supported-evidence",
                new JdbcCampaignDeclineSelectionStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs), f.auth));
        if (completeCollection) registrations.add(DeclineSelectionCompletionCriteria.completeCollectionQuality(
                "decline-explore", "1", "complete-collection"));
        return new CompletionCriterionRegistry(registrations);
    }

    private static CampaignExplorationCandidateStore candidates(CallFixture f, CompletionCriterionRegistry criteria) {
        return new JdbcCampaignExplorationCandidateStore(f.base.jdbc, f.base.transactions, CLOCK, f.runs, f.steps,
                f.models, f.catalog, f.contracts, criteria, f.auth);
    }

    private static String completeCandidate(InvocationRecord completion) {
        Map<String, PlanBinding> bindings = new TreeMap<>();
        completion.outputs().forEach((name, ref) -> bindings.put(name, PlanBinding.artifact(ref.artifactId())));
        return new ExplorationCandidate(ExplorationCandidate.SCHEMA_VERSION, ExplorationCandidate.Kind.COMPLETE,
                "The sealed pair covers the frozen candidates; source collection completeness remains unverified.",
                completion.outputs().values().stream().map(ArtifactRef::artifactId).sorted().toList(), bindings, null, null, null).encode();
    }

    private static void migrate(CallFixture f) {
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260920_10__campaign_exploration_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_11__campaign_exploration_budget.sql"),
                new ClassPathResource("sql/migration/V20260920_13__campaign_skill_observation.sql"),
                new ClassPathResource("sql/migration/V20260920_14__campaign_exploration_candidate.sql")).execute(f.base.jdbc.getDataSource());
    }

    private static void exited(CallFixture f) {
        for (String table : List.of("campaign_child_ledger", "campaign_step_ledger", "campaign_exploration_call"))
            assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE callback_active=TRUE", Integer.class));
    }

    private static final class TerminalModel implements ChatModel {
        final String response;
        final AtomicInteger calls = new AtomicInteger();
        TerminalModel(String response) { this.response = response; }
        @Override public ChatResponse call(Prompt prompt) {
            assertEquals(1, calls.incrementAndGet(), "A durable terminal candidate must reuse its original MODEL response");
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        @Override public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("scripted-model").build(); }
    }
}
