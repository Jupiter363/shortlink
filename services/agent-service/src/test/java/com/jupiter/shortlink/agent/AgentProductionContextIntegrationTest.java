package com.jupiter.shortlink.agent;

import static org.assertj.core.api.Assertions.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.graph.DefaultCampaignAnalysisGraphExecutor;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.source.ShortLinkBusinessRiskStatsGateway;
import com.jupiter.shortlink.agent.securityriskagent.graph.DefaultSecurityRiskGraphExecutor;
import com.jupiter.shortlink.agent.tool.registry.AgentToolRegistry;
import com.sun.net.httpserver.HttpServer;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Full production-profile application wiring with real MySQL and a local empty authority scope;
 * never invokes a model.
 */
class AgentProductionContextIntegrationTest {
    @Test
    void productionApplicationStartsWithAllToolsGraphsAndBoundedTomcat() throws Exception {
        String url = System.getenv("AGENT_TEST_MYSQL_URL");
        if (url == null
                || !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_agent_it(?:\\?.*)?"))
            throw new IllegalArgumentException(
                    "Full application integration requires isolated shortlink_agent_it");
        var dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        url,
                        System.getenv("AGENT_TEST_MYSQL_USER"),
                        System.getenv("AGENT_TEST_MYSQL_PASSWORD"));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                        new org.springframework.core.io.ClassPathResource(
                                "sql/agent_service_schema.sql"))
                .execute(dataSource);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM t_agent_risk_profile_batch");
        jdbc.update("DELETE FROM t_agent_risk_analysis_job");
        var authority = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authority.createContext(
                "/internal/short-link-admin/v1/agent-tools/authorization/resolve",
                exchange -> {
                    byte[] response =
                            "{\"code\":\"0\",\"data\":{\"tenantId\":\"1001\",\"ownershipVersion\":\"context-v1\",\"links\":[]}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getRequestBody().close();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response);
                    }
                });
        authority.start();
        try (var context =
                new SpringApplicationBuilder(ShortLinkAgentApplication.class)
                        .run(
                                "--spring.profiles.active=production",
                                "--spring.config.location=classpath:application-production.properties",
                                "--server.port=0",
                                "--management.server.port=0",
                                "--spring.datasource.url=" + url,
                                "--spring.datasource.username="
                                        + System.getenv("AGENT_TEST_MYSQL_USER"),
                                "--spring.datasource.password="
                                        + System.getenv("AGENT_TEST_MYSQL_PASSWORD"),
                                "--spring.sql.init.mode=always",
                                "--spring.sql.init.schema-locations=classpath:sql/agent_service_schema.sql",
                                "--spring.data.redis.host=127.0.0.1",
                                "--spring.data.redis.port=65534",
                                "--spring.data.redis.password=unused-test-only",
                                "--management.health.redis.enabled=false",
                                "--short-link.agent.business.base-url=http://127.0.0.1:"
                                        + authority.getAddress().getPort(),
                                "--short-link.agent.business.username=zhangsan",
                                "--short-link.agent.business.internal-token="
                                        + StatsTestFixtures.SECRET,
                                "--short-link.agent.security.internal-token="
                                        + StatsTestFixtures.SECRET,
                                "--short-link.agent.deepseek.api-key=",
                                "--short-link.agent.deepseek.base-url=http://127.0.0.1:65533",
                                "--short-link.agent.deepseek.model=test-unused",
                                "--short-link.agent.risk.analysis.worker-interval-millis=3600000",
                                "--short-link.agent.risk.profile.schedule-cron=-")) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBean(AgentToolRegistry.class).descriptors())
                    .extracting(value -> value.name())
                    .containsExactlyInAnyOrder(
                            "list_groups",
                            "page_short_links",
                            "get_short_link_stats",
                            "get_group_stats",
                            "get_group_access_records",
                            "submit_statistics_query_job",
                            "get_statistics_query_job",
                            "get_statistics_query_job_page");
            assertThat(context.getBean(DefaultCampaignAnalysisGraphExecutor.class)).isNotNull();
            assertThat(context.getBean(DefaultSecurityRiskGraphExecutor.class)).isNotNull();
            assertThat(context.getBean(RiskPolicyService.class)).isNotNull();
            assertThat(context.getBean(ShortLinkBusinessRiskStatsGateway.class)).isNotNull();
            var server =
                    (TomcatWebServer) ((ServletWebServerApplicationContext) context).getWebServer();
            var protocol =
                    (AbstractHttp11Protocol<?>)
                            server.getTomcat().getConnector().getProtocolHandler();
            assertThat(protocol.getDisableUploadTimeout()).isFalse();
            assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(5_000);
            assertThat(protocol.getMaxSwallowSize()).isZero();
        } finally {
            authority.stop(0);
        }
    }
}
