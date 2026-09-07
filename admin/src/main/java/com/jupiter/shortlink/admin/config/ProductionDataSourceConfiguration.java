package com.jupiter.shortlink.admin.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.encrypt.api.config.EncryptRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.rule.EncryptColumnRuleConfiguration;
import org.apache.shardingsphere.encrypt.api.config.rule.EncryptTableRuleConfiguration;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

/** Account writes and initialization intents use the same physical MySQL transaction. */
@Configuration
@Profile("production")
public class ProductionDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource accountPhysicalDataSource(
            @Value("${shortlink.database.url}") String url,
            @Value("${shortlink.database.username}") String username,
            @Value("${shortlink.database.password}") String password) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(username);
        c.setPassword(password);
        c.setMaximumPoolSize(12);
        c.setMinimumIdle(0);
        c.setConnectionTimeout(1500);
        c.setPoolName("admin-account");
        c.addDataSourceProperty("connectTimeout", "1500");
        c.addDataSourceProperty("socketTimeout", "5000");
        return new HikariDataSource(c);
    }

    @Bean
    @Primary
    DataSource dataSource(
            @Qualifier("accountPhysicalDataSource") DataSource physical,
            @Value("${shortlink.account.pii-aes-key}") String key)
            throws SQLException {
        if (key.length() < 32)
            throw new IllegalArgumentException(
                    "Account encryption key must have at least 32 characters");
        ShardingRuleConfiguration sharding = new ShardingRuleConfiguration();
        Properties hash = new Properties();
        hash.setProperty("sharding-count", "16");
        sharding.getShardingAlgorithms()
                .put("hash16", new AlgorithmConfiguration("HASH_MOD", hash));
        for (String name : List.of("t_user", "t_group")) {
            var table = new ShardingTableRuleConfiguration(name, "ds_0." + name + "_${0..15}");
            table.setTableShardingStrategy(
                    new StandardShardingStrategyConfiguration("username", "hash16"));
            sharding.getTables().add(table);
        }
        Properties aes = new Properties();
        aes.setProperty("aes-key-value", key);
        List<EncryptColumnRuleConfiguration> columns =
                List.of(
                        new EncryptColumnRuleConfiguration(
                                "phone", "phone", null, null, null, "pii", null, null, true),
                        new EncryptColumnRuleConfiguration(
                                "mail", "mail", null, null, null, "pii", null, null, true));
        var encryption =
                new EncryptRuleConfiguration(
                        List.of(new EncryptTableRuleConfiguration("t_user", columns, true)),
                        Map.of("pii", new AlgorithmConfiguration("AES", aes)),
                        true);
        return ShardingSphereDataSourceFactory.createDataSource(
                Map.of("ds_0", physical), List.of(sharding, encryption), new Properties());
    }
}
