package com.nageoffer.shortlink.agent.infrastructure.persistence;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wires the native Spring AI Alibaba checkpoint saver to the application's
 * primary datasource. The saver owns its GRAPH_THREAD and GRAPH_CHECKPOINT
 * tables, separate from the redacted business snapshot table.
 */
@Configuration(proxyBeanMethods = false)
public class MysqlGraphSaverConfiguration {

    @Bean
    public MysqlSaver mysqlGraphSaver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
    }
}
