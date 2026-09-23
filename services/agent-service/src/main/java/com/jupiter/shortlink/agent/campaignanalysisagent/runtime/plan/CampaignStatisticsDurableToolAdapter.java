package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.CapacityRejectedException;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessCapacityExecutor.WorkRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignAdvanceOutcomeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignAdvanceOutcomeStore.Outcome;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore.StepStatus;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.Header;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.JdbcCampaignRunIntakeStore.State;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.tool.shortlink.CampaignStatisticsQueryPlan;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Graph-facing, opt-in adapter. A continuation names a durable Run, never a remote job. */
public class CampaignStatisticsDurableToolAdapter {
    private static final String COMPARE = "compare_statistics", RANK = "rank_short_links";
    private static final Set<String> COMPARE_FIELDS = Set.of("scopes", "periods");
    private static final Set<String> RANK_FIELDS = Set.of("gid", "startDate", "endDate", "metric", "limit");
    private static final Set<String> SCOPE_FIELDS = Set.of("gid", "fullShortUrl", "label");
    private static final Set<String> PERIOD_FIELDS = Set.of("startDate", "endDate", "label");
    private final CampaignStatisticsFixedRuntime runtime;
    private final Clock clock;

    public CampaignStatisticsDurableToolAdapter(CampaignStatisticsFixedRuntime runtime, Clock clock) {
        this.runtime = Objects.requireNonNull(runtime);
        this.clock = Objects.requireNonNull(clock);
    }

    /** requestKey is trusted per-invocation identity for a new Run; a continuation uses its receipt key. */
    public ToolResult execute(String toolName, ToolContext context, String requestKey) {
        try {
            if (!COMPARE.equals(toolName) && !RANK.equals(toolName))
                throw new IllegalArgumentException("STATISTICS_TOOL_UNSUPPORTED");
            if (context == null || context.principal() == null || context.principal().system()
                    || !context.principal().username().equals(context.username()))
                throw new SecurityException("STATISTICS_TRUSTED_CONTEXT_REQUIRED");
            Map<String, Object> arguments = context.arguments();
            boolean continuing = arguments.containsKey("workRef");
            requireFields(arguments, COMPARE.equals(toolName) ? COMPARE_FIELDS : RANK_FIELDS,
                    continuing, "STATISTICS_ARGUMENTS_INVALID");
            Selection selection = COMPARE.equals(toolName) ? comparison(arguments) : ranking(arguments);
            AgentPrincipal current = runtime.principals().bindCurrent(context.principal(), context.sessionId());
            Caller caller = new Caller(current.tenantId(), current.username(), current.authVersion());
            WorkRef work;
            CampaignStatisticsPlanFactory.Prepared prepared;
            Header receipt;
            if (continuing) {
                work = workRef(arguments.get("workRef"));
                receipt = runtime.intake().receipt(current, work);
                requireReceipt(receipt, caller, context.sessionId());
                prepared = prepare(toolName, caller, context.sessionId(), receipt.requestKey(), selection);
                if (!receipt.definitionHash().equals(prepared.definition().definitionHash())
                        || !receipt.runId().equals(prepared.definition().runId())
                        || !receipt.planId().equals(prepared.definition().planId()))
                    throw new SecurityException("STATISTICS_CONTINUATION_CHANGED");
            } else {
                if (requestKey == null || requestKey.isBlank())
                    throw new IllegalArgumentException("STATISTICS_REQUEST_KEY_REQUIRED");
                prepared = prepare(toolName, caller, context.sessionId(), requestKey, selection);
                work = runtime.intake().register(current, context.sessionId(), requestKey,
                        CampaignStatisticsFixedRuntime.PROFILE_REF,
                        CampaignStatisticsFixedRuntime.PROFILE_VERSION, prepared.definition());
                receipt = runtime.intake().receipt(current, work);
                requireReceipt(receipt, caller, context.sessionId());
            }
            if (receipt.state() == State.FROZEN) {
                var snapshot = runtime.steps().snapshot(caller, work.runId());
                if (!prepared.definition().equals(snapshot.run().definition())
                        || snapshot.run().status() != RunStatus.ACTIVE)
                    throw new SecurityException("STATISTICS_RUN_CHANGED");
                Map<String, StepRecord> states = new LinkedHashMap<>();
                for (StepRecord step : snapshot.steps()) {
                    if (states.putIfAbsent(step.spec().stepId(), step) != null)
                        throw new IllegalStateException("STATISTICS_STEPS_CHANGED");
                }
                if (!prepared.queries().stream().map(CampaignStatisticsPlanFactory.StepQuery::stepId)
                        .collect(java.util.stream.Collectors.toSet()).containsAll(states.keySet()))
                    throw new IllegalStateException("STATISTICS_STEPS_CHANGED");
                if (states.size() == prepared.queries().size()
                        && states.values().stream().allMatch(step -> step.status() == StepStatus.SUCCEEDED)) {
                    Map<String, Object> projected;
                    if (COMPARE.equals(toolName)) {
                        var queryPlan = Objects.requireNonNull(selection.queryPlan());
                        Map<String, String> stepIds = new LinkedHashMap<>();
                        for (int index = 0; index < prepared.queries().size(); index++) {
                            var expected = queryPlan.queries().get(index);
                            var actual = prepared.queries().get(index);
                            if (!expected.key().equals(actual.key()))
                                throw new IllegalStateException("STATISTICS_QUERY_ORDER_CHANGED");
                            stepIds.put(expected.key(), actual.stepId());
                        }
                        projected = runtime.projector().compare(caller, snapshot.run().token(), queryPlan,
                                stepIds, runtime.artifactAuthorizer(), clock);
                    } else projected = runtime.projector().rank(caller, snapshot.run().token(),
                            prepared.queries().get(0).stepId(), selection.gid(), selection.startDate(),
                            selection.endDate(), selection.metric(), selection.limit(),
                            runtime.artifactAuthorizer());
                    Map<String, Object> completed = new LinkedHashMap<>(projected);
                    completed.put("continuation", continuation(toolName, selection, work));
                    return ToolResult.success(completed);
                }
                Outcome observed = runtime.outcomes().latest(receipt).orElse(null);
                boolean incomplete = failed(observed) || states.values().stream().anyMatch(step ->
                        step.status() == StepStatus.FAILED || step.status() == StepStatus.BLOCKED);
                boolean retryQueued = false;
                if (!states.values().stream().anyMatch(step -> step.status() == StepStatus.FAILED)) {
                    try { runtime.intake().submit(current, work); retryQueued = true; }
                    catch (CapacityRejectedException full) { incomplete = true; }
                }
                return ToolResult.success(pending(toolName, prepared, selection, work, states,
                        incomplete ? "INCOMPLETE" : "PENDING", observed, retryQueued));
            }
            Outcome observed = runtime.outcomes().latest(receipt).orElse(null);
            String status = failed(observed) ? "INCOMPLETE" : "PENDING";
            boolean retryQueued = false;
            try { runtime.intake().submit(current, work); retryQueued = true; }
            catch (CapacityRejectedException full) { status = "INCOMPLETE"; }
            return ToolResult.success(pending(toolName, prepared, selection, work, Map.of(),
                    status, observed, retryQueued));
        } catch (SecurityException denied) {
            return ToolResult.failure("STATISTICS_ACCESS_DENIED");
        } catch (IllegalArgumentException invalid) {
            return ToolResult.failure(invalid.getMessage() != null && invalid.getMessage().startsWith("STATISTICS_")
                    ? invalid.getMessage() : "STATISTICS_ARGUMENTS_INVALID");
        } catch (RuntimeException unavailable) {
            return ToolResult.failure("STATISTICS_DURABLE_RUNTIME_UNAVAILABLE");
        }
    }

