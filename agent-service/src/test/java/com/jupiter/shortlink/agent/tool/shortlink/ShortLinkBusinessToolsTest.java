package com.jupiter.shortlink.agent.tool.shortlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolDescriptor;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ShortLinkBusinessToolsTest {

    @Test
    void tooLargeStatisticsSubmitOneJobAndDoNotPollOrInventCompletedResults() {
        var gateway = org.mockito.Mockito.mock(ShortLinkBusinessGateway.class);
        org.mockito.Mockito.when(
                        gateway.get(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(ToolResult.failure("TOO_LARGE"));
        org.mockito.Mockito.when(
                        gateway.post(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(
                        ToolResult.success(
                                Map.of(
                                        "status",
                                        "PENDING",
                                        "jobId",
                                        "job-1",
                                        "resultReady",
                                        false)));
        var result =
                new GetGroupStatsTool(gateway)
                        .execute(
                                context(
                                        Map.of(
                                                "gid",
                                                "g1",
                                                "startDate",
                                                "2026-07-01",
                                                "endDate",
                                                "2026-07-07")));
        assertThat(result.data())
                .isEqualTo(Map.of("status", "PENDING", "jobId", "job-1", "resultReady", false));
        var request = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(gateway)
                .get(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.verify(gateway)
                .post(
                        org.mockito.ArgumentMatchers.eq(
                                "/internal/short-link-admin/v1/agent-tools/statistics/jobs"),
                        org.mockito.ArgumentMatchers.any(),
                        request.capture());
        assertThat(request.getValue())
                .containsEntry("queryKind", "METRICS")
                .containsKey("requestId")
                .doesNotContainKey("authVersion");
        org.mockito.Mockito.verifyNoMoreInteractions(gateway);
    }

    @Test
    void registersFiveReadOnlyBusinessTools() {
        CapturingGateway gateway = new CapturingGateway();
        AgentToolRegistry registry =
                new AgentToolRegistry(
                        List.of(
                                new ListGroupsTool(gateway),
                                new PageShortLinksTool(gateway),
                                new GetShortLinkStatsTool(gateway),
                                new GetGroupStatsTool(gateway),
                                new GetGroupAccessRecordsTool(gateway)));

        assertThat(registry.descriptors())
                .extracting(ToolDescriptor::name)
                .containsExactly(
                        "list_groups",
                        "page_short_links",
                        "get_short_link_stats",
                        "get_group_stats",
                        "get_group_access_records");
    }

    @Test
    void listGroupsCallsAdminInternalGroupsEndpoint() {
        CapturingGateway gateway = new CapturingGateway();
        ListGroupsTool tool = new ListGroupsTool(gateway);

        ToolResult result = tool.execute(context(Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(gateway.path).isEqualTo("/internal/short-link-admin/v1/agent-tools/groups");
        assertThat(gateway.queryParams).isEmpty();
        assertThat(gateway.context.username()).isEqualTo("zhangsan");
    }

    @Test
    void pageShortLinksRequiresGidAndAddsPaginationDefaults() {
        CapturingGateway gateway = new CapturingGateway();
        PageShortLinksTool tool = new PageShortLinksTool(gateway);

        ToolResult missingGid = tool.execute(context(Map.of()));

        assertThat(missingGid.success()).isFalse();
        assertThat(missingGid.message()).contains("gid");
        assertThat(gateway.calls).isZero();

        ToolResult result =
                tool.execute(
                        context(
                                Map.of(
                                        "gid", "g1",
                                        "orderTag", "todayPv")));

        assertThat(result.success()).isTrue();
        assertThat(gateway.path)
                .isEqualTo("/internal/short-link-admin/v1/agent-tools/short-links/page");
        assertThat(gateway.queryParams)
                .containsEntry("gid", "g1")
                .containsEntry("orderTag", "todayPv")
                .containsEntry("current", 1L)
                .containsEntry("size", 10L);
    }

    @Test
    void statsToolsBuildExpectedQueries() {
        CapturingGateway gateway = new CapturingGateway();

        new GetShortLinkStatsTool(gateway)
                .execute(
                        context(
                                Map.of(
                                        "fullShortUrl", "nurl.ink/a",
                                        "gid", "g1",
                                        "startDate", "2026-07-01",
                                        "endDate", "2026-07-07")));
        assertThat(gateway.path)
                .isEqualTo("/internal/short-link-admin/v1/agent-tools/short-link/stats");
        assertThat(gateway.queryParams)
                .containsEntry("fullShortUrl", "nurl.ink/a")
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07");

        new GetGroupStatsTool(gateway)
                .execute(
                        context(
                                Map.of(
                                        "gid", "g1",
                                        "startDate", "2026-07-01",
                                        "endDate", "2026-07-07")));
        assertThat(gateway.path).isEqualTo("/internal/short-link-admin/v1/agent-tools/group/stats");
        assertThat(gateway.queryParams)
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07");

        new GetGroupAccessRecordsTool(gateway)
                .execute(
                        context(
                                Map.of(
                                        "gid", "g1",
                                        "startDate", "2026-07-01",
                                        "endDate", "2026-07-07")));
        assertThat(gateway.path)
                .isEqualTo("/internal/short-link-admin/v1/agent-tools/group/access-records");
        assertThat(gateway.queryParams)
                .containsEntry("gid", "g1")
                .containsEntry("startDate", "2026-07-01")
                .containsEntry("endDate", "2026-07-07")
                .containsEntry("current", 1L)
                .containsEntry("size", 10L);
    }

    private ToolContext context(Map<String, Object> arguments) {
        return new ToolContext("session-1", "zhangsan", arguments);
    }

    private static class CapturingGateway implements ShortLinkBusinessGateway {

        private String path;

        private ToolContext context;

        private Map<String, Object> queryParams = Map.of();

        private int calls;

        @Override
        public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
            this.path = path;
            this.context = context;
            this.queryParams = queryParams;
            this.calls++;
            return ToolResult.success(Map.of("path", path));
        }
    }
}
