package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.agent.graph.DefaultCampaignAnalysisGraphExecutor;
import com.nageoffer.shortlink.agent.harness.api.AgentChatController;
import com.nageoffer.shortlink.agent.harness.runtime.DefaultAgentRunHarness;
import com.nageoffer.shortlink.agent.harness.security.InternalAgentApiFilter;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatRequest;
import com.nageoffer.shortlink.agent.infrastructure.llm.DeepSeekChatResponse;
import com.nageoffer.shortlink.agent.infrastructure.llm.LlmChatClient;
import com.nageoffer.shortlink.agent.infrastructure.persistence.JdbcGraphCheckpointStore;
import com.nageoffer.shortlink.agent.tool.registry.AgentToolRegistry;
import com.nageoffer.shortlink.agent.tool.shortlink.GetGroupAccessRecordsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.GetGroupStatsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.ListGroupsTool;
import com.nageoffer.shortlink.agent.tool.shortlink.PageShortLinksTool;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentChatE2eTest {

    @Test
    void chatRunsGraphToolsAdminInternalApiAndCheckpointEndToEnd() throws Exception {
        AgentProperties properties = agentProperties();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer adminServer = MockRestServiceServer.createServer(restTemplate);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(h2DataSource());
        JdbcGraphCheckpointStore checkpointStore = new JdbcGraphCheckpointStore(jdbcTemplate);
        CapturingLlmChatClient llmChatClient = new CapturingLlmChatClient();
        ShortLinkBusinessHttpGateway businessGateway = new ShortLinkBusinessHttpGateway(properties, restTemplate);
        DefaultCampaignAnalysisGraphExecutor graphExecutor = new DefaultCampaignAnalysisGraphExecutor(
                llmChatClient,
                checkpointStore,
                properties,
                new AgentToolRegistry(List.of(
                        new ListGroupsTool(businessGateway),
                        new PageShortLinksTool(businessGateway),
                        new GetGroupStatsTool(businessGateway),
                        new GetGroupAccessRecordsTool(businessGateway)
                ))
        );
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentChatController(new DefaultAgentRunHarness(graphExecutor)))
                .addFilters(new InternalAgentApiFilter(properties))
                .build();
        expectListGroups(adminServer);
        expectPageShortLinks(adminServer);
        expectGroupStats(adminServer);
        expectGroupAccessRecords(adminServer);

        MvcResult mvcResult = mockMvc.perform(post("/internal/short-link-agent/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Agent-Internal-Token", "internal-token")
                        .header("X-Agent-Username", "trusted-user")
                        .content("""
                                {
                                  "sessionId": "e2e-session-1",
                                  "username": "spoofed-user",
                                  "message": "show groups and link list and stats and access records gid=g1 startDate=2026-07-01 endDate=2026-07-07 current=1 size=10"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value("e2e-session-1"))
                .andExpect(jsonPath("$.data.answer").value("E2E campaign analysis answer"))
                .andExpect(jsonPath("$.data.toolCalls[0].name").value("list_groups"))
                .andExpect(jsonPath("$.data.toolCalls[1].name").value("page_short_links"))
                .andExpect(jsonPath("$.data.toolCalls[2].name").value("get_group_stats"))
                .andExpect(jsonPath("$.data.toolCalls[3].name").value("get_group_access_records"))
                .andExpect(jsonPath("$.data.cards[0].type").value("group_summary"))
                .andExpect(jsonPath("$.data.cards[1].type").value("short_link_page"))
                .andExpect(jsonPath("$.data.cards[2].type").value("stats_summary"))
                .andExpect(jsonPath("$.data.cards[3].type").value("access_records"))
                .andExpect(jsonPath("$.data.dataSources[0].type").value("graph"))
                .andExpect(jsonPath("$.data.dataSources[1].type").value("llm"))
                .andExpect(jsonPath("$.data.dataSources[2].type").value("tool"))
                .andExpect(jsonPath("$.data.traceEvents[0].nodeName").value("intake"))
                .andExpect(jsonPath("$.data.traceEvents[1].nodeName").value("tool_planning"))
                .andExpect(jsonPath("$.data.traceEvents[2].nodeName").value("llm_analysis"))
                .andExpect(jsonPath("$.data.traceEvents[3].nodeName").value("response_compose"))
                .andExpect(jsonPath("$.data.traceEvents[4].nodeName").value("checkpoint_save"))
                .andExpect(jsonPath("$.data.traceEvents[4].status").value("success"))
                .andExpect(jsonPath("$.data.traceEvents[4].checkpointVersion").exists())
                .andReturn();

        String responseJson = mvcResult.getResponse().getContentAsString();
        assertThat(responseJson)
                .contains("127.0.*.*")
                .doesNotContain("127.0.0.1")
                .doesNotContain("visitor-001")
                .doesNotContain("spoofed-user");
        assertThat(llmChatClient.request.messages().get(1).content())
                .contains("list_groups")
                .contains("get_group_access_records")
                .doesNotContain("127.0.0.1")
                .doesNotContain("visitor-001");
        assertCheckpoint(jdbcTemplate);
        adminServer.verify();
    }

    private AgentProperties agentProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSecurity().setInternalToken("internal-token");
        properties.getBusiness().setBaseUrl("http://admin.test");
        properties.getBusiness().setInternalToken("internal-token");
        return properties;
    }

    private DataSource h2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:agent_chat_e2e;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
        return dataSource;
    }

    private void expectListGroups(MockRestServiceServer adminServer) {
        adminServer.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/groups"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "trusted-user"))
                .andExpect(headerDoesNotExist("username"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "message": "success",
                          "data": [
                            {
                              "gid": "g1",
                              "name": "Campaign Group",
                              "shortLinkCount": 2
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectPageShortLinks(MockRestServiceServer adminServer) {
        adminServer.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/short-links/page?gid=g1&current=1&size=10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "trusted-user"))
                .andExpect(headerDoesNotExist("username"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "message": "success",
                          "data": {
                            "records": [
                              {
                                "gid": "g1",
                                "fullShortUrl": "nurl.ink/a",
                                "describe": "Launch campaign",
                                "todayPv": 42,
                                "totalPv": 120
                              }
                            ],
                            "total": 1,
                            "current": 1,
                            "size": 10
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectGroupStats(MockRestServiceServer adminServer) {
        adminServer.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/group/stats?gid=g1&startDate=2026-07-01&endDate=2026-07-07"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "trusted-user"))
                .andExpect(headerDoesNotExist("username"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "message": "success",
                          "data": {
                            "pv": 120,
                            "uv": 40,
                            "uip": 30,
                            "daily": [
                              {
                                "date": "2026-07-01",
                                "pv": 20
                              },
                              {
                                "date": "2026-07-02",
                                "pv": 100
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void expectGroupAccessRecords(MockRestServiceServer adminServer) {
        adminServer.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/group/access-records?gid=g1&startDate=2026-07-01&endDate=2026-07-07&current=1&size=10"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
                .andExpect(header("X-Agent-Username", "trusted-user"))
                .andExpect(headerDoesNotExist("username"))
                .andRespond(withSuccess("""
                        {
                          "code": "0",
                          "message": "success",
                          "data": {
                            "records": [
                              {
                                "ip": "127.0.0.1",
                                "user": "visitor-001",
                                "browser": "Chrome",
                                "network": "WiFi"
                              }
                            ],
                            "total": 1,
                            "current": 1,
                            "size": 10
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
    }

    private void assertCheckpoint(JdbcTemplate jdbcTemplate) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                        select thread_id,
                               trace_id,
                               graph_name,
                               graph_version,
                               checkpoint_json,
                               status
                        from t_agent_graph_checkpoint
                        where thread_id = ?
                        """,
                "e2e-session-1"
        );
        assertThat(row)
                .containsEntry("THREAD_ID", "e2e-session-1")
                .containsEntry("GRAPH_NAME", "campaign-analysis-graph")
                .containsEntry("GRAPH_VERSION", "v1")
                .containsEntry("STATUS", "FINISHED");
        assertThat(row.get("TRACE_ID")).isNotNull();
        assertThat(String.valueOf(row.get("CHECKPOINT_JSON")))
                .contains("\"toolExecutions\"")
                .contains("list_groups")
                .contains("page_short_links")
                .contains("get_group_stats")
                .contains("get_group_access_records")
                .contains("\"traceEvents\"")
                .doesNotContain("127.0.0.1")
                .doesNotContain("visitor-001");
    }

    private static class CapturingLlmChatClient implements LlmChatClient {

        private DeepSeekChatRequest request;

        @Override
        public DeepSeekChatResponse chat(DeepSeekChatRequest request) {
            this.request = request;
            return new DeepSeekChatResponse(
                    "chat-e2e",
                    "deepseek-v4-flash",
                    "E2E campaign analysis answer",
                    "stop",
                    new DeepSeekChatResponse.Usage(12, 8, 20)
            );
        }
    }
}
