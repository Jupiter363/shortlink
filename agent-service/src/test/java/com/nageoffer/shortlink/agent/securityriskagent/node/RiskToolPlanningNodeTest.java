package com.nageoffer.shortlink.agent.securityriskagent.node;

import com.nageoffer.shortlink.agent.harness.tool.AgentTool;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolDescriptor;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskHashService;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.securityriskagent.model.ProfileRiskAnalysisContext;
import com.nageoffer.shortlink.agent.securityriskagent.safety.RiskToolStateSanitizer;
import com.nageoffer.shortlink.agent.securityriskagent.safety.SecurityRiskSanitizer;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskToolPlanningNodeTest {

    private static final String HASH_SALT = "risk-test-salt";
    private static final String RAW_IP = "192.0.2.44";

    @Test
    void planAndExecuteRunsShortLinkStatsAndAccessRecordsWithTypedArguments() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of("pv", 10))
        );
        CapturingAgentTool recordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(Map.of("total", 1))
        );
        RiskToolPlanningNode node = node(List.of(statsTool, recordsTool));

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 fullShortUrl=http://s.com/a startDate=2026-07-01 endDate=2026-07-07 access current=2 size=5",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("visitedNodes")).isEqualTo(List.of("intake", "risk_tool_planning"));
        assertThat(output.get("toolWarnings")).isEqualTo(List.of());
        assertThat(output.get("evidenceStatus")).isEqualTo("AVAILABLE");
        assertThat(output.get("toolExecutions").toString())
                .contains("get_short_link_stats")
                .contains("get_group_access_records");
        assertThat(statsTool.context.username()).isEqualTo("zhangsan");
        assertThat(statsTool.context.arguments())
                .containsEntry("gid", "g1")
                .containsEntry("fullShortUrl", "http://s.com/a")
                .containsEntry("current", 2L)
                .containsEntry("size", 5L);
        assertThat(recordsTool.context.arguments())
                .containsEntry("current", 2L)
                .containsEntry("size", 5L);
    }

    @Test
    void planAndExecuteDoesNotCallToolsWithoutGidAndDateRange() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 10))
        );
        RiskToolPlanningNode node = node(List.of(statsTool));

        Map<String, Object> output = node.planAndExecute("risk gid=g1", "session-1", "zhangsan");

        assertThat(output.get("toolExecutions")).isEqualTo(List.of());
        assertThat(output.get("evidenceRequested")).isEqualTo(false);
        assertThat(output.get("evidenceStatus")).isEqualTo("NOT_REQUESTED");
        assertThat(statsTool.context).isNull();
    }

    @Test
    void planAndExecuteRecordsFailedExecutionWhenPlannedToolIsNotRegistered() {
        RiskToolPlanningNode node = node(List.of());

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat((List<?>) output.get("toolExecutions"))
                .singleElement()
                .satisfies(execution -> {
                    Map<?, ?> executionMap = (Map<?, ?>) execution;
                    assertThat(executionMap.get("name")).isEqualTo("get_group_stats");
                    assertThat(executionMap.get("success")).isEqualTo(false);
                    assertThat(executionMap.get("message")).isEqualTo("Agent tool is not registered");
                });
        assertThat(output.get("toolWarnings").toString())
                .contains("Agent tool get_group_stats failed")
                .contains("Agent tool is not registered");
        assertThat(output.get("evidenceStatus")).isEqualTo("SOURCE_FAILURE");
    }

    @ParameterizedTest
    @MethodSource("emptyToolData")
    void planAndExecutePreservesSuccessfulEmptyToolDataAsNoDataEvidence(Object emptyData) {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(emptyData)
        );
        RiskToolPlanningNode node = node(List.of(statsTool));

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat((List<?>) output.get("toolExecutions"))
                .singleElement()
                .satisfies(execution -> {
                    Map<?, ?> executionMap = (Map<?, ?>) execution;
                    assertThat(executionMap.get("name")).isEqualTo("get_group_stats");
                    assertThat(executionMap.get("success")).isEqualTo(true);
                    assertEmptyDataSemantics(executionMap.get("data"), emptyData);
                    assertThat(executionMap.containsKey("message")).isFalse();
                });
        assertThat(output.get("toolWarnings")).isEqualTo(List.of());
        assertThat(output.get("evidenceStatus")).isEqualTo("NO_DATA");
    }

    @Test
    void planAndExecuteSanitizesToolFailureMessages() {
        ThrowingAgentTool statsTool = new ThrowingAgentTool(
                "get_group_stats",
                "backend failed ip=192.168.1.10 user=visitor-001 token=abc"
        );
        RiskToolPlanningNode node = node(List.of(statsTool));

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("toolExecutions").toString())
                .contains("Agent tool execution failed")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
        assertThat(output.get("toolWarnings").toString())
                .contains("Agent tool get_group_stats failed")
                .contains("Agent tool execution failed")
                .doesNotContain("192.168.1.10")
                .doesNotContain("visitor-001")
                .doesNotContain("abc");
    }

    @Test
    void constructorRequiresBothTextAndToolStateSanitizers() {
        assertThat(RiskToolPlanningNode.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes()).containsExactly(
                        AgentToolRegistry.class,
                        SecurityRiskSanitizer.class,
                        RiskToolStateSanitizer.class
                ));
    }

    @Test
    void retainsExplicitManualDirectiveAlongsideToolQueryEnvelope() {
        RiskToolPlanningNode node = node(List.of());

        Map<String, Object> output = node.planAndExecute(
                "review gid=g1 fullShortUrl=nurl.ink/a "
                        + "action=LIMIT_TIME_WINDOW timezone=Asia/Shanghai "
                        + "allowedWindows=09:00-18:00",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("manualActionDirective"))
                .isEqualTo(new RiskManualActionDirective(
                        RiskPolicyAction.LIMIT_TIME_WINDOW,
                        "Asia/Shanghai",
                        List.of("09:00-18:00")
                ));
        assertThat(output.toString()).doesNotContain("rawIp");
    }

    @Test
    void normalRiskQueryDoesNotInvokeStrictManualDirectiveParser() {
        Map<String, Object> output = node(List.of()).planAndExecute(
                "analyze security risk gid=g1",
                "session-1",
                "zhangsan"
        );

        assertThat(output).doesNotContainKey("manualActionDirective");
    }

    @Test
    void explicitManualDirectiveRejectsRawIpEvenInsideToolQueryEnvelope() {
        assertThatThrownBy(() -> node(List.of()).planAndExecute(
                "gid=g1 action=BLOCK_IP rawIp=" + RAW_IP,
                "session-1",
                "zhangsan"
        ))
                .isInstanceOf(
                        com.nageoffer.shortlink.agent.harness.action.service.AgentActionException.class
                )
                .hasMessage("Risk manual action directive payload is invalid");
    }

    @Test
    void explicitManualDirectiveDoesNotDiscardMalformedDirectiveToken() {
        assertThatThrownBy(() -> node(List.of()).planAndExecute(
                "gid=g1 action=DISABLE_SHORT_LINK timezone",
                "session-1",
                "zhangsan"
        ))
                .isInstanceOf(
                        com.nageoffer.shortlink.agent.harness.action.service.AgentActionException.class
                )
                .hasMessage("Risk manual action directive payload is invalid");
    }

    @Test
    void sanitizesTopIpRowsAndSensitiveNestedDataBeforeWritingToolExecutions() {
        Map<String, Object> topIpRow = new LinkedHashMap<>();
        topIpRow.put("ip", RAW_IP);
        topIpRow.put("cnt", 45);
        topIpRow.put("user", "visitor-001");
        topIpRow.put("rawIp", "198.51.100.8");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("ip", "203.0.113.9");
        nested.put("visitor", "visitor-002");
        nested.put("token", "internal-secret");
        nested.put("userCount", 17);
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("pv", 100);
        sourceData.put("userCount", 25);
        sourceData.put("topIpStats", new ArrayList<>(List.of(topIpRow)));
        sourceData.put("nested", nested);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(sourceData)
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 fullShortUrl=nurl.ink/a startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        Map<String, Object> data = executionData(output);
        Map<String, Object> safeRow = firstMap(data.get("topIpStats"));
        assertThat(safeRow)
                .containsOnlyKeys("ipHash", "maskedIp", "cnt")
                .containsEntry("ipHash", new RiskHashService(HASH_SALT).sha256(RAW_IP))
                .containsEntry("maskedIp", "192.0.*.*")
                .containsEntry("cnt", 45);
        assertThat(data).containsEntry("userCount", 25);
        assertThat(map(data.get("nested")))
                .containsEntry("maskedIp", "203.0.*.*")
                .containsEntry("userCount", 17)
                .doesNotContainKeys("ip", "visitor", "token");
        assertThat(data.toString())
                .doesNotContain(RAW_IP, "198.51.100.8", "203.0.113.9", "visitor-001", "visitor-002", "internal-secret");
    }

    @Test
    void semanticSensitiveKeysAreRemovedWhileExplicitAggregateCountsRemain() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("userProfile", Map.of("name", "private-user"));
        nested.put("visitorUuid", "visitor-uuid-001");
        nested.put("clientSecret", "client-secret-001");
        nested.put("dbCredential", "db-password-001");
        nested.put("sessionToken", "session-token-001");
        nested.put("userCount", 17);
        nested.put("visitorCount", 9);
        nested.put("newUserCount", 5);
        nested.put("accountCount", 3);
        nested.put("userCount24h", 12);
        nested.put("topVisitorShare", 0.75D);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("nested", nested))
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(map(executionData(output).get("nested")))
                .containsOnly(
                        Map.entry("userCount", 17),
                        Map.entry("visitorCount", 9),
                        Map.entry("newUserCount", 5),
                        Map.entry("accountCount", 3),
                        Map.entry("userCount24h", 12),
                        Map.entry("topVisitorShare", 0.75D)
                );
        assertThat(output.toString()).doesNotContain(
                "private-user", "visitor-uuid-001", "client-secret-001",
                "db-password-001", "session-token-001"
        );
    }

    @Test
    void mutableAndUnknownScalarValuesAreDetachedAndSanitizedBeforeStateWrite() {
        StringBuilder mutableText = new StringBuilder("user=visitor-001 source 2001:db8::44");
        AtomicInteger mutableNumber = new AtomicInteger(7);
        MutableScalar mutableCustom = new MutableScalar("token=private-token source 198.51.100.8");
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("description", mutableText);
        sourceData.put("counter", mutableNumber);
        sourceData.put("customValue", mutableCustom);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(sourceData)
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );
        Map<String, Object> stateData = executionData(output);

        mutableText.append(" mutated");
        mutableNumber.set(99);
        mutableCustom.value = "token=changed source 203.0.113.9";

        assertThat(stateData.get("description")).isInstanceOf(String.class);
        assertThat(stateData.get("counter")).isEqualTo("7");
        assertThat(stateData.get("customValue")).isInstanceOf(String.class);
        assertThat(stateData.toString())
                .doesNotContain("visitor-001", "2001:db8::44", "private-token", "198.51.100.8")
                .doesNotContain("mutated", "99", "changed", "203.0.113.9");
    }

    @Test
    void preservesAlreadySafeTopIpRowsIdempotently() {
        Map<String, Object> safeRow = Map.of(
                "ipHash", "a".repeat(64),
                "maskedIp", "192.0.*.*",
                "cnt", 45
        );
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("pv", 100, "topIpStats", List.of(safeRow)))
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(firstMap(executionData(output).get("topIpStats"))).isEqualTo(safeRow);
    }

    @Test
    void rejectsForgedOrMalformedMaskedIpRowsBeforeStateWrite() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("topIpStats", List.of(
                        Map.of("ipHash", "a".repeat(64), "maskedIp", "2001:db8::44", "cnt", 45),
                        Map.of("ipHash", "b".repeat(64), "maskedIp", "not-a-mask", "cnt", 44)
                )))
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(executionData(output).get("topIpStats")).isEqualTo(List.of());
        assertThat(output.toString()).doesNotContain("2001:db8::44", "not-a-mask");
    }

    @Test
    void sanitizesRawIpv6TopIpRowsIntoHashAndStrictMaskedEvidence() {
        String rawIpv6 = "2001:db8::44";
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of(
                        "topIpStats", List.of(Map.of("ip", rawIpv6, "cnt", 45))
                ))
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(firstMap(executionData(output).get("topIpStats")))
                .containsOnlyKeys("ipHash", "maskedIp", "cnt")
                .containsEntry("ipHash", new RiskHashService(HASH_SALT).sha256(rawIpv6))
                .containsEntry("maskedIp", "2001:db8:*:*")
                .containsEntry("cnt", 45);
        assertThat(output.toString()).doesNotContain(rawIpv6);
    }

    @Test
    void accessRecordToolKeepsOnlySafePaginationAndAggregateFields() {
        Map<String, Object> recordsData = new LinkedHashMap<>();
        recordsData.put("total", 20L);
        recordsData.put("current", 2L);
        recordsData.put("size", 10L);
        recordsData.put("pages", 2L);
        recordsData.put("recordCount", 20L);
        recordsData.put("records", List.of(Map.of("ip", RAW_IP, "user", "visitor-001")));
        recordsData.put("rows", List.of(Map.of("token", "secret")));
        recordsData.put("rawData", Map.of("ip", "198.51.100.8"));
        recordsData.put("details", List.of("private-detail"));
        CapturingAgentTool recordsTool = new CapturingAgentTool(
                "get_group_access_records",
                ToolResult.success(recordsData)
        );
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of())
        );

        Map<String, Object> output = node(List.of(statsTool, recordsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07 access",
                "session-1",
                "zhangsan"
        );

        Map<String, Object> data = executionData(output, "get_group_access_records");
        assertThat(data).containsOnly(
                Map.entry("total", 20L),
                Map.entry("current", 2L),
                Map.entry("size", 10L),
                Map.entry("pages", 2L),
                Map.entry("recordCount", 20L)
        );
        assertThat(data.toString())
                .doesNotContain("records", "rows", "rawData", "details", RAW_IP, "visitor-001", "secret");
    }

    @Test
    void sanitizationDeepCopiesMutableMapsListsCollectionsAndArrays() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ip", RAW_IP);
        row.put("cnt", 45);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        Map<String, Object> sourceData = new LinkedHashMap<>();
        sourceData.put("pv", 100);
        sourceData.put("topIpStats", rows);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(sourceData)
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );
        Map<String, Object> stateData = executionData(output);

        row.put("ip", "198.51.100.8");
        row.put("cnt", 999);
        rows.clear();
        sourceData.put("pv", 999);

        assertThat(stateData).containsEntry("pv", 100);
        assertThat(firstMap(stateData.get("topIpStats")))
                .containsEntry("maskedIp", "192.0.*.*")
                .containsEntry("cnt", 45);

        RiskToolStateSanitizer toolSanitizer = toolSanitizer(HASH_SALT);
        Object[] sourceArray = new Object[]{Map.of("ip", RAW_IP), List.of(Map.of("uid", "u-001"))};
        Object sanitizedArray = toolSanitizer.sanitize("other_tool", sourceArray);
        Set<Object> sourceCollection = Set.of(Map.of("ip", "203.0.113.9"));
        Object sanitizedCollection = toolSanitizer.sanitize("other_tool", sourceCollection);

        assertThat(sanitizedArray).isInstanceOf(List.class).isNotSameAs(sourceArray);
        assertThat((List<?>) sanitizedArray).hasSize(2);
        assertThat(((List<?>) sanitizedArray).get(0).toString())
                .contains("maskedIp=192.0.*.*")
                .doesNotContain(RAW_IP);
        assertThatThrownBy(() -> ((List<Object>) sanitizedArray).add("unsafe"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(sanitizedCollection).isInstanceOf(Collection.class).isNotSameAs(sourceCollection);
        assertThat(sanitizedCollection.toString())
                .contains("maskedIp=203.0.*.*")
                .doesNotContain("203.0.113.9");
    }

    @Test
    void missingHashSaltFailsClosedWithoutWritingRawToolData() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of(
                        "pv", 100,
                        "topIpStats", List.of(Map.of("ip", RAW_IP, "cnt", 45))
                ))
        );
        SecurityRiskSanitizer textSanitizer = new SecurityRiskSanitizer();
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool)),
                textSanitizer,
                new RiskToolStateSanitizer(new RiskHashService(""), textSanitizer)
        );

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat((List<?>) output.get("toolExecutions")).singleElement().satisfies(item -> {
            Map<?, ?> execution = (Map<?, ?>) item;
            assertThat(execution.get("success")).isEqualTo(false);
            assertThat(execution.get("message")).isEqualTo("Agent tool execution failed");
            assertThat(execution.containsKey("data")).isFalse();
            assertThat(execution.toString()).doesNotContain(RAW_IP);
        });
        assertThat(output.toString()).doesNotContain(RAW_IP, "Risk hash salt must be configured");
    }

    @Test
    void failedExecutionPersistsSanitizedArgumentsButToolReceivesOriginalArguments() {
        String rawUrl = "http://203.0.113.8/a";
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_short_link_stats",
                ToolResult.success(Map.of(
                        "topIpStats", List.of(Map.of("ip", "198.51.100.8", "cnt", 45))
                ))
        );
        SecurityRiskSanitizer textSanitizer = new SecurityRiskSanitizer();
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool)),
                textSanitizer,
                new RiskToolStateSanitizer(new RiskHashService(""), textSanitizer)
        );

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 fullShortUrl=" + rawUrl + " startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(statsTool.context.arguments()).containsEntry("fullShortUrl", rawUrl);
        assertThat(output.get("toolExecutions").toString())
                .contains("success=false")
                .doesNotContain("203.0.113.8", "198.51.100.8");
    }

    @Test
    void profileContextExecutionPassesThroughTheSameStateSanitizerBoundary() {
        String rawIp = "198.51.100.8";
        ShortLinkRiskProfile profile = new ShortLinkRiskProfile(
                "g1",
                rawIp,
                "abc123",
                "http://" + rawIp + "/abc123",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 7, 0, 0),
                new ShortLinkRiskMetrics(1, 1, 1, 1, 1, 1, null, null, null, null, null, null, null, null, null),
                50,
                50,
                RiskLevel.MEDIUM,
                Set.of(RiskReasonCode.TRAFFIC_SPIKE),
                RiskWatchStatus.WATCHING,
                List.of(),
                "source " + rawIp + " token=profile-secret",
                "batch-001"
        );
        ProfileRiskAnalysisContext profileContext = new ProfileRiskAnalysisContext("g1", null, List.of(profile));

        Map<String, Object> output = node(List.of()).planAndExecute(
                "analyze profile",
                "session-1",
                "zhangsan",
                profileContext
        );

        assertThat(profile.fullShortUrl()).contains(rawIp);
        assertThat(output.get("toolExecutions").toString())
                .contains("risk_profile_context")
                .doesNotContain(rawIp, "profile-secret");
    }

    @Test
    void sanitizeFailureRemovesAnyDataAddedBeforeTheException() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("topIpStats", List.of(Map.of("ip", RAW_IP, "cnt", 1))))
        );
        RiskToolStateSanitizer throwingSanitizer = new RiskToolStateSanitizer(
                new RiskHashService(HASH_SALT),
                new SecurityRiskSanitizer()
        ) {
            @Override
            public Object sanitize(String toolName, Object data) {
                throw new IllegalStateException("unsafe " + RAW_IP);
            }
        };
        RiskToolPlanningNode node = new RiskToolPlanningNode(
                new AgentToolRegistry(List.of(statsTool)),
                new SecurityRiskSanitizer(),
                throwingSanitizer
        );

        Map<String, Object> output = node.planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertThat(output.get("toolExecutions").toString())
                .contains("success=false", "Agent tool execution failed")
                .doesNotContain("data=", RAW_IP, "unsafe");
    }

    @Test
    void cyclicToolDataFailsClosedWithoutOverflowingTheGraphExecution() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("pv", 100);
        cyclic.put("self", cyclic);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(cyclic)
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertFailedSafeExecution(output);
    }

    @Test
    void deeplyNestedToolDataFailsClosedAtTheSanitizationBoundary() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cursor = root;
        for (int depth = 0; depth < 40; depth++) {
            Map<String, Object> child = new LinkedHashMap<>();
            cursor.put("child", child);
            cursor = child;
        }
        cursor.put("value", RAW_IP);
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(root)
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertFailedSafeExecution(output);
    }

    @Test
    void oversizedToolCollectionsFailClosedBeforeStateAllocation() {
        CapturingAgentTool statsTool = new CapturingAgentTool(
                "get_group_stats",
                ToolResult.success(Map.of("samples", java.util.Collections.nCopies(1_025, 1)))
        );

        Map<String, Object> output = node(List.of(statsTool)).planAndExecute(
                "risk gid=g1 startDate=2026-07-01 endDate=2026-07-07",
                "session-1",
                "zhangsan"
        );

        assertFailedSafeExecution(output);
    }

    private static Stream<Arguments> emptyToolData() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(Map.of()),
                Arguments.of(List.of()),
                Arguments.of("   "),
                Arguments.of((Object) new Object[0])
        );
    }

    private RiskToolPlanningNode node(List<AgentTool> tools) {
        SecurityRiskSanitizer textSanitizer = new SecurityRiskSanitizer();
        return new RiskToolPlanningNode(
                new AgentToolRegistry(tools),
                textSanitizer,
                new RiskToolStateSanitizer(new RiskHashService(HASH_SALT), textSanitizer)
        );
    }

    private RiskToolStateSanitizer toolSanitizer(String salt) {
        SecurityRiskSanitizer textSanitizer = new SecurityRiskSanitizer();
        return new RiskToolStateSanitizer(new RiskHashService(salt), textSanitizer);
    }

    private void assertFailedSafeExecution(Map<String, Object> output) {
        assertThat((List<?>) output.get("toolExecutions")).singleElement().satisfies(item -> {
            Map<?, ?> execution = (Map<?, ?>) item;
            assertThat(execution.get("success")).isEqualTo(false);
            assertThat(execution.get("message")).isEqualTo("Agent tool execution failed");
            assertThat(execution.containsKey("data")).isFalse();
        });
        assertThat(output.toString()).doesNotContain(RAW_IP, "sanitization", "limit");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executionData(Map<String, Object> output) {
        Map<String, Object> execution = (Map<String, Object>) ((List<?>) output.get("toolExecutions")).get(0);
        assertThat(execution).containsEntry("success", true);
        return (Map<String, Object>) execution.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executionData(Map<String, Object> output, String toolName) {
        Map<String, Object> execution = ((List<?>) output.get("toolExecutions")).stream()
                .map(item -> (Map<String, Object>) item)
                .filter(item -> toolName.equals(item.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(execution).containsEntry("success", true);
        return (Map<String, Object>) execution.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object value) {
        return (Map<String, Object>) ((List<?>) value).get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertEmptyDataSemantics(Object actual, Object expected) {
        if (expected == null) {
            assertThat(actual).isNull();
            return;
        }
        if (expected instanceof Map<?, ?>) {
            assertThat(actual).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) actual).isEmpty();
            return;
        }
        if (expected instanceof Collection<?>) {
            assertThat(actual).isInstanceOf(Collection.class);
            assertThat((Collection<?>) actual).isEmpty();
            return;
        }
        if (expected instanceof CharSequence) {
            assertThat(String.valueOf(actual)).isBlank();
            return;
        }
        if (expected.getClass().isArray()) {
            assertThat(actual).isInstanceOf(Collection.class);
            assertThat((Collection<?>) actual).isEmpty();
            return;
        }
        assertThat(actual).isEqualTo(expected);
    }

    private static class CapturingAgentTool implements AgentTool {

        private final String name;

        private final ToolResult result;

        private ToolContext context;

        private CapturingAgentTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            this.context = context;
            return result;
        }
    }

    private static class ThrowingAgentTool implements AgentTool {

        private final String name;

        private final String message;

        private ThrowingAgentTool(String name, String message) {
            this.name = name;
            this.message = message;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, "throwing test tool", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolContext context) {
            throw new IllegalStateException(message);
        }
    }

    private static final class MutableScalar {

        private String value;

        private MutableScalar(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
