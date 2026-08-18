package com.jupiter.shortlink.aggregation;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 短链接聚合应用
 */
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = {
        "com.jupiter.shortlink.admin",
        "com.jupiter.shortlink.project",
        "com.jupiter.shortlink.aggregation"
})
@MapperScan(value = {
        "com.jupiter.shortlink.project.dao.mapper",
        "com.jupiter.shortlink.admin.dao.mapper"
})
public class AggregationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AggregationServiceApplication.class, args);
    }
}