package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.harness.tool.ToolContext;
import com.nageoffer.shortlink.agent.harness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ShortLinkBusinessHttpGatewayTest {

    @Test
    void getCallsAdminInternalToolApiWithTrustedHeadersAndUnwrapsData() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://admin.test/");
        properties.getBusiness().setInternalToken("internal-token");
        ShortLinkBusinessHttpGateway gateway = new ShortLinkBusinessHttpGateway(properties, restTemplate);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("gid", "g1");
        queryParams.put("current", 1L);

        server.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/short-links/page?gid=g1&current=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andExpect(header("X-Agent-Internal-Token", "internal-token"))
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
                                "todayPv": 42
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = gateway.get(
                "/internal/short-link-admin/v1/agent-tools/short-links/page",
                new ToolContext("session-1", "zhangsan", Map.of()),
                queryParams
        );

        assertThat(result.success()).isTrue();
        assertThat(result.data().toString()).contains("nurl.ink/a");
        assertThat(result.message()).isNull();
        server.verify();
    }

    @Test
    void getConvertsBusinessErrorToToolFailure() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://admin.test");
        ShortLinkBusinessHttpGateway gateway = new ShortLinkBusinessHttpGateway(properties, restTemplate);

        server.expect(requestTo("http://admin.test/internal/short-link-admin/v1/agent-tools/groups"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Agent-Username", "zhangsan"))
                .andExpect(headerDoesNotExist("X-Agent-Internal-Token"))
                .andRespond(withSuccess("""
                        {
                          "code": "B0001",
                          "message": "no permission",
                          "data": null
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = gateway.get(
                "/internal/short-link-admin/v1/agent-tools/groups",
                new ToolContext("session-1", "zhangsan", Map.of()),
                Map.of()
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no permission");
        server.verify();
    }
}
