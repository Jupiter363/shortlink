package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.harness.runtime.AgentRunResult;
import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ShortLinkBusinessHttpGatewayTest {

    @Test
    void getCallsAggregationWithUsernameHeaderAndUnwrapsData() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        AgentProperties properties = new AgentProperties();
        properties.getBusiness().setBaseUrl("http://aggregation.test/");
        ShortLinkBusinessHttpGateway gateway = new ShortLinkBusinessHttpGateway(properties, restTemplate);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("gid", "g1");
        queryParams.put("current", 1L);

        server.expect(requestTo("http://aggregation.test/api/short-link/admin/v1/page?gid=g1&current=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("username", "zhangsan"))
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
                "/api/short-link/admin/v1/page",
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
        properties.getBusiness().setBaseUrl("http://aggregation.test");
        ShortLinkBusinessHttpGateway gateway = new ShortLinkBusinessHttpGateway(properties, restTemplate);

        server.expect(requestTo("http://aggregation.test/api/short-link/admin/v1/group"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "code": "B0001",
                          "message": "no permission",
                          "data": null
                        }
                        """, MediaType.APPLICATION_JSON));

        ToolResult result = gateway.get(
                "/api/short-link/admin/v1/group",
                new ToolContext("session-1", "zhangsan", Map.of()),
                Map.of()
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("no permission");
        server.verify();
    }
}