    private CampaignStatisticsPlanFactory.Prepared prepare(String name, Caller owner, String session,
            String requestKey, Selection selection) {
        if (COMPARE.equals(name)) return runtime.plans().comparison(owner, session, requestKey,
                selection.scopes(), selection.periods());
        return runtime.plans().ranking(owner, session, requestKey, selection.gid(),
                selection.startDate(), selection.endDate(), selection.metric(), selection.limit());
    }

    private static Selection comparison(Map<String, Object> arguments) {
        List<Map<String, Object>> scopes = canonicalList(arguments.get("scopes"), SCOPE_FIELDS, true);
        List<Map<String, Object>> periods = canonicalList(arguments.get("periods"), PERIOD_FIELDS, false);
        var plan = CampaignStatisticsQueryPlan.create(scopes, periods);
        if (plan.combinations() > 16) throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
        return new Selection(scopes, periods, plan, null, null, null, null, null);
    }

    private static Selection ranking(Map<String, Object> arguments) {
        String gid = exactText(arguments.get("gid"));
        String start = exactText(arguments.get("startDate"));
        String end = exactText(arguments.get("endDate"));
        String metric = exactText(arguments.get("metric"));
        Object rawLimit = arguments.get("limit");
        if (!(rawLimit instanceof Integer || rawLimit instanceof Long)
                || ((Number) rawLimit).longValue() < 1 || ((Number) rawLimit).longValue() > 50)
            throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
        return new Selection(null, null, null, gid, start, end, metric, ((Number) rawLimit).intValue());
    }

