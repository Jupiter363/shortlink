package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanningAssessment;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds a reproducible report from actual successful Step outputs, preserving every goal. */
public final class CampaignArtifactReportAssembler {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final CampaignRunStore runs;
    private final CampaignStepStore steps;
    private final CampaignArtifactReportRows rows;
    private final java.util.function.BiFunction<RunToken,ArtifactMetadata,RunToken> sources;
    public CampaignArtifactReportAssembler(CampaignRunStore runs, CampaignStepStore steps, CampaignArtifactReportRows rows) {
        this(runs,steps,rows,(token,metadata)->token);
    }
    public CampaignArtifactReportAssembler(CampaignRunStore runs, CampaignStepStore steps, CampaignArtifactReportRows rows,
            java.util.function.BiFunction<RunToken,ArtifactMetadata,RunToken> sources) {
        this.runs = Objects.requireNonNull(runs); this.steps = Objects.requireNonNull(steps); this.rows = Objects.requireNonNull(rows);
        this.sources=Objects.requireNonNull(sources);
    }
    public record Assembled(ReportDraft draft, Map<String, GoalAssessor.RequirementObservation> observations,
            List<ArtifactMetadata> evidence) {
        public Assembled { observations = Map.copyOf(observations); evidence = List.copyOf(evidence); }
    }

    public Assembled assemble(Caller caller, RunToken token, int revision, ArtifactAuthorizer authorizer) {
        FrozenCampaignRun frozen = FrozenCampaignRun.read(token.definition());
        Map<String, CampaignStepStore.StepRecord> saved = new LinkedHashMap<>();
        steps.steps(token).forEach(step -> saved.put(step.spec().stepId(), step));
        Map<String, Artifact> loaded = new LinkedHashMap<>();
        Map<String, List<ReportBlock>> rendered = new LinkedHashMap<>();
        Map<String, GoalAssessor.RequirementObservation> observations = new LinkedHashMap<>();
        List<ReportSection> sections = new ArrayList<>();
        for (var goal : frozen.plan().goals()) {
            List<ReportBlock> blocks = new ArrayList<>();
            LinkedHashSet<String> goalEvidence = new LinkedHashSet<>();
            for (var requirement : frozen.assessment().requirements()) {
                if (!goal.goalId().equals(requirement.goalId())) continue;
                List<PlanningAssessment.EvidenceOutput> sources = frozen.assessment().coverageBindings().stream()
                        .filter(binding -> requirement.requirementId().equals(binding.requirementId()))
                        .flatMap(binding -> binding.evidenceOutputs().stream()).toList();
                List<String> refs = new ArrayList<>();
                boolean complete = !sources.isEmpty();
                for (var source : sources) {
                    var step = saved.get(source.stepId());
                    String artifactId = step == null || step.status() != CampaignStepStore.StepStatus.SUCCEEDED
                            ? null : step.outputs().get(source.output());
                    if (artifactId == null) { complete = false; continue; }
                    Artifact artifact = loaded.computeIfAbsent(artifactId, id -> runs.readArtifact(caller, id, authorizer));
                    if (!caller.equals(artifact.metadata().owner()) || !token.definition().runId().equals(artifact.metadata().runId())
                            || !token.definition().planId().equals(artifact.metadata().planId())
                            || token.definition().revision() < artifact.metadata().revision()) throw new SecurityException("REPORT_SOURCE_CHANGED");
                    RunToken producer=this.sources.apply(token,artifact.metadata());
                    if (producer.definition().revision()!=artifact.metadata().revision()) throw new SecurityException("REPORT_SOURCE_CHANGED");
                    refs.add(artifactId); goalEvidence.add(artifactId);
                    rendered.computeIfAbsent(artifactId, id -> render(caller, producer, artifact, authorizer));
                    complete &= rendered.get(artifactId).stream().anyMatch(ReportBlock::isDeliverable);
                    complete &= matchesRequirement(requirement, producer, artifact);
                }
                // Execution and observational tables cannot establish causal requirements.
                boolean observed = complete && requirement.kind() != PlanningAssessment.RequirementKind.CAUSAL_EVIDENCE
                        && !requirement.criterionRef().startsWith("analysis-interpretation")
                        && !requirement.criterionRef().startsWith("analysis-recommendation");
                observations.put(requirement.requirementId(), new GoalAssessor.RequirementObservation(
                        observed ? RequirementAssessment.Verdict.MET : RequirementAssessment.Verdict.UNKNOWN,
                        observed ? null : "EVIDENCE_NOT_ASSESSED", refs,
                        observed ? List.of() : List.of("该目标的所需证据尚未全部交付。")));
            }
            goalEvidence.forEach(id -> blocks.addAll(rendered.get(id)));
            if (blocks.isEmpty()) blocks.add(new ReportBlock("pending-" + goal.goalId(), ReportBlock.Kind.LIMITATION,
                    "分析进度", "该目标暂未形成可交付证据；保留原分析目标，等待执行完成或补充必要条件。",
                    Map.of(), List.of(), false));
            sections.add(new ReportSection("goal-" + goal.goalId(), sections.size(), goal.question(), List.of(goal.goalId()), blocks));
        }
        String reportId = "report-" + CampaignRunStore.sha256(token.definition().runId() + ":" + token.definition().revision());
        return new Assembled(new ReportDraft(reportId, revision, token.definition().runId(), token.definition().planId(),
                token.definition().revision(), sections, List.of()), observations,
                loaded.values().stream().map(Artifact::metadata).toList());
    }

