package com.nageoffer.shortlink.admin.authority.identity.model;

public record AgentSessionBootstrapRequest(
        String agentType,
        ClientContext clientContext
) {

    public record ClientContext(String locale, String timezone) {
    }
}
