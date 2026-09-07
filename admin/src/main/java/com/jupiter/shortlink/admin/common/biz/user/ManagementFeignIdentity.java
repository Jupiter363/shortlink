package com.jupiter.shortlink.admin.common.biz.user;

import feign.RequestInterceptor;
import feign.RequestTemplate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ManagementFeignIdentity implements RequestInterceptor {
    private final String token;

    public ManagementFeignIdentity(@Value("${shortlink.internal-token:}") String token) {
        this.token = token;
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template.feignTarget() != null
                && !java.util.Set.of(
                                "shortlink-command",
                                "shortlink-command-groups",
                                "shortlink-command-risk",
                                "batch-command")
                        .contains(template.feignTarget().name())) return;
        if (token.isBlank())
            throw new IllegalStateException("Internal service token is not configured");
        template.removeHeader("X-Internal-Token");
        template.header("X-Internal-Token", token);
        template.removeHeader("x-shortlink-tenant-id");
        template.removeHeader("x-shortlink-username");
        template.removeHeader("x-shortlink-auth-version");
        if (UserContext.getUserId() != null && UserContext.getAuthVersion() != null) {
            template.header("x-shortlink-tenant-id", UserContext.getUserId());
            template.header("x-shortlink-username", UserContext.getUsername());
            template.header(
                    "x-shortlink-auth-version", Long.toString(UserContext.getAuthVersion()));
        }
    }
}