    private static List<Map<String, Object>> canonicalList(Object raw, Set<String> allowed, boolean scope) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 16)
            throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
        List<Map<String, Object>> canonical = new ArrayList<>(list.size());
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map) || !allowed.containsAll(map.keySet()))
                throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
            if (map.containsKey("label") && !(map.get("label") instanceof String label
                    && label.length() <= 128 && label.chars().noneMatch(Character::isISOControl)))
                throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
            Map<String, Object> normalized = new LinkedHashMap<>();
            if (scope) {
                normalized.put("gid", exactText(map.get("gid")));
                if (map.containsKey("fullShortUrl")) {
                    String url = exactText(map.get("fullShortUrl"));
                    if (url.matches("(?i)^https?://.*")) {
                        url = url.substring(url.indexOf("://") + 3);
                    }
                    if (url.isBlank() || url.contains("://") || url.length() > 2048)
                        throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
                    normalized.put("fullShortUrl", url);
                }
            } else {
                normalized.put("startDate", exactText(map.get("startDate")));
                normalized.put("endDate", exactText(map.get("endDate")));
            }
            // Labels are display hints. The frozen definition intentionally contains only identity.
            canonical.add(Map.copyOf(normalized));
        }
        return List.copyOf(canonical);
    }

    private static String exactText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())
                || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("STATISTICS_ARGUMENTS_INVALID");
        return text;
    }

    private static void requireFields(Map<String, Object> args, Set<String> selection,
            boolean continuing, String code) {
        Set<String> keys = new java.util.HashSet<>(selection);
        if (continuing) keys.add("workRef");
        if (!keys.equals(args.keySet())) throw new IllegalArgumentException(code);
    }

    private static WorkRef workRef(Object value) {
        if (!(value instanceof Map<?, ?> map) || !Set.of("runId", "workId").equals(map.keySet()))
            throw new IllegalArgumentException("STATISTICS_WORK_REF_INVALID");
        return new WorkRef(exactText(map.get("runId")), exactText(map.get("workId")));
    }

    private static void requireReceipt(Header receipt, Caller caller, String session) {
        if (!caller.equals(receipt.caller()) || !session.equals(receipt.sessionId())
                || !CampaignStatisticsFixedRuntime.PROFILE_REF.equals(receipt.profileRef())
                || !CampaignStatisticsFixedRuntime.PROFILE_VERSION.equals(receipt.profileVersion())
                || receipt.revision() != 1)
            throw new SecurityException("STATISTICS_CONTINUATION_OWNER_CHANGED");
    }

    private static boolean failed(Outcome outcome) {
        return outcome != null && JdbcCampaignAdvanceOutcomeStore.FAILED.equals(outcome.status());
    }

    private static Map<String, Object> pending(String name, CampaignStatisticsPlanFactory.Prepared prepared,
            Selection selection, WorkRef work, Map<String, StepRecord> states, String status,
            Outcome observed, boolean retryQueued) {
        Map<String, Object> continuation = continuation(name, selection, work);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (COMPARE.equals(name)) for (var query : prepared.queries()) {
            Map<String, Object> row = new LinkedHashMap<>(query.request());
            row.remove("requestId");
            row.put("key", query.key());
            StepRecord step = states.get(query.stepId());
            row.put("status", "INCOMPLETE".equals(status) || step != null
                    && (step.status() == StepStatus.FAILED || step.status() == StepStatus.BLOCKED)
                    ? "INCOMPLETE" : "PENDING");
            rows.add(Map.copyOf(row));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resultComplete", false);
        meta.put("planId", prepared.queryPlanId());
        meta.put("queryCount", prepared.queries().size());
        meta.put("businessTimezone", "Asia/Shanghai");
        if (observed != null) {
            // RUNNING records only the most recent admitted attempt; it is not liveness evidence.
            meta.put("lastAdvanceStatus", observed.status());
            if (failed(observed)) {
                meta.put("advanceReason", observed.reason());
                meta.put("canRetryWithWorkRef", true);
                meta.put("retryQueued", retryQueued);
            }
        }
        if (RANK.equals(name)) {
            meta.put("metric", selection.metric());
            meta.put("limit", selection.limit());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", COMPARE.equals(name) ? "comparison" : "ranking");
        result.put("status", status);
        result.put("rows", List.copyOf(rows));
        result.put("meta", Map.copyOf(meta));
        if (failed(observed)) result.put("warnings", List.of(
                "上次推进未完成，可继续查询本次分析或稍后重试。"));
        result.put("continuation", continuation);
        return result;
    }

    private static Map<String, Object> continuation(String name, Selection selection, WorkRef work) {
        Map<String, Object> continuation = new LinkedHashMap<>();
        if (COMPARE.equals(name)) {
            continuation.put("scopes", selection.scopes());
            continuation.put("periods", selection.periods());
        } else {
            continuation.put("gid", selection.gid());
            continuation.put("startDate", selection.startDate());
            continuation.put("endDate", selection.endDate());
            continuation.put("metric", selection.metric());
            continuation.put("limit", selection.limit());
        }
        continuation.put("workRef", Map.of("runId", work.runId(), "workId", work.workId()));
        return Map.copyOf(continuation);
    }

    private record Selection(List<Map<String, Object>> scopes, List<Map<String, Object>> periods,
            CampaignStatisticsQueryPlan.Plan queryPlan, String gid, String startDate, String endDate,
            String metric, Integer limit) {}
}