    private boolean matchesRequirement(PlanningAssessment.Requirement requirement, RunToken token, Artifact artifact) {
        String type = artifact.metadata().ref().type();
        if ("statistics-evidence-delivery".equals(requirement.criterionRef()) && !"StatisticsJobPages".equals(type)) return false;
        if ("selected-entities-delivery".equals(requirement.criterionRef())
                && !List.of("SelectedEntitiesArtifact", "DeclineEvidenceArtifact").contains(type)) return false;
        if ("dimension-change-delivery".equals(requirement.criterionRef()) && !"DimensionChangeArtifact".equals(type)) return false;
        if (!"statistics-query-evidence".equals(requirement.criterionRef())) return true;
        if (!"StatisticsJobPages".equals(type)) return false;
        ChildRecord child = runs.child(token, artifact.metadata().childId()).orElse(null);
        if (child == null || child.spec().wire() == null) return false;
        JsonNode wire = CampaignArtifactReportRows.tree(child.spec().wire().bodyJson());
        if (!wire.isObject()) return false;
        @SuppressWarnings("unchecked") Map<String, Object> request = JSON.convertValue(wire, java.util.TreeMap.class);
        request.remove("requestId");
        try { return Objects.equals(requirement.parameters().get("queryHash"), CampaignRunStore.sha256(JSON.writeValueAsString(request))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return false; }
    }

    private List<ReportBlock> render(Caller caller, RunToken token, Artifact artifact, ArtifactAuthorizer authorizer) {
        ArtifactMetadata metadata = artifact.metadata();
        String prefix = "block-" + CampaignRunStore.sha256(metadata.ref().artifactId()).substring(0, 24);
        List<String> evidence = List.of(metadata.ref().artifactId());
        JsonNode body = CampaignArtifactReportRows.tree(artifact.payloadJson());
        List<ReportBlock> blocks = new ArrayList<>();
        List<Map<String, Object>> metrics = new ArrayList<>();
        String type = metadata.ref().type();
        String displayTitle = title(type);
        String queryContext = "";
        if ("StatisticsJobPages".equals(type)) {
            ChildRecord child = runs.child(token, metadata.childId()).orElseThrow(() -> new SecurityException("REPORT_SOURCE_CHANGED"));
            JsonNode query = CampaignArtifactReportRows.tree(child.spec().wire().bodyJson());
            String period = query.path("startDate").asText() + " 至 " + query.path("endDate").asText();
            displayTitle += " · " + period;
            queryContext = "统计对象：" + (query.hasNonNull("fullShortUrl") ? query.path("fullShortUrl").asText()
                    : "分组 " + query.path("gid").asText()) + "；期间：" + period + "。";
            JsonNode requested = body.path("metrics").path("requested");
            for (String key : List.of("pv", "uv", "uip")) if (requested.path(key).isNumber())
                metrics.add(metric(key.toUpperCase(), requested.get(key).numberValue(), "次"));
        } else {
            Map<String, String> names = Map.of("candidateCount", "分析对象", "comparedCount", "已比较对象", "selectedCount", "下降对象",
                    "memberCount", "已选对象", "comparisonRows", "维度观察", "coveredCohorts", "覆盖分片");
            for (String key : List.of("candidateCount", "comparedCount", "selectedCount", "memberCount", "comparisonRows", "coveredCohorts"))
                if (body.path(key).isNumber()) metrics.add(metric(names.get(key), body.get(key).numberValue(), "项"));
        }
        if (!metrics.isEmpty()) blocks.add(new ReportBlock(prefix + "-metrics", ReportBlock.Kind.METRIC, displayTitle, null,
                Map.of("items", metrics), evidence, false));
        CampaignArtifactReportRows.Page preview = rows.supports(type) ? rows.read(caller, token, metadata, null, 12, authorizer) : null;
        if (preview != null) {
            boolean complete = "StatisticsJobPages".equals(type) ? body.path("resultComplete").asBoolean()
                    : body.path("selectionComplete").asBoolean()
                        && !"INSUFFICIENT_EVIDENCE".equals(body.path("evidenceDisposition").asText());
            List<Map<String, Object>> chartRows = preview.rows().stream()
                    .filter(row -> row.get("baseline") instanceof Number && row.get("target") instanceof Number).toList();
            if (!chartRows.isEmpty()) blocks.add(new ReportBlock(prefix + "-chart", ReportBlock.Kind.CHART,
                    "已展示对象的期间变化", null, Map.of("chartType", "BAR", "unit", "次",
                            "labels", chartRows.stream().map(row -> String.valueOf(row.get("linkId"))).toList(),
                            "series", List.of(Map.of("name", "基期", "values", chartRows.stream().map(row -> row.get("baseline")).toList()),
                                    Map.of("name", "目标期", "values", chartRows.stream().map(row -> row.get("target")).toList()))), evidence, false));
            if (chartRows.isEmpty()) {
                List<Map<String, Object>> values = preview.rows().stream().filter(row -> row.get("pv") instanceof Number
                        && (row.containsKey("linkId") || row.get("dimensions") instanceof Map<?, ?>)).toList();
                if (!values.isEmpty()) blocks.add(new ReportBlock(prefix + "-chart", ReportBlock.Kind.CHART,
                        "当前结果的访问量预览", null, Map.of("chartType", "BAR", "unit", "次",
                        "labels", values.stream().map(row -> row.containsKey("linkId") ? String.valueOf(row.get("linkId"))
                                : String.valueOf(row.get("dimensions"))).toList(),
                        "series", List.of(Map.of("name", "PV", "values", values.stream().map(row -> row.get("pv")).toList()))), evidence, false));
            }
            String explanation = queryContext + explanation(type, body, preview);
            blocks.add(new ReportBlock(prefix + "-analysis", ReportBlock.Kind.ANALYSIS, "数据解读", explanation,
                    Map.of("claimType", "OBSERVED_ONLY"), evidence, false));
            List<Map<String, Object>> columns = preview.rows().isEmpty() ? List.of()
                    : preview.rows().get(0).keySet().stream().filter(key -> !"evidenceRefs".equals(key))
                            .map(key -> Map.<String, Object>of("key", key, "label", label(key))).toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactId", metadata.ref().artifactId()); payload.put("columns", columns);
            payload.put("rows", preview.rows()); payload.put("nextCursor", preview.nextCursor()); payload.put("totalRows", preview.totalRows());
            blocks.add(new ReportBlock(prefix + "-table", ReportBlock.Kind.TABLE, displayTitle + "明细", null, payload,
                    evidence, complete && preview.nextCursor() == null));
            if (complete) blocks.add(new ReportBlock(prefix + "-result", ReportBlock.Kind.RESULT_LINK, "完整结果", "按本报告版本查看全部明细。",
                    Map.of("artifactId", metadata.ref().artifactId(), "label", "查看完整结果", "rowCount", preview.totalRows()), evidence, true));
            blocks.add(new ReportBlock(prefix + "-limits", ReportBlock.Kind.LIMITATION, "统计口径与限制",
                    "以上为已返回证据的观察结果；数据变化和维度分布不能单独证明原因。预览仅展示部分行，完整结果保留原始比较状态、数据质量和来源引用。",
                    Map.of(), evidence, false));
        } else blocks.add(new ReportBlock(prefix + "-unsupported", ReportBlock.Kind.LIMITATION, "证据说明",
                "已产生 " + type + " 证据，但尚无对应的公开结果读取合同，不能将该目标标为完整交付。", Map.of(), evidence, false));
        return List.copyOf(blocks);
    }
    private static Map<String, Object> metric(String label, Number value, String unit) { return Map.of("label", label, "value", value, "unit", unit); }
    private static String explanation(String type, JsonNode body, CampaignArtifactReportRows.Page page) {
        return switch (type) {
            case "SelectedEntitiesArtifact" -> "本次在 " + body.path("candidateCount").asLong() + " 个冻结对象中，返回 "
                    + page.totalRows() + " 个满足可比较条件且指标下降的对象。仅这些对象进入后续维度分析；未入选不等于没有访问变化。"
                    + (body.path("selectionComplete").asBoolean() ? "筛选结果已齐全。" : "筛选覆盖仍存在缺口，不能外推到全部对象。");
            case "DeclineEvidenceArtifact" -> "已保留 " + page.totalRows() + " 条逐对象比较证据。基期、目标期和变化量应与比较状态一起解读；不可比较记录不能据此判断真实增长或下降。";
            case "DimensionChangeArtifact" -> "对筛选出的 " + body.path("memberCount").asLong() + " 个对象形成 " + page.totalRows()
                    + " 条维度观察。统计单元为固定分片群体，省份与设备联合分布的变化只描述现象，不代表独立访客可以跨桶相加或已完成因果归因。";
            default -> "该统计快照包含 " + page.totalRows() + " 条结果。请结合所选对象、期间及数据质量比较；空结果只有在完整查询明确返回零行时才成立。";
        };
    }
    private static String title(String type) { return switch (type) {
        case "SelectedEntitiesArtifact" -> "下降对象"; case "DeclineEvidenceArtifact" -> "对象比较";
        case "DimensionChangeArtifact" -> "维度变化"; case "StatisticsJobPages" -> "访问统计"; default -> "分析证据"; }; }
    private static String label(String key) { return switch (key) {
        case "linkId" -> "短链"; case "baseline" -> "基期"; case "target" -> "目标期"; case "delta" -> "变化量";
        case "relativeChange" -> "变化率"; case "comparability" -> "可比性"; case "reasonCodes" -> "口径说明";
        case "dimensions" -> "维度"; case "pvDelta" -> "PV变化"; case "pvShareChange" -> "PV占比变化";
        case "shardIndex" -> "分片"; case "quality" -> "数据质量"; default -> key; }; }
}
