package com.jupiter.shortlink.command.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

@Configuration
public class CommandDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource physicalDataSource(
            @Value("${shortlink.database.url}") String url,
            @Value("${shortlink.database.username}") String username,
            @Value("${shortlink.database.password}") String password) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(username);
        c.setPassword(password);
        c.setMaximumPoolSize(16);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(1500);
        c.setPoolName("command-business");
        c.addDataSourceProperty("rewriteBatchedStatements", "true");
        c.addDataSourceProperty("connectTimeout", "1500");
        c.addDataSourceProperty("socketTimeout", "5000");
        return new HikariDataSource(c);
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("physicalDataSource") DataSource raw)
            throws SQLException {
        ShardingRuleConfiguration rule = new ShardingRuleConfiguration();
        Properties props = new Properties();
        props.setProperty("sharding-count", "16");
        rule.getShardingAlgorithms().put("hash16", new AlgorithmConfiguration("HASH_MOD", props));
        for (String table : List.of("t_user", "t_group", "t_link")) {
            ShardingTableRuleConfiguration t =
                    new ShardingTableRuleConfiguration(table, "ds_0." + table + "_${0..15}");
            t.setTableShardingStrategy(
                    new StandardShardingStrategyConfiguration(
                            table.equals("t_link") ? "gid" : "username", "hash16"));
            rule.getTables().add(t);
        }
        Properties settings = new Properties();
        settings.setProperty("sql-show", "false");
        return ShardingSphereDataSourceFactory.createDataSource(
                Map.of("ds_0", raw), List.of(rule), settings);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(DataSource dataSource) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        return jdbc;
    }
}
