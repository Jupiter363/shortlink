package com.nageoffer.shortlink.agent.harness.action;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionContext;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionExecutionResult;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionPage;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionProposal;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionStatus;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionType;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingActionView;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadConflictException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionModelTest {

    private static final String ACTION_TYPE_ERROR =
            "Agent action type must match [a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+";

    @Test
    void statusAuthorizationAndActionTypeExposeStableContracts() {
        assertThat(AgentActionStatus.values()).containsExactly(
                AgentActionStatus.PENDING,
                AgentActionStatus.EXECUTING,
                AgentActionStatus.EXECUTED,
                AgentActionStatus.FAILED,
                AgentActionStatus.REJECTED,
                AgentActionStatus.EXPIRED
        );
        assertThat(AgentActionAuthorizationScope.values()).containsExactly(
                AgentActionAuthorizationScope.GID,
                AgentActionAuthorizationScope.OWNER
        );

        assertThat(new AgentActionType("risk.disable-short-link").value())
                .isEqualTo("risk.disable-short-link");
        assertThat(new AgentActionType("risk.limit-time-window").value())
                .isEqualTo("risk.limit-time-window");
        assertThat(new AgentActionType("risk.block-ip").value())
                .isEqualTo("risk.block-ip");

        String[] invalidValues = {
                null,
                "",
                "disable-short-link",
                "risk_disable_short_link",
                "Risk.disable-short-link",
                "risk.Disable-short-link",
                " risk.disable-short-link",
                "risk.disable-short-link "
        };
        for (String invalidValue : invalidValues) {
            assertThatThrownBy(() -> new AgentActionType(invalidValue))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(ACTION_TYPE_ERROR);
        }
    }

    @Test
    void recordsExposeTheFieldsRequiredByLaterActionTasks() {
        assertRecordComponents(AgentActionProposal.class,
                "actionId", "agentType", "actionType", "payloadVersion", "authorizationScope",
                "ownerUsername", "gid", "targetType", "targetKey", "targetRef", "title", "summary",
                "payload", "evidence", "idempotencyKey", "activeSlotKey", "proposedBy", "traceId",
                "eventId", "batchId", "sessionId");
        assertRecordComponents(AgentPendingAction.class,
                "id", "actionId", "agentType", "actionType", "payloadVersion", "authorizationScope",
                "ownerUsername", "gid", "targetType", "targetKey", "targetRefJson", "title", "summary",
                "payloadJson", "payloadHash", "evidenceJson", "idempotencyKey", "activeSlotKey", "status",
                "expireTime", "version", "executionToken", "executionLeaseUntil", "attemptCount", "resultJson",
                "failureCode", "failureMessage", "proposedBy", "confirmedBy", "confirmedTime", "rejectedBy",
                "rejectedTime", "rejectionReason", "rejectionReviewAction", "traceId", "eventId", "batchId",
                "sessionId", "createTime", "updateTime");
        assertRecordComponents(AgentActionActor.class,
                "username", "userId", "realName", "expectedGid");
        assertRecordComponents(AgentActionExecutionContext.class, "actor", "note");
        assertRecordComponents(AgentActionExecutionResult.class, "result");
        assertRecordComponents(AgentPendingActionView.class,
                "actionId", "agentType", "actionType", "status", "gid", "targetType", "target", "title",
                "summary", "evidenceSummary", "attemptCount", "version", "expireTime", "rejectionReason",
                "rejectionReviewAction", "result", "failure");
        assertRecordComponents(AgentActionPage.class, "records", "total", "pageNo", "pageSize");
    }

    @Test
    void proposalDeeplyCopiesAllMapContainers() {
        Map<String, Object> nestedTarget = new LinkedHashMap<>();
        nestedTarget.put("shortUri", "abc");
        Map<String, Object> targetRef = new LinkedHashMap<>();
        targetRef.put("domain", "nurl.ink");
        targetRef.put("nested", nestedTarget);

        List<Object> items = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("count", 3);
        items.add(item);
        Object[] windows = {new LinkedHashMap<>(Map.of("requests", 12))};
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("windows", windows);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("metrics", new LinkedHashMap<>(Map.of("riskScore", 91)));

        AgentActionProposal proposal = proposal(targetRef, payload, evidence);

        targetRef.put("domain", "changed.example");
        nestedTarget.put("shortUri", "changed");
        items.add("late-item");
        item.put("count", 99);
        windows[0] = Map.of("requests", 99);
        payload.put("late", true);
        evidence.clear();

        assertThat(proposal.targetRef()).containsEntry("domain", "nurl.ink");
        assertThat(asMap(proposal.targetRef().get("nested"))).containsEntry("shortUri", "abc");
        assertThat(asList(proposal.payload().get("items"))).hasSize(1);
        assertThat(asMap(asList(proposal.payload().get("items")).get(0))).containsEntry("count", 3);
        assertThat(asMap(asList(proposal.payload().get("windows")).get(0))).containsEntry("requests", 12);
        assertThat(asMap(proposal.evidence().get("metrics"))).containsEntry("riskScore", 91);

        assertThatThrownBy(() -> proposal.payload().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(proposal.payload().get("items")).add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(asList(proposal.payload().get("items")).get(0)).put("count", 4))
                .isInstanceOf(UnsupportedOperationException.class);

        AgentActionProposal emptyMaps = proposal(null, null, null);
        assertThat(emptyMaps.targetRef()).isEmpty();
        assertThat(emptyMaps.payload()).isEmpty();
        assertThat(emptyMaps.evidence()).isEmpty();
    }

    @Test
    void executionResultPageActorAndContextKeepImmutableContracts() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("policyVersion", 2L);
        nested.put("policyKey", "risk:policy:short-link:block-ip:nurl.ink:abc:full-hash");
        Map<String, Object> resultSource = new LinkedHashMap<>();
        resultSource.put("policy", nested);
        AgentActionExecutionResult executionResult = new AgentActionExecutionResult(resultSource);

        nested.put("policyVersion", 3L);
        resultSource.clear();

        assertThat(asMap(executionResult.result().get("policy"))).containsEntry("policyVersion", 2L);
        assertThat(asMap(executionResult.result().get("policy")))
                .containsEntry("policyKey", "risk:policy:short-link:block-ip:nurl.ink:abc:full-hash");
        assertThatThrownBy(() -> asMap(executionResult.result().get("policy")).put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(AgentActionExecutionResult.empty().result()).isEmpty();

        List<String> records = new ArrayList<>(List.of("a", "b"));
        AgentActionPage<String> page = new AgentActionPage<>(records, 2L, 1, 20);
        records.add("c");
        assertThat(page.records()).containsExactly("a", "b");
        assertThatThrownBy(() -> page.records().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new AgentActionPage<String>(null, 0L, 1, 20).records()).isEmpty();

        AgentActionActor actor = new AgentActionActor("trusted-user", "1001", "Trusted User", "g1");
        AgentActionExecutionContext context = new AgentActionExecutionContext(actor, "reviewed");
        assertThat(context.actor()).isSameAs(actor);
        assertThat(context.note()).isEqualTo("reviewed");

        AgentActionActor replayActor = new AgentActionActor("operator", "1002", "Operator", "");
        assertThat(replayActor.expectedGid()).isEmpty();
    }

    @Test
    void pendingActionMapsEveryPersistenceFieldWithoutFrameworkDependencies() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 11, 12, 0);
        AgentPendingAction action = new AgentPendingAction(
                7L,
                "action-1",
                "security-risk",
                new AgentActionType("risk.block-ip"),
                1,
                AgentActionAuthorizationScope.GID,
                "owner",
                "g1",
                "SHORT_LINK",
                "nurl.ink/abc",
                "{\"domain\":\"nurl.ink\",\"shortUri\":\"abc\"}",
                "Block abusive IP",
                "Repeated abusive traffic",
                "{\"ipHash\":\"hash-1\"}",
                "payload-hash",
                "{\"maskedIp\":\"203.0.*.*\"}",
                "idem-1",
                null,
                AgentActionStatus.EXECUTING,
                null,
                4L,
                "execution-1",
                now.plusMinutes(5),
                2,
                "{}",
                "",
                "",
                "risk-analysis-worker",
                "trusted-user",
                now.minusMinutes(1),
                "",
                null,
                "",
                null,
                "trace-1",
                "event-1",
                "batch-1",
                "session-1",
                now.minusHours(1),
                now
        );

        assertThat(action.id()).isEqualTo(7L);
        assertThat(action.actionType().value()).isEqualTo("risk.block-ip");
        assertThat(action.activeSlotKey()).isNull();
        assertThat(action.expireTime()).isNull();
        assertThat(action.rejectionReviewAction()).isNull();
        assertThat(action.executionLeaseUntil()).isEqualTo(now.plusMinutes(5));
        assertThat(action.updateTime()).isEqualTo(now);
    }

    @Test
    void viewCopiesMapsLimitsReviewTextAndDoesNotSanitizeResultTooEarly() {
        Map<String, Object> target = new LinkedHashMap<>(Map.of("domain", "nurl.ink"));
        Map<String, Object> evidenceSummary = new LinkedHashMap<>(Map.of("riskScore", 91));
        Map<String, Object> result = new LinkedHashMap<>(Map.of("ipHash", "full-hash"));
        Map<String, Object> failure = new LinkedHashMap<>(Map.of("code", "FAILED"));

        AgentPendingActionView view = new AgentPendingActionView(
                "action-1",
                "security-risk",
                "risk.block-ip",
                AgentActionStatus.FAILED,
                "g1",
                "SHORT_LINK",
                target,
                "Block abusive IP",
                "Repeated abusive traffic",
                evidenceSummary,
                2,
                4L,
                null,
                "r".repeat(2100),
                "FALSE_POSITIVE".repeat(4),
                result,
                failure
        );

        target.clear();
        evidenceSummary.clear();
        result.clear();
        failure.clear();

        assertThat(view.target()).containsEntry("domain", "nurl.ink");
        assertThat(view.evidenceSummary()).containsEntry("riskScore", 91);
        assertThat(view.result()).containsEntry("ipHash", "full-hash");
        assertThat(view.failure()).containsEntry("code", "FAILED");
        assertThat(view.rejectionReason()).hasSize(2048);
        assertThat(view.rejectionReviewAction()).hasSize(32);
        assertThatThrownBy(() -> view.result().put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);

        AgentPendingActionView empty = new AgentPendingActionView(
                "action-2", "security-risk", "risk.disable-short-link", AgentActionStatus.PENDING,
                "g1", "SHORT_LINK", null, "title", "summary", null, 0, 1L, null,
                null, null, null, null
        );
        assertThat(empty.target()).isEmpty();
        assertThat(empty.evidenceSummary()).isEmpty();
        assertThat(empty.result()).isEmpty();
        assertThat(empty.failure()).isNull();
        assertThat(empty.rejectionReason()).isNull();
        assertThat(empty.rejectionReviewAction()).isNull();
    }

    @Test
    void evidenceSummaryRetainsOnlySafeReviewFieldsAndNumericMetrics() {
        Map<String, Object> metricRow = new LinkedHashMap<>();
        metricRow.put("count", 4);
        metricRow.put("label", "must-not-leak-from-row");
        metricRow.put("visitor", "visitor-1");
        Object[] windows = {metricRow, 7};
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("requestCount", 42);
        metrics.put("topIpShare", 0.75D);
        metrics.put("windows", windows);
        metrics.put("secretLabel", "must-not-leak-from-metrics");
        metrics.put("IpHaSh", "full-hash-nested");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reasonCodes", new ArrayList<>(List.of("TRAFFIC_SPIKE", "IP_CONCENTRATION")));
        evidence.put("maskedIp", "203.0.*.*");
        evidence.put("eventId", "event-1");
        evidence.put("batchId", "batch-1");
        evidence.put("riskScore", 91);
        evidence.put("metrics", metrics);
        evidence.put("malformed", Map.of(
                "reasonCodes", Map.of("raw", "must-not-leak-malformed-reason-codes")
        ));
        evidence.put("ip", "203.0.113.8");
        evidence.put("ipHash", "full-hash");
        evidence.put("user", "user-1");
        evidence.put("unknownText", "must-not-leak-unknown");
        evidence.put("payloadJson", "payload-secret");
        evidence.put("EvidenceJson", "evidence-secret");
        evidence.put("ExecutionToken", "token-secret");

        Map<String, Object> summary = AgentPendingActionView.summarizeEvidence(evidence);

        assertThat(summary)
                .containsEntry("maskedIp", "203.0.*.*")
                .containsEntry("eventId", "event-1")
                .containsEntry("batchId", "batch-1")
                .containsEntry("riskScore", 91)
                .containsKey("metrics");
        assertThat(asList(summary.get("reasonCodes")))
                .containsExactly("TRAFFIC_SPIKE", "IP_CONCENTRATION");
        assertThat(asMap(summary.get("metrics")))
                .containsEntry("requestCount", 42)
                .containsEntry("topIpShare", 0.75D);
        assertThat(asList(asMap(summary.get("metrics")).get("windows"))).hasSize(2);
        assertThat(asMap(asList(asMap(summary.get("metrics")).get("windows")).get(0)))
                .containsEntry("count", 4)
                .doesNotContainKeys("label", "visitor");

        assertThat(summary.toString())
                .doesNotContain("203.0.113.8")
                .doesNotContain("full-hash")
                .doesNotContain("user-1")
                .doesNotContain("must-not-leak-unknown")
                .doesNotContain("must-not-leak-from-row")
                .doesNotContain("must-not-leak-from-metrics")
                .doesNotContain("must-not-leak-malformed-reason-codes")
                .doesNotContain("payload-secret")
                .doesNotContain("evidence-secret")
                .doesNotContain("token-secret");
        assertThat(evidence).containsKeys("ip", "ipHash", "user", "unknownText");
        assertThat(metrics).containsEntry("IpHaSh", "full-hash-nested");
        assertThatThrownBy(() -> summary.put("new", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(summary.get("metrics")).put("new", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(asMap(summary.get("metrics")).get("windows")).add(9))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceSummaryRejectsStatusContainersAndDynamicIdentityKeys() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("203.0.113.8", 9);
        status.put("accountNumber", 7);
        status.put("count", 5);
        status.put("unknownText", "must-not-leak-status");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", status);

        Map<String, Object> summary = AgentPendingActionView.summarizeEvidence(evidence);

        assertThat(summary).doesNotContainKey("status");
        assertThat(summary.toString())
                .doesNotContain("203.0.113.8")
                .doesNotContain("accountNumber")
                .doesNotContain("must-not-leak-status");
        assertThat(evidence).containsEntry("status", status);
        assertThat(status)
                .containsEntry("203.0.113.8", 9)
                .containsEntry("accountNumber", 7)
                .containsEntry("count", 5);
    }

    @Test
    void evidenceSummaryKeepsWhitelistedMetricsWithoutDynamicIpKeys() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("203.0.113.8", 9);
        metrics.put("2001:db8::1", 8);
        metrics.put("requestCount", 42);
        metrics.put("topIpShare", 0.75D);
        Map<String, Object> topIpStats = new LinkedHashMap<>();
        topIpStats.put("198.51.100.9", 6);
        topIpStats.put("count", 6);
        topIpStats.put("share", 0.4D);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("riskMetrics", metrics);
        evidence.put("topIpStats", topIpStats);

        Map<String, Object> summary = AgentPendingActionView.summarizeEvidence(evidence);

        assertThat(asMap(summary.get("riskMetrics")))
                .containsEntry("requestCount", 42)
                .containsEntry("topIpShare", 0.75D)
                .doesNotContainKeys("203.0.113.8", "2001:db8::1");
        assertThat(asMap(summary.get("topIpStats")))
                .containsEntry("count", 6)
                .containsEntry("share", 0.4D)
                .doesNotContainKey("198.51.100.9");
        assertThat(metrics).containsEntry("203.0.113.8", 9).containsEntry("2001:db8::1", 8);
        assertThat(topIpStats).containsEntry("198.51.100.9", 6);
        assertThatThrownBy(() -> summary.put("new", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(summary.get("riskMetrics")).put("new", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceSummaryRequiresExplicitMetricNamesAndRejectsIpLikeKeys() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("corporateId", 1001L);
        metrics.put("sessionId", 1002L);
        metrics.put("deviceId", 1003L);
        metrics.put("policyVersion", 7L);
        metrics.put("requestCount", 42);
        metrics.put("topIpShare", 0.75D);
        metrics.put("riskScore", 91);
        metrics.put("pv2h", 120L);
        metrics.put("pvPerUv", 3.5D);
        metrics.put("userCount", 12);
        metrics.put("visitorCount", "13");
        metrics.put("203.0.113.8:443", 9);
        metrics.put("203.0.113.0/24", 8);
        metrics.put("[2001:db8::1]:443", 7);
        metrics.put("[2001:db8::]/64", 6);
        metrics.put("2001:db8::2", 5);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("aggregateMetrics", metrics);

        Map<String, Object> summary = AgentPendingActionView.summarizeEvidence(evidence);
        Map<String, Object> sanitizedMetrics = asMap(summary.get("aggregateMetrics"));

        assertThat(sanitizedMetrics)
                .containsEntry("requestCount", 42)
                .containsEntry("topIpShare", 0.75D)
                .containsEntry("riskScore", 91)
                .containsEntry("pv2h", 120L)
                .containsEntry("pvPerUv", 3.5D)
                .containsEntry("userCount", 12)
                .doesNotContainKeys(
                        "corporateId",
                        "sessionId",
                        "deviceId",
                        "policyVersion",
                        "visitorCount",
                        "203.0.113.8:443",
                        "203.0.113.0/24",
                        "[2001:db8::1]:443",
                        "[2001:db8::]/64",
                        "2001:db8::2"
                );
        assertThat(metrics)
                .containsEntry("corporateId", 1001L)
                .containsEntry("203.0.113.8:443", 9)
                .containsEntry("[2001:db8::1]:443", 7);
        assertThatThrownBy(() -> sanitizedMetrics.put("new", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceSummaryAllowsBareNumbersOnlyForNamedMetricSeries() {
        List<Long> sessionIds = new ArrayList<>(List.of(1001L, 1002L));
        long[] deviceIds = {2001L, 2002L};
        List<Integer> riskScores = new ArrayList<>(List.of(81, 92));
        int[] requestCounts = {40, 55};
        List<Double> riskTrend7d = new ArrayList<>(List.of(0.2D, 0.6D));
        Object[] trend = {1, 3, 5};
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sessionIds", sessionIds);
        metrics.put("deviceIds", deviceIds);
        metrics.put("riskScores", riskScores);
        metrics.put("requestCounts", requestCounts);
        metrics.put("riskTrend7d", riskTrend7d);
        metrics.put("trend", trend);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("riskMetrics", metrics);

        Map<String, Object> summary = AgentPendingActionView.summarizeEvidence(evidence);
        Map<String, Object> sanitizedMetrics = asMap(summary.get("riskMetrics"));

        assertThat(sanitizedMetrics)
                .doesNotContainKeys("sessionIds", "deviceIds")
                .containsKeys("riskScores", "requestCounts", "riskTrend7d", "trend");
        assertThat(asList(sanitizedMetrics.get("riskScores"))).containsExactly(81, 92);
        assertThat(asList(sanitizedMetrics.get("requestCounts"))).containsExactly(40, 55);
        assertThat(asList(sanitizedMetrics.get("riskTrend7d"))).containsExactly(0.2D, 0.6D);
        assertThat(asList(sanitizedMetrics.get("trend"))).containsExactly(1, 3, 5);

        assertThat(sessionIds).containsExactly(1001L, 1002L);
        assertThat(deviceIds).containsExactly(2001L, 2002L);
        assertThat(riskScores).containsExactly(81, 92);
        assertThat(requestCounts).containsExactly(40, 55);
        assertThatThrownBy(() -> sanitizedMetrics.put("new", List.of(1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(sanitizedMetrics.get("riskScores")).add(100))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void finalResultSanitizerMasksBlockIpPolicyKeysAndDropsInternalFieldsRecursively() {
        String policyKey = "risk:policy:short-link:block-ip:nurl.ink:abc:abcdef";
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("PolicyKey", "risk:policy:short-link:block-ip:nurl.ink:abc:nested-hash");
        nested.put("IpHaSh", "nested-hash");
        nested.put("rawIp", "203.0.113.8");
        nested.put("rawVisitor", "visitor-secret");
        nested.put("payloadJson", "payload-secret");
        nested.put("safeCount", 3);
        Object[] audit = {nested};
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", "policy-1");
        result.put("policyKey", policyKey);
        result.put("policyVersion", 2L);
        result.put("policyStatus", "ACTIVE");
        result.put("syncStatus", "SYNCED");
        result.put("desiredState", "ACTIVE");
        result.put("effective", true);
        result.put("ipHash", "full-hash");
        result.put("rawUser", "user-secret");
        result.put("evidenceJson", "evidence-secret");
        result.put("executionToken", "token-secret");
        result.put("audit", audit);

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("risk.block-ip"),
                result
        );

        assertThat(sanitized)
                .containsEntry("policyId", "policy-1")
                .containsEntry("policyKey", "risk:policy:short-link:block-ip:nurl.ink:abc:***")
                .containsEntry("policyVersion", 2L)
                .containsEntry("policyStatus", "ACTIVE")
                .containsEntry("syncStatus", "SYNCED")
                .containsEntry("desiredState", "ACTIVE")
                .containsEntry("effective", true)
                .doesNotContainKeys("ipHash", "rawUser", "evidenceJson", "executionToken");
        Map<String, Object> sanitizedNested = asMap(asList(sanitized.get("audit")).get(0));
        assertThat(sanitizedNested)
                .containsEntry("PolicyKey", "risk:policy:short-link:block-ip:nurl.ink:abc:***")
                .containsEntry("safeCount", 3)
                .doesNotContainKeys("IpHaSh", "rawIp", "rawVisitor", "payloadJson");
        assertThat(sanitized.toString())
                .doesNotContain("full-hash")
                .doesNotContain("nested-hash")
                .doesNotContain("203.0.113.8")
                .doesNotContain("user-secret")
                .doesNotContain("visitor-secret")
                .doesNotContain("payload-secret")
                .doesNotContain("evidence-secret")
                .doesNotContain("token-secret");

        assertThat(result).containsEntry("policyKey", policyKey).containsEntry("ipHash", "full-hash");
        assertThat(nested).containsEntry("IpHaSh", "nested-hash");
        assertThatThrownBy(() -> sanitized.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(sanitized.get("audit")).add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sanitizedNested.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void finalResultSanitizerKeepsNonBlockIpPolicyKeys() {
        String policyKey = "risk:policy:short-link:disable:nurl.ink:abc";
        for (String actionType : List.of(
                "risk.disable-short-link",
                "risk.limit-time-window",
                "campaign.pause-placement"
        )) {
            Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                    new AgentActionType(actionType),
                    Map.of(
                            "policyKey", policyKey,
                            "ipHash", "must-be-removed",
                            "nested", List.of(Map.of("policyKey", policyKey, "IPHASH", "nested-hash"))
                    )
            );

            assertThat(sanitized).containsEntry("policyKey", policyKey).doesNotContainKey("ipHash");
            assertThat(asMap(asList(sanitized.get("nested")).get(0)))
                    .containsEntry("policyKey", policyKey)
                    .doesNotContainKey("IPHASH");
        }
    }

    @Test
    void finalResultSanitizerDropsIdentityVariantsAndKeepsOnlyNumericIdentityCounts() {
        String policyKey = "risk:policy:short-link:disable:nurl.ink:abc";
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("userEmail", "user@example.com");
        identity.put("USER_IDENTIFIER", "user-identifier");
        identity.put("visitor-fingerprint", "visitor-fingerprint");
        identity.put("Visitor_Name", "visitor-name");
        identity.put("account-number", "account-number");
        identity.put("userCount", 12);
        identity.put("visitor_count", 8);
        identity.put("USER-COUNT", "12");
        Object[] audit = {
                identity,
                List.of(Map.of(
                        "USER_EMAIL", "nested-user@example.com",
                        "visitor-count", "8",
                        "user_count", 5
                ))
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyKey", policyKey);
        result.put("audit", audit);

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("risk.disable-short-link"),
                result
        );

        assertThat(sanitized).containsEntry("policyKey", policyKey);
        List<Object> sanitizedAudit = asList(sanitized.get("audit"));
        Map<String, Object> sanitizedIdentity = asMap(sanitizedAudit.get(0));
        assertThat(sanitizedIdentity)
                .containsEntry("userCount", 12)
                .containsEntry("visitor_count", 8)
                .doesNotContainKeys(
                        "userEmail",
                        "USER_IDENTIFIER",
                        "visitor-fingerprint",
                        "Visitor_Name",
                        "account-number",
                        "USER-COUNT"
                );
        Map<String, Object> nestedIdentity = asMap(asList(sanitizedAudit.get(1)).get(0));
        assertThat(nestedIdentity)
                .containsEntry("user_count", 5)
                .doesNotContainKeys("USER_EMAIL", "visitor-count");
        assertThat(sanitized.toString())
                .doesNotContain("user@example.com")
                .doesNotContain("user-identifier")
                .doesNotContain("visitor-fingerprint")
                .doesNotContain("visitor-name")
                .doesNotContain("account-number")
                .doesNotContain("nested-user@example.com");

        assertThat(result).containsEntry("policyKey", policyKey).containsEntry("audit", audit);
        assertThat(identity)
                .containsEntry("userEmail", "user@example.com")
                .containsEntry("USER-COUNT", "12");
        assertThatThrownBy(() -> sanitized.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sanitizedAudit.add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sanitizedIdentity.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void finalResultSanitizerRedactsSensitiveTextAndKeepsSafeGenericStrings() {
        String message = "client 203.0.113.8 Bearer bearer-secret sk-live-secret "
                + "token=token-secret password:password-secret secret=secret-value "
                + "apiKey:api-key-secret credential=credential-secret "
                + "user=visitor-1 visitor=visitor-2 username:admin";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("policyStatus", "ACTIVE");
        result.put("syncStatus", "SYNCED");
        result.put("desiredState", "PAUSED");
        result.put("futureCampaignState", "READY");

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                result
        );

        assertThat(sanitized)
                .containsEntry("policyStatus", "ACTIVE")
                .containsEntry("syncStatus", "SYNCED")
                .containsEntry("desiredState", "PAUSED")
                .containsEntry("futureCampaignState", "READY");
        assertThat((String) sanitized.get("message"))
                .contains("client ***")
                .contains("Bearer ***")
                .contains("token=***")
                .contains("password:***")
                .contains("secret=***")
                .contains("apiKey:***")
                .contains("credential=***")
                .contains("user=***")
                .contains("visitor=***")
                .contains("username:***")
                .doesNotContain(
                        "203.0.113.8",
                        "bearer-secret",
                        "sk-live-secret",
                        "token-secret",
                        "password-secret",
                        "secret-value",
                        "api-key-secret",
                        "credential-secret",
                        "visitor-1",
                        "visitor-2",
                        "admin"
                );
        assertThat(result).containsEntry("message", message);
        assertThatThrownBy(() -> sanitized.put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void finalResultSanitizerRedactsIpv6IdentityIdsAndJsonStyleSecrets() {
        String message = "client 2001:db8::1 visitorId=visitor-secret userId:user-secret "
                + "{\"token\":\"token-secret\",\"apiKey\":\"api-secret\"}";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("safeStatus", "READY");

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                result
        );

        assertThat(sanitized).containsEntry("safeStatus", "READY");
        assertThat((String) sanitized.get("message"))
                .contains("client ***")
                .contains("visitorId=***")
                .contains("userId:***")
                .contains("{\"token\":\"***\",\"apiKey\":\"***\"}")
                .doesNotContain(
                        "2001:db8::1",
                        "visitor-secret",
                        "user-secret",
                        "token-secret",
                        "api-secret"
                );
        assertThat(result).containsEntry("message", message).containsEntry("safeStatus", "READY");
        assertThatThrownBy(() -> sanitized.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void finalResultSanitizerKeepsClockTextButRedactsIpv6Literals() {
        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                Map.of("message", "completed at 12:30:45 via 2001:db8::1 and ::1")
        );

        assertThat(sanitized.get("message"))
                .isEqualTo("completed at 12:30:45 via *** and ***");
    }

    @Test
    void finalResultSanitizerUsesStrictNumericIpv6LiteralParsing() {
        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                Map.of("message", String.join(" | ",
                        "::",
                        "::1",
                        "2001:db8::1",
                        "2001:0db8:0000:0000:0000:ff00:0042:8329",
                        "12:30:45",
                        "2001:db8:1",
                        "2001:::1",
                        "2001:db8::1::2",
                        "12345:db8::1"
                ))
        );

        assertThat(sanitized.get("message")).isEqualTo(String.join(" | ",
                "***",
                "***",
                "***",
                "***",
                "12:30:45",
                "2001:db8:1",
                "2001:::1",
                "2001:db8::1::2",
                "12345:db8::1"
        ));
    }

    @Test
    void finalResultSanitizerUsesOneStrictParserForIpKeysAndEmbeddedIpv6Text() {
        Map<String, Object> dynamic = new LinkedHashMap<>();
        dynamic.put("::ffff:192.0.2.128", "mapped");
        dynamic.put("[::ffff:192.0.2.128]:443", "mapped-port");
        dynamic.put("2001:db8::1", "ipv6");
        dynamic.put("[2001:db8::1]/64", "cidr");
        dynamic.put("fe80::1%eth0", "zone");
        dynamic.put("999.0.0.1", "invalid-ipv4");
        dynamic.put("2001:::1", "invalid-triple-colon");
        dynamic.put("2001:db8::1::2", "invalid-double-compression");
        dynamic.put("1:2:3:4:5:6:7:8:9", "too-many-units");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dynamic", dynamic);
        result.put("message", "mapped ::ffff:192.0.2.128 and [::ffff:192.0.2.128]:443 "
                + "ipv6 2001:db8::1 zone fe80::1%eth0 completed at 12:30:45 "
                + "business order:id:ready invalid 2001:::1 and 999.0.0.1");

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                result
        );

        Map<String, Object> sanitizedDynamic = asMap(sanitized.get("dynamic"));
        assertThat(sanitizedDynamic)
                .doesNotContainKeys(
                        "::ffff:192.0.2.128",
                        "[::ffff:192.0.2.128]:443",
                        "2001:db8::1",
                        "[2001:db8::1]/64",
                        "fe80::1%eth0"
                )
                .containsEntry("999.0.0.1", "invalid-ipv4")
                .containsEntry("2001:::1", "invalid-triple-colon")
                .containsEntry("2001:db8::1::2", "invalid-double-compression")
                .containsEntry("1:2:3:4:5:6:7:8:9", "too-many-units");
        assertThat((String) sanitized.get("message"))
                .contains("mapped *** and ***")
                .contains("ipv6 *** zone ***")
                .contains("completed at 12:30:45")
                .contains("business order:id:ready")
                .contains("invalid 2001:::1 and 999.0.0.1")
                .doesNotContain("::ffff:", "192.0.2.128", "fe80::1%eth0");
    }

    @Test
    void finalResultSanitizerRedactsCommonCredentialHeadersAssignmentsAndUrlUserInfo() {
        String message = "request failed\n"
                + "Authorization: Basic basic-secret\n"
                + "Authorization: Bearer bearer-secret\n"
                + "Cookie: session=cookie-secret; theme=dark\n"
                + "Set-Cookie: session=set-cookie-secret; HttpOnly\n"
                + "access_token=access-secret access-token:access-dash-secret "
                + "refresh_token=refresh-secret id_token=id-secret "
                + "client_secret=client-secret session_token=session-secret\n"
                + "url=https://url-user:url-pass@example.com/path";

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                Map.of("message", message)
        );
        String safe = (String) sanitized.get("message");

        assertThat(safe)
                .contains("request failed")
                .contains("Authorization: ***")
                .contains("Cookie: ***")
                .contains("Set-Cookie: ***")
                .contains("access_token=***")
                .contains("access-token:***")
                .contains("refresh_token=***")
                .contains("id_token=***")
                .contains("client_secret=***")
                .contains("session_token=***")
                .contains("https://***@example.com/path")
                .doesNotContain(
                        "basic-secret", "bearer-secret", "cookie-secret", "set-cookie-secret",
                        "access-secret", "access-dash-secret", "refresh-secret", "id-secret",
                        "client-secret", "session-secret", "url-user", "url-pass"
                );
    }

    @Test
    void finalResultSanitizerRedactsUrlUserInfoOnlyWithinAuthority() {
        String message = "password-url=https://user:pass@example.com/path "
                + "token-url=https://access-token@example.com/path "
                + "plain=https://example.com/path "
                + "path-at=https://example.com/path/@marker "
                + "query-email=https://example.com?email=user@example.org "
                + "fragment-at=https://example.com#contact=@support";

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("campaign.pause-placement"),
                Map.of("message", message)
        );
        String safe = (String) sanitized.get("message");

        assertThat(safe)
                .contains("password-url=https://***@example.com/path")
                .contains("token-url=https://***@example.com/path")
                .contains("plain=https://example.com/path")
                .contains("path-at=https://example.com/path/@marker")
                .contains("query-email=https://example.com?email=user@example.org")
                .contains("fragment-at=https://example.com#contact=@support")
                .doesNotContain(
                        "user:pass@example.com",
                        "access-token@example.com"
                );
    }

    @Test
    void finalResultSanitizerBuildsJsonNativeTreeWithoutCallingUnknownToString() {
        DangerousObject dangerousKey = new DangerousObject("dangerous-key-secret");
        DangerousObject dangerousValue = new DangerousObject("dangerous-value-secret");
        Map<Object, Object> nested = new LinkedHashMap<>();
        nested.put("safeText", "campaign-ready");
        nested.put("futureState", FutureResultState.READY);
        nested.put("enabled", true);
        nested.put("amount", 12.5D);
        nested.put("nullable", null);
        nested.put("customPojo", dangerousValue);
        nested.put(dangerousKey, "non-string-key-secret");
        nested.put(7, "numeric-key-secret");
        List<Object> items = new ArrayList<>();
        items.add("safe-item");
        items.add(FutureResultState.PAUSED);
        items.add(dangerousValue);
        items.add(null);
        Object[] array = {"array-item", FutureResultState.READY, dangerousValue};
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyKey", 42L);
        result.put("generic", nested);
        result.put("items", items);
        result.put("array", array);
        result.put("customPojo", dangerousValue);

        Map<String, Object> sanitized = AgentPendingActionView.sanitizeFinalResult(
                new AgentActionType("risk.block-ip"),
                result
        );

        assertThat(dangerousKey.toStringCalls()).isZero();
        assertThat(dangerousValue.toStringCalls()).isZero();
        assertThat(sanitized).doesNotContainKeys("policyKey", "customPojo");
        Map<String, Object> sanitizedNested = asMap(sanitized.get("generic"));
        assertThat(sanitizedNested)
                .containsEntry("safeText", "campaign-ready")
                .containsEntry("futureState", "READY")
                .containsEntry("enabled", true)
                .containsEntry("amount", 12.5D)
                .containsEntry("nullable", null)
                .doesNotContainKeys("customPojo", "7", "dangerous-key-secret");
        assertThat(sanitizedNested.keySet()).allMatch(String.class::isInstance);
        assertThat(asList(sanitized.get("items")))
                .containsExactly("safe-item", "PAUSED", null);
        assertThat(asList(sanitized.get("array")))
                .containsExactly("array-item", "READY");

        assertThat(result).containsEntry("policyKey", 42L).containsEntry("customPojo", dangerousValue);
        assertThat(nested.containsKey(dangerousKey)).isTrue();
        assertThat(items).contains(dangerousValue);
        assertThatThrownBy(() -> sanitizedNested.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(sanitized.get("items")).add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void actionExceptionsExposeStableNonBlankCodes() {
        AgentActionException exception = new AgentActionException("ACTION_TEST", "Stable message");
        assertThat(exception.code()).isEqualTo("ACTION_TEST");
        assertThat(exception).hasMessage("Stable message");

        assertThatThrownBy(() -> new AgentActionException(" ", "message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent action error code must not be blank");

        AgentActionPayloadConflictException conflict = new AgentActionPayloadConflictException();
        assertThat(conflict.code()).isEqualTo("ACTION_PAYLOAD_CONFLICT");
        assertThat(conflict).hasMessage("Agent action payload conflicts with an existing action");
    }

    private AgentActionProposal proposal(
            Map<String, Object> targetRef,
            Map<String, Object> payload,
            Map<String, Object> evidence
    ) {
        return new AgentActionProposal(
                "action-1",
                "security-risk",
                new AgentActionType("risk.disable-short-link"),
                1,
                AgentActionAuthorizationScope.GID,
                "owner",
                "g1",
                "SHORT_LINK",
                "nurl.ink/abc",
                targetRef,
                "Disable risky short link",
                "Repeated abusive traffic",
                payload,
                evidence,
                "idem-1",
                "slot-1",
                "risk-analysis-worker",
                "trace-1",
                "event-1",
                "batch-1",
                "session-1"
        );
    }

    private void assertRecordComponents(Class<?> recordType, String... names) {
        assertThat(recordType.isRecord()).isTrue();
        assertThat(Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly(names);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private enum FutureResultState {
        READY,
        PAUSED
    }

    private static final class DangerousObject {

        private final String text;
        private int toStringCalls;

        private DangerousObject(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            toStringCalls++;
            return text;
        }

        private int toStringCalls() {
            return toStringCalls;
        }
    }
}
