package com.jupiter.shortlink.agent.riskprofile;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/** Runs the complete repository contract against the explicitly isolated real MySQL schema. */
class RiskProfileRepositoryMySqlIntegrationTest extends RiskProfileRepositoryTest {
    @Override
    protected DataSource h2DataSource(String name) {
        String url = System.getenv("AGENT_TEST_MYSQL_URL");
        if (url == null
                || !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_agent_it(?:\\?.*)?"))
            throw new IllegalArgumentException(
                    "Real MySQL integration test requires isolated shortlink_agent_it on loopback");
        var source =
                new DriverManagerDataSource(
                        url,
                        System.getenv("AGENT_TEST_MYSQL_USER"),
                        System.getenv("AGENT_TEST_MYSQL_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql"))
                .execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM t_agent_short_link_risk_profile");
        jdbc.update("DELETE FROM t_agent_group_risk_profile");
        jdbc.update("DELETE FROM t_agent_risk_profile_batch");
        return source;
    }
}
