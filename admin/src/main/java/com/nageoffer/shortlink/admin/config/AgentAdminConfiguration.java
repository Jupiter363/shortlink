package com.nageoffer.shortlink.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "short-link.agent.admin")
public class AgentAdminConfiguration {

    private String internalToken = "";

    private boolean internalTokenDevMode = false;
}
