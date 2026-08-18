package com.jupiter.shortlink.agent;

import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AgentProperties.class, DeepSeekProperties.class})
public class ShortLinkAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkAgentApplication.class, args);
    }
}
