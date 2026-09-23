package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.progress.CampaignRunResultProjection.*;
import com.jupiter.shortlink.agent.harness.runtime.AgentRunResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Composes existing publication, assessment and lifecycle contracts for request-bound delivery. */
public final class CampaignReportDeliveryService {
    public static final String CLIENT_CAPABILITY = "campaign-response/v2";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final JdbcReportLifecycleStore lifecycle;
    private final ArtifactAuthorizer artifacts;
    private final RunAccess access;
    private final CampaignArtifactReportRows rows;
    private final CampaignTrustedRunResultAdapter bindings;
    private final CampaignReportNarrativeSynthesizer synthesis;
    private final CampaignSealedReportAccess sealed;

    public CampaignReportDeliveryService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, JdbcReportLifecycleStore lifecycle,
            ArtifactAuthorizer artifacts, RunAccess access, CampaignArtifactReportRows rows) {
        this(jdbc, transactions, clock, runs, steps, lifecycle, artifacts, access, rows, null);
    }
    public CampaignReportDeliveryService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, JdbcReportLifecycleStore lifecycle,
            ArtifactAuthorizer artifacts, RunAccess access, CampaignArtifactReportRows rows,
            CampaignReportNarrativeSynthesizer synthesis) {
        this(jdbc, transactions, clock, runs, steps, lifecycle, artifacts, access, rows, synthesis, null);
    }
    public CampaignReportDeliveryService(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock,
            CampaignRunStore runs, CampaignStepStore steps, JdbcReportLifecycleStore lifecycle,
            ArtifactAuthorizer artifacts, RunAccess access, CampaignArtifactReportRows rows,
            CampaignReportNarrativeSynthesizer synthesis, CampaignSealedReportAccess sealed) {
        this.jdbc = Objects.requireNonNull(jdbc); this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock); this.runs = Objects.requireNonNull(runs);
        this.steps = Objects.requireNonNull(steps); this.lifecycle = Objects.requireNonNull(lifecycle);
        this.artifacts = Objects.requireNonNull(artifacts); this.access = Objects.requireNonNull(access);
        this.rows = Objects.requireNonNull(rows);
        this.bindings = new CampaignTrustedRunResultAdapter(jdbc, lifecycle, transactions, clock);
        this.synthesis = synthesis;
        this.sealed = sealed;
    }

    @FunctionalInterface public interface RunAccess { boolean mayRead(Caller caller, RunDefinition definition); }
    public record Reference(String sessionId, String runId, String planId, int planRevision) {
        public Reference {
            if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()
                    || planId == null || planId.isBlank() || planRevision < 1) throw invalid("REPORT_REFERENCE_INVALID");
        }
        public static Reference of(RunDefinition definition) {
            return new Reference(definition.sessionId(), definition.runId(), definition.planId(), definition.revision());
        }
    }
    public record RowsPage(String schemaVersion, ReportRef reportRef, String runId, String planId, int planRevision,
            String blockId, List<Map<String, Object>> rows, String nextCursor, long totalRows) { }
    public record HistoryItem(String sessionId, String runId, String planId, int planRevision,
            ReportRef reportRef, String title, List<GoalAssessment> goalAssessments) { }
    public record HistoryPage(String schemaVersion, List<HistoryItem> items, String nextCursor) { }
    public record Export(String schemaVersion, CampaignReportView view, String fileName, String content, String format) { }

    /** The only write entry point. All GET/read methods below are independent from this method. */
    public Optional<AgentRunResult.Report> publishCurrent(Caller caller, RunToken token) {
        return publishCurrent(caller, token, null);
    }

    public Optional<AgentRunResult.Report> publishCurrent(Caller caller, RunToken token, ProcessExecutionScope scope) {
        requireCurrent(caller, token);
        List<CampaignStepStore.StepRecord> sourceSteps = steps.steps(token);
        var prepared = new CampaignArtifactReportAssembler(runs, steps, rows,(current,metadata)->
                metadata.revision()==current.definition().revision()?current:sourceToken(caller,current.definition(),metadata))
                .assemble(caller, token, 1, artifacts);
        if (prepared.evidence().isEmpty()) return Optional.empty();
        if (!sourceSteps.equals(steps.steps(token))) throw new IllegalStateException("REPORT_SNAPSHOT_CHANGED");
        // Native model work must never hold a database run/binding/report lock.
        if (synthesis != null && scope != null) prepared = synthesis.synthesize(caller, token, prepared, scope);
        final var assembled = prepared;
        return bindings.withCurrentRunAndBinding(caller, token, owner(caller), capability(token.definition()), () -> {
            requireCurrent(caller, token);
            if (!sourceSteps.equals(steps.steps(token))) throw new IllegalStateException("REPORT_SNAPSHOT_CHANGED");
            for (ArtifactMetadata evidence : assembled.evidence())
                if (!evidence.equals(runs.inspectArtifact(caller, evidence.ref().artifactId(), artifacts)))
                    throw new SecurityException("REPORT_SOURCE_CHANGED");
            FrozenCampaignRun frozen = FrozenCampaignRun.read(token.definition());
            var assessed = new GoalAssessor().assess(new GoalAssessor.Input(frozen.plan(), frozen.assessment(),
                    assembled.observations(), assembled.draft()));
            boolean complete = !assessed.goals().isEmpty() && assessed.goals().stream().allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED);
            boolean runnable = sourceSteps.stream().anyMatch(step -> step.status() != CampaignStepStore.StepStatus.SUCCEEDED
                    && step.status() != CampaignStepStore.StepStatus.FAILED && step.status() != CampaignStepStore.StepStatus.BLOCKED);
            ExecutionStatus state = complete ? ExecutionStatus.SUCCEEDED : runnable ? ExecutionStatus.WAITING : ExecutionStatus.UNKNOWN;
            NextAction action = complete || !runnable ? NextAction.none() : new NextAction(NextActionKind.CONTINUE, "REPORT_PARTIAL", List.of());
            var app = application(caller, token.definition());
            List<Integer> previous = jdbc.query("SELECT revision FROM campaign_report_lifecycle WHERE report_id=? ORDER BY revision DESC LIMIT 1",
                    (rs, n) -> rs.getInt(1), assembled.draft().reportId());
            int revision = previous.isEmpty() ? 1 : Math.addExact(previous.get(0), 1);
            if (!previous.isEmpty()) {
                var snapshot = snapshot(caller, token.definition(), new ReportRef(assembled.draft().reportId(), previous.get(0)),
                        ReportLifecycleStore.Mode.HISTORY_VIEW);
                if (regresses(snapshot.goalAssessments(), assessed.goals()))
                    return Optional.of(report(publicView(snapshot), bindingStatus(token).orElse(ExecutionStatus.UNKNOWN)));
                ReportDraft candidate = revision(assembled.draft(), previous.get(0));
                if (json(candidate).equals(json(snapshot.draft()))
                        && assessed.goals().equals(snapshot.goalAssessments()) && bindingStatus(token).orElse(state) == state)
                    return Optional.of(report(publicView(snapshot), state));
            }
            ReportDraft draft = revision(assembled.draft(), revision);
            Instant retained = assembled.evidence().stream().map(value -> value.ref().expiresAt()).min(Comparator.naturalOrder()).orElseThrow();
            var request = new CampaignReportApplicationService.PublishRequest(new CampaignReportPublisher.PublishRequest(
                    frozen.plan(), frozen.assessment(), assembled.observations(), draft, CLIENT_CAPABILITY),
                    owner(caller), capability(token.definition()), retained, retained);
            var coordinator = new CampaignRunReportPublicationCoordinator(app, bindings, lifecycle, jdbc, transactions);
            coordinator.publishAndBind(caller, token, request, new CampaignRunResultStore.BindingDraft(
                    new ReportRef(draft.reportId(), revision), state, action,
                    assessed.goals().stream().flatMap(goal -> goal.limitations().stream()).distinct().toList()));
            return Optional.of(report(publicView(snapshot(caller, token.definition(), new ReportRef(draft.reportId(), revision),
                    ReportLifecycleStore.Mode.HISTORY_VIEW)), state));
        });
    }

    /** Reads the current durable reference; never drafts, queries statistics, or invokes a model. */
    public Optional<AgentRunResult.Report> latest(Caller caller, RunToken token) {
        requireCurrent(caller, token);
        List<CurrentReport> refs = jdbc.query("SELECT report_id,report_revision,execution_status FROM campaign_run_result_binding WHERE run_id=? AND revision=? AND report_id IS NOT NULL",
                (rs, n) -> new CurrentReport(new ReportRef(rs.getString(1), rs.getInt(2)), ExecutionStatus.valueOf(rs.getString(3))),
                token.definition().runId(), token.definition().revision());
        return refs.stream().findFirst().map(current -> report(read(caller, Reference.of(token.definition()), current.ref()), current.status()));
    }

    public CampaignReportView read(Caller caller, Reference reference, ReportRef reportRef) {
        RunDefinition definition = definition(caller, reference);
        return publicView(snapshot(caller, definition, reportRef, ReportLifecycleStore.Mode.HISTORY_VIEW));
    }

    public RowsPage rows(Caller caller, Reference reference, ReportRef reportRef, String blockId, String cursor, int size) {
        RunDefinition definition = definition(caller, reference);
        var loaded = loadSnapshot(caller, definition, reportRef, ReportLifecycleStore.Mode.HISTORY_VIEW);
        var snapshot = loaded.snapshot();
        CampaignReportView view = publicView(snapshot);
        ReportBlock block = view.blocksById().get(blockId);
        if (block == null || (block.kind() != ReportBlock.Kind.TABLE && block.kind() != ReportBlock.Kind.RESULT_LINK)
                || !(block.payload().get("artifactId") instanceof String id) || !block.evidenceArtifactIds().contains(id))
            throw invalid("REPORT_BLOCK_NOT_PAGEABLE");
        ArtifactMetadata metadata = runs.inspectArtifact(caller, id, loaded.gate());
        String nativeCursor = unwrapCursor(cursor, reportRef, blockId, metadata.ref().artifactId());
        RunToken token = loaded.grant()==null ? requireCurrentDefinition(caller, definition).token() : loaded.grant().tokenFor(metadata);
        var page = rows.read(caller, token, metadata, nativeCursor, size, loaded.gate());
        if (loaded.grant()==null) requireCurrentDefinition(caller, definition); else loaded.grant().revalidate();
        return new RowsPage("campaign-report-rows/v1", reportRef, definition.runId(), definition.planId(), definition.revision(),
                blockId, page.rows(), wrapCursor(page.nextCursor(), reportRef, blockId, metadata.ref().artifactId()), page.totalRows());
    }

    public HistoryPage history(Caller caller, String sessionId, String cursor, int size) {
        if (sessionId == null || sessionId.isBlank() || size < 1 || size > 100) throw invalid("REPORT_HISTORY_INVALID");
        String partition = CampaignRunStore.sha256(owner(caller) + ":" + sessionId);
        JsonNode after = cursor == null ? null : decode(cursor);
        if (after != null && !partition.equals(after.path("partition").asText())) throw invalid("REPORT_CURSOR_INVALID");
        List<Object> args = new ArrayList<>(List.of(caller.tenantId(), caller.subject(), caller.authVersion(), sessionId,clock.millis(),clock.millis()));
        String boundary = "";
        if (after != null) {
            boundary = " AND (r.created_at < ? OR (r.created_at=? AND (r.report_id < ? OR (r.report_id=? AND r.revision < ?))))";
            args.add(after.path("createdAt").asLong()); args.add(after.path("createdAt").asLong());
            args.add(after.path("reportId").asText()); args.add(after.path("reportId").asText()); args.add(after.path("revision").asInt());
        }
        args.add(size + 1);
        var candidates = jdbc.query("SELECT l.session_id,l.run_id,l.plan_id,l.revision,r.report_id,r.revision AS report_revision,r.created_at "
                + "FROM campaign_report_lifecycle r JOIN campaign_run_ledger l ON l.run_id=r.run_id AND l.revision=r.plan_revision "
                + "WHERE l.tenant_id=? AND l.subject_name=? AND l.auth_version=? AND l.session_id=? AND r.retained_until>? AND r.evidence_retained_until>?" + boundary
                + " ORDER BY r.created_at DESC,r.report_id DESC,r.revision DESC LIMIT ?",
                (rs, n) -> new HistoryRow(new Reference(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                        new ReportRef(rs.getString(5), rs.getInt(6)), rs.getLong(7)), args.toArray());
        List<HistoryItem> items = new ArrayList<>();
        for (HistoryRow row : candidates.stream().limit(size).toList()) {
            // Apply current ACL per immutable version, before returning even the title.
            try {
                CampaignReportView view = read(caller, row.reference(), row.reportRef());
                items.add(new HistoryItem(sessionId, view.runId(), view.planId(), view.planRevision(), view.reportRef(),
                        view.modules().isEmpty() ? "投放分析" : view.modules().get(0).title(), view.goalAssessments()));
            } catch (SecurityException denied) { /* revoked results are absent from public history */ }
            catch (IllegalStateException expired) {
                if (!"REPORT_DATA_EXPIRED".equals(expired.getMessage())) throw expired;
            }
        }
        HistoryRow last = candidates.size() > size ? candidates.get(size - 1) : null;
        String next = last == null ? null : encode(Map.of("partition", partition, "createdAt", last.createdAt(),
                "reportId", last.reportRef().reportId(), "revision", last.reportRef().revision()));
        return new HistoryPage("campaign-report-history/v1", List.copyOf(items), next);
    }

    public Export export(Caller caller, Reference reference, ReportRef reportRef) {
        RunDefinition definition = definition(caller, reference);
        CampaignReportView view = publicView(snapshot(caller, definition, reportRef, ReportLifecycleStore.Mode.EXPORT));
        StringBuilder text = new StringBuilder("# 投放分析报告\n\n");
        text.append("报告：").append(reportRef.reportId()).append(" / v").append(reportRef.revision()).append("\n\n");
        for (var module : view.modules()) {
            text.append("## ").append(module.title()).append("\n\n状态：").append(module.status()).append("\n\n");
            for (String blockId : module.blockIds()) {
                ReportBlock block = view.blocksById().get(blockId);
                text.append("### ").append(block.title()).append("\n\n");
                if (block.text() != null) text.append(block.text()).append("\n\n");
                if (block.kind() == ReportBlock.Kind.TABLE) {
                    text.append("报告表格预览；完整结果共 ").append(block.payload().get("totalRows")).append(" 行。\n\n")
                            .append(json(block.payload().get("rows"))).append("\n\n")
                            .append("完整明细引用：").append(reportRef.reportId()).append(" / v").append(reportRef.revision())
                            .append(" / ").append(blockId).append("（按此固定报告版本分页读取）。\n\n");
                } else if (block.kind() == ReportBlock.Kind.METRIC || block.kind() == ReportBlock.Kind.CHART)
                    text.append(json(block.payload())).append("\n\n");
            }
        }
        // Export the entire immutable report, not an unbounded in-memory copy of raw result pages.
        snapshot(caller, definition, reportRef, ReportLifecycleStore.Mode.EXPORT);
        return new Export("campaign-report-export/v1", view, reportRef.reportId() + "-v" + reportRef.revision() + ".md", text.toString(), "markdown");
    }

    private CampaignReportApplicationService application(Caller caller, RunDefinition definition) {
        CampaignReportPublisher publisher = new CampaignReportPublisher(new GoalAssessor(), id -> {
            ArtifactMetadata metadata = runs.inspectArtifact(caller, id, artifacts);
            requireArtifact(definition, metadata);
            return Optional.of(new CampaignReportPublisher.Evidence(id, metadata.ref().type(), metadata.ref().schemaVersion(),
                    metadata.ref().payloadHash(), metadata.ref().expiresAt(), "campaign-report-rows/v1", true));
        }, clock, CLIENT_CAPABILITY, lifecycle);
        return new CampaignReportApplicationService(publisher, (suppliedOwner, suppliedCapability, operation) ->
                owner(caller).equals(suppliedOwner) && capability(definition).equals(suppliedCapability)
                        && caller.equals(definition.caller()) && access.mayRead(caller, definition));
    }

    private CampaignReportReadProjection.Snapshot snapshot(Caller caller, RunDefinition definition, ReportRef ref, ReportLifecycleStore.Mode mode) {
        return loadSnapshot(caller,definition,ref,mode).snapshot();
    }
    private record LoadedSnapshot(CampaignReportReadProjection.Snapshot snapshot,ArtifactAuthorizer gate,CampaignSealedReportAccess.Grant grant) {}
    private LoadedSnapshot loadSnapshot(Caller caller, RunDefinition definition, ReportRef ref, ReportLifecycleStore.Mode mode) {
        var current=runs.loadRun(caller,definition.runId()).orElseThrow(()->new SecurityException("REPORT_ACCESS_DENIED"));
        boolean historical=current.status()!=RunStatus.ACTIVE || !current.definition().equals(definition);
        if (!historical || sealed==null) requireCurrentDefinition(caller, definition); else sealed.token(caller,Reference.of(definition));
        var app = application(caller, definition);
        var stored = app.read(new CampaignReportApplicationService.ReadRequest(ref.reportId(), ref.revision(), owner(caller), capability(definition), mode))
                .orElseThrow(() -> invalid("REPORT_NOT_FOUND"));
        var snapshot = new CampaignReportReadProjection(app).projectAuthorized(stored, mode);
        CampaignSealedReportAccess.Grant grant=historical&&sealed!=null?sealed.open(caller,definition,stored):null;
        ArtifactAuthorizer gate=grant==null?artifacts:grant;
        if (!definition.runId().equals(snapshot.runId()) || !definition.planId().equals(snapshot.draft().planId())
                || definition.revision() != snapshot.planRevision()) throw new SecurityException("REPORT_REFERENCE_MISMATCH");
        JsonNode manifest = CampaignArtifactReportRows.tree(stored.manifestJson());
        if (!CampaignRunStore.sha256(stored.manifestJson()).equals(stored.manifestChecksum())
                || !"campaign-evidence-manifest/v1".equals(manifest.path("schemaVersion").asText())
                || !ref.reportId().equals(manifest.path("reportId").asText()) || ref.revision() != manifest.path("revision").asInt()
                || !manifest.path("entries").isArray() || manifest.path("entries").isEmpty())
            throw new SecurityException("REPORT_MANIFEST_INVALID");
        Set<String> verifiedRefs = new HashSet<>();
        for (JsonNode entry : manifest.path("entries")) {
            if (!verifiedRefs.add(entry.path("artifactId").asText())) throw new SecurityException("REPORT_MANIFEST_INVALID");
            ArtifactMetadata metadata = runs.inspectArtifact(caller, entry.path("artifactId").asText(), gate);
            if (grant==null) requireArtifact(definition, metadata);
            if (!metadata.ref().payloadHash().equals(entry.path("checksum").asText())
                    || !metadata.ref().type().equals(entry.path("type").asText())
                    || !metadata.ref().schemaVersion().equals(entry.path("schemaVersion").asText())
                    || !metadata.ref().expiresAt().equals(Instant.parse(entry.path("retainedUntil").asText())))
                throw new SecurityException("REPORT_SOURCE_CHANGED");
        }
        if (!verifiedRefs.containsAll(snapshot.draft().evidenceArtifactIds()) || snapshot.goalAssessments().stream()
                .anyMatch(goal -> !verifiedRefs.containsAll(goal.evidenceArtifactIds()))) throw new SecurityException("REPORT_MANIFEST_INVALID");
        if (grant==null) requireCurrentDefinition(caller, definition); else grant.revalidate();
        return new LoadedSnapshot(snapshot,gate,grant);
    }

    private CampaignReportView publicView(CampaignReportReadProjection.Snapshot snapshot) {
        CampaignReportView view = CampaignReportView.from(snapshot);
        Map<String, ReportBlock> blocks = new LinkedHashMap<>();
        view.blocksById().forEach((id, block) -> {
            if (block.kind() == ReportBlock.Kind.TABLE && block.payload().get("nextCursor") instanceof String cursor) {
                Map<String, Object> payload = new LinkedHashMap<>(block.payload());
                // The report's exact source hash is verified on reads; bind preview cursors to artifact identity too.
                payload.put("nextCursor", wrapCursor(cursor, view.reportRef(), id, String.valueOf(payload.get("artifactId"))));
                block = new ReportBlock(block.blockId(), block.kind(), block.title(), block.text(), payload, block.evidenceArtifactIds(), block.completeResult());
            }
            blocks.put(id, block);
        });
        return new CampaignReportView(view.schemaVersion(), view.reportRef(), view.runId(), view.planId(), view.planRevision(),
                view.modules(), blocks, view.goalAssessments(), view.limitations());
    }

    private Optional<ExecutionStatus> bindingStatus(RunToken token) {
        return jdbc.query("SELECT execution_status FROM campaign_run_result_binding WHERE run_id=? AND revision=?",
                (rs, n) -> ExecutionStatus.valueOf(rs.getString(1)), token.definition().runId(), token.definition().revision()).stream().findFirst();
    }
    private static boolean regresses(List<GoalAssessment> previous, List<GoalAssessment> candidate) {
        Map<String, RequirementAssessment.Verdict> current = new HashMap<>();
        candidate.forEach(goal -> goal.requirements().forEach(requirement -> current.put(requirement.requirementId(), requirement.verdict())));
        return previous.stream().flatMap(goal -> goal.requirements().stream()).anyMatch(requirement ->
                (requirement.verdict() == RequirementAssessment.Verdict.MET || requirement.verdict() == RequirementAssessment.Verdict.NOT_APPLICABLE)
                        && current.get(requirement.requirementId()) != RequirementAssessment.Verdict.MET
                        && current.get(requirement.requirementId()) != RequirementAssessment.Verdict.NOT_APPLICABLE);
    }
    private static AgentRunResult.Report report(CampaignReportView view, ExecutionStatus state) {
        int answered = (int) view.goalAssessments().stream().filter(goal -> goal.status() == GoalAssessment.Status.ANSWERED).count();
        int partial = (int) view.goalAssessments().stream().filter(goal -> goal.status() == GoalAssessment.Status.PARTIAL).count();
        boolean complete = answered == view.goalAssessments().size() && answered > 0;
        String answer = view.modules().stream().map(module -> module.title() + "：" + module.status() + "\n"
                + module.blockIds().stream().map(view.blocksById()::get).map(ReportBlock::text).filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("\n\n"))).collect(java.util.stream.Collectors.joining("\n\n"));
        return new AgentRunResult.Report(CampaignLegacyAnswerAdapter.SCHEMA,
                complete ? CampaignLegacyAnswerAdapter.Availability.COMPLETE : CampaignLegacyAnswerAdapter.Availability.PARTIAL,
                CampaignLegacyAnswerAdapter.ExecutionStatus.valueOf(state.name()),
                answer.isBlank() ? "报告已生成。" : answer, view.reportRef(), List.copyOf(view.blocksById().values()), view.goalAssessments(),
                new CampaignLegacyAnswerAdapter.GoalRollup(view.goalAssessments().size(), answered, partial, view.goalAssessments().size() - answered - partial),
                view.limitations(), null, view);
    }
    private RunDefinition definition(Caller caller, Reference reference) {
        if (sealed!=null) return sealed.token(caller,reference).definition();
        RunRecord run = runs.loadRun(caller, reference.runId()).orElseThrow(() -> new SecurityException("REPORT_ACCESS_DENIED"));
        if (!reference.equals(Reference.of(run.definition()))) throw new SecurityException("REPORT_REFERENCE_MISMATCH");
        requireCurrentDefinition(caller, run.definition());
        return run.definition();
    }
    private RunRecord requireCurrentDefinition(Caller caller, RunDefinition definition) {
        RunRecord current = runs.loadRun(caller, definition.runId()).orElseThrow(() -> new SecurityException("REPORT_ACCESS_DENIED"));
        if (!caller.equals(definition.caller()) || !definition.equals(current.definition()) || current.status() != RunStatus.ACTIVE
                || !access.mayRead(caller, definition)) throw new SecurityException("REPORT_ACCESS_DENIED");
        return current;
    }
    private void requireCurrent(Caller caller, RunToken token) {
        if (!token.equals(requireCurrentDefinition(caller, token.definition()).token())) throw new SecurityException("REPORT_RUN_CHANGED");
    }
    private static void requireArtifact(RunDefinition definition, ArtifactMetadata metadata) {
        if (!definition.caller().equals(metadata.owner()) || !definition.runId().equals(metadata.runId())
                || !definition.planId().equals(metadata.planId()) || metadata.revision()<1 || definition.revision() < metadata.revision())
            throw new SecurityException("REPORT_SOURCE_CHANGED");
    }
    private RunToken sourceToken(Caller caller,RunDefinition current,ArtifactMetadata metadata) {
        requireArtifact(current,metadata);
        if (sealed==null) throw new SecurityException("REPORT_SOURCE_CHANGED");
        return sealed.token(caller,new Reference(current.sessionId(),current.runId(),current.planId(),metadata.revision()));
    }
    public static String owner(Caller caller) { return CampaignRunStore.sha256(caller.tenantId() + ":" + caller.subject() + ":" + caller.authVersion()); }
    public static String capability(RunDefinition definition) { return "report:" + CampaignRunStore.sha256(definition.runId() + ":" + definition.definitionHash()); }
    private static ReportDraft revision(ReportDraft draft, int revision) {
        return new ReportDraft(draft.reportId(), revision, draft.runId(), draft.planId(), draft.planRevision(), draft.sections(), draft.resultEntries());
    }
    private static String wrapCursor(String cursor, ReportRef ref, String blockId, String source) {
        return cursor == null ? null : encode(Map.of("reportId", ref.reportId(), "revision", ref.revision(), "blockId", blockId, "source", source, "cursor", cursor));
    }
    private static String unwrapCursor(String cursor, ReportRef ref, String blockId, String source) {
        if (cursor == null) return null;
        JsonNode value = decode(cursor);
        if (!ref.reportId().equals(value.path("reportId").asText()) || ref.revision() != value.path("revision").asInt()
                || !blockId.equals(value.path("blockId").asText()) || !source.equals(value.path("source").asText())
                || !value.path("cursor").isTextual()) throw invalid("REPORT_CURSOR_INVALID");
        return value.path("cursor").textValue();
    }
    private static String encode(Object value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(json(value).getBytes(StandardCharsets.UTF_8)); }
    private static JsonNode decode(String value) {
        try { return JSON.readTree(Base64.getUrlDecoder().decode(value)); }
        catch (Exception invalid) { throw invalid("REPORT_CURSOR_INVALID"); }
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception invalid) { throw invalid("REPORT_ENCODING_INVALID"); }
    }
    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
    private record HistoryRow(Reference reference, ReportRef reportRef, long createdAt) { }
    private record CurrentReport(ReportRef ref, ExecutionStatus status) { }
}
